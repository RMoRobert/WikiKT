package com.wikikt.service

import com.wikikt.config.SelfUpdateDirsConfig
import com.wikikt.model.nowMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The updater sidecar's periodic heartbeat (`updater.json` in the state dir). Its freshness -- not
 * volume existence or an environment variable -- is how the app knows an updater is actually running: the
 * volume exists even with the `selfupdate` profile off, and an env flag would lie when the container is
 * stopped. [runningComposeRevision] is the `com.wikikt.compose-revision` label of the *running* app
 * container, reported by the updater (which can `docker inspect`; the app cannot see its own labels);
 * it lets the Updates page warn about a required Compose-file change before Install is clicked.
 */
@Serializable
data class UpdaterHeartbeat(
    val schema: Int = 0,
    val protocol: Int = 0,
    val beatAt: Long = 0,
    val composeProject: String? = null,
    val targetService: String? = null,
    val capabilities: List<String> = emptyList(),
    val runningComposeRevision: Int? = null,
)

/** The updater's progress/result report (`status.json`). Single writer: the updater. */
@Serializable
data class UpdaterStatus(
    val schema: Int = 0,
    val requestId: String = "",
    val phase: String = "",
    val terminal: Boolean = false,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
    val finishedAt: Long? = null,
    val fromVersion: String? = null,
    val fromDigest: String? = null,
    val toVersion: String? = null,
    val toDigest: String? = null,
    val message: String? = null,
    val backupPath: String? = null,
    val logTail: List<String> = emptyList(),
)

/** What the app writes to ring the doorbell (`request.json`). Everything here is telemetry-only on
 *  the updater side — nothing in it may become a docker argument, image ref, path, tag, or shell word. */
@Serializable
private data class UpdateRequest(
    val schema: Int,
    val requestId: String,
    val requestedAt: Long,
    val requestedBy: String,
    val fromVersion: String,
    val expectVersion: String,
)

sealed interface UpdaterPresence {
    /** No heartbeat file: the `selfupdate` profile isn't enabled (or the updater never started). */
    data object NotInstalled : UpdaterPresence

    /** Heartbeat exists but is old: the updater container is stopped or wedged. */
    data class Stale(val lastBeatAt: Long) : UpdaterPresence

    data class Available(val heartbeat: UpdaterHeartbeat) : UpdaterPresence
}

/**
 * The gap between clicking Install and the updater picking the request up (its poll is ~10 s).
 * Without this state the post-click page looks identical to the pre-click one, as if the button
 * did nothing. Derived entirely from disk (`request.json` vs `status.json`'s requestId), so it
 * survives the PRG redirect and any reload.
 */
sealed interface PendingInstall {
    /** Request written, not yet picked up; normal for the first seconds after the click. */
    data class Waiting(val requestedAt: Long, val requestedBy: String) : PendingInstall

    /** Still unclaimed well past the poll interval: the updater likely stopped. */
    data class Unclaimed(val requestedAt: Long) : PendingInstall
}

enum class InstallRequestOutcome { REQUESTED, UPDATER_NOT_AVAILABLE, ALREADY_RUNNING, ALREADY_REQUESTED }

/**
 * App side of the self-update handshake with the `wikikt-updater` sidecar. See
 * `docker/updater/updater.sh` for the other half and `docker/README.md` for the trust model. The
 * short version: the updater holds the Docker socket (root-equivalent on the host) and the app never
 * does; the app's request file is a doorbell, not a command; and the mounts enforce direction
 * (request dir: app rw / updater ro; state dir: app ro / updater rw), so a compromised app cannot
 * forge the updater's status or heartbeat.
 *
 * Every read here is defensive — size-capped, unknown-keys-tolerant, and returning null on any
 * parse problem — because these files render on an admin page and a garbage file must degrade to
 * "status unknown", never throw.
 */
class SelfUpdateService(
    private val dirs: SelfUpdateDirsConfig?,
    private val settings: SettingsService,
    private val clock: () -> Long = ::nowMillis,
) {
    private val logger = LoggerFactory.getLogger(SelfUpdateService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** False outside the Docker stack: the Updates page then shows manual instructions only. */
    val configured: Boolean get() = dirs != null

    suspend fun presence(): UpdaterPresence {
        val d = dirs ?: return UpdaterPresence.NotInstalled
        val beat = readJson<UpdaterHeartbeat>(d.stateDir.resolve(HEARTBEAT_FILE)) ?: return UpdaterPresence.NotInstalled
        if (beat.schema != SCHEMA || beat.protocol != PROTOCOL) {
            // A future/past updater we don't speak with; surface as stale so the page says "check the
            // updater container" rather than offering an Install that would be ignored or misread.
            return UpdaterPresence.Stale(beat.beatAt)
        }
        return if (clock() - beat.beatAt <= HEARTBEAT_FRESH_MS) {
            UpdaterPresence.Available(beat)
        } else {
            UpdaterPresence.Stale(beat.beatAt)
        }
    }

    suspend fun status(): UpdaterStatus? {
        val d = dirs ?: return null
        return readJson<UpdaterStatus>(d.stateDir.resolve(STATUS_FILE))?.takeIf { it.schema == SCHEMA }
    }

    /** A non-terminal status whose updater last wrote recently = an update is genuinely in flight.
     *  Non-terminal + old means the updater died mid-run; the page shows "status unknown" for that. */
    fun isRunning(status: UpdaterStatus?): Boolean =
        status != null && !status.terminal && (clock() - status.updatedAt) <= STATUS_STALE_MS

    fun isAbandoned(status: UpdaterStatus?): Boolean =
        status != null && !status.terminal && (clock() - status.updatedAt) > STATUS_STALE_MS

    /**
     * The last written request, when the updater hasn't acknowledged it yet ([status] carries the
     * requestId the updater is/was working on; the updater echoes the id it consumed, so a match
     * means the request's story is now told by the status card instead). Requests older than
     * [STATUS_STALE_MS] return null so a leftover file (e.g. a recreated state volume) won't make
     * this state get stuck.
     */
    suspend fun pending(status: UpdaterStatus?): PendingInstall? {
        val d = dirs ?: return null
        val req = readJson<UpdateRequest>(d.requestDir.resolve(REQUEST_FILE))?.takeIf { it.schema == SCHEMA } ?: return null
        if (status?.requestId == req.requestId) return null
        return when (val age = clock() - req.requestedAt) {
            in 0..PENDING_PICKUP_MS -> PendingInstall.Waiting(req.requestedAt, req.requestedBy)
            in PENDING_PICKUP_MS..STATUS_STALE_MS -> PendingInstall.Unclaimed(req.requestedAt)
            else -> null.also { if (age < 0) logger.debug("request.json is from the future; ignoring") }
        }
    }

    /**
     * Rings the doorbell. The app-side running check is UX only; the real single-flight guarantee is
     * the updater's own lock (`mkdir /state/.lock`) plus its request-id replay protection, because
     * this process is about to be replaced and cannot hold a lock across its own restart.
     */
    suspend fun requestInstall(requestedBy: String, currentVersion: String, expectVersion: String): InstallRequestOutcome {
        val d = dirs ?: return InstallRequestOutcome.UPDATER_NOT_AVAILABLE
        if (presence() !is UpdaterPresence.Available) return InstallRequestOutcome.UPDATER_NOT_AVAILABLE
        val current = status()
        if (isRunning(current)) return InstallRequestOutcome.ALREADY_RUNNING
        // A request already waiting for pickup: don't overwrite it (double-click, or two admins). An
        // Unclaimed one may be overwritten — re-requesting is exactly the recovery for that state.
        if (pending(current) is PendingInstall.Waiting) return InstallRequestOutcome.ALREADY_REQUESTED

        val request = UpdateRequest(
            schema = SCHEMA,
            requestId = UUID.randomUUID().toString(),
            requestedAt = clock(), // epoch MILLIS — every timestamp in the protocol is millis
            requestedBy = requestedBy.take(64),
            fromVersion = currentVersion.take(64),
            expectVersion = expectVersion.take(64),
        )

        // Audit breadcrumb first (survives the restart; corroborates the updater's status afterwards).
        val anchor = settings.instanceAnchorSiteId()
        settings.set(anchor, SettingsService.UPDATE_LAST_REQUEST_ID, request.requestId)
        settings.set(anchor, SettingsService.UPDATE_LAST_REQUESTED_AT, clock().toString())
        settings.set(anchor, SettingsService.UPDATE_LAST_REQUESTED_BY, request.requestedBy)
        settings.set(anchor, SettingsService.UPDATE_LAST_REQUESTED_FROM, request.fromVersion)
        logger.info("Self-update to {} requested by {} (running {})", request.expectVersion, request.requestedBy, request.fromVersion)

        withContext(Dispatchers.IO) {
            Files.createDirectories(d.requestDir)
            // Atomic move so the updater never reads a half-written file.
            val tmp = Files.createTempFile(d.requestDir, ".request", ".tmp")
            Files.writeString(tmp, json.encodeToString(request))
            Files.move(tmp, d.requestDir.resolve(REQUEST_FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        // The previous run's result card is now stale news -- a new update is under way, and until the
        // updater overwrites status.json (its poll is ~10 s) the old outcome would sit next to the
        // "requested" card as if it described this run. Retire it as if it had been dismissed.
        markOutcomeDismissed(current)
        return InstallRequestOutcome.REQUESTED
    }

    /**
     * Hides the current terminal outcome from the Updates page. The result of a finished update is
     * worth reading once; after that a success note would sit on the page forever, since only the
     * next update replaces it. Nothing is destroyed: the updater owns `status.json` (the app's mount
     * is read-only) and the container logs keep the full record — this just remembers which outcome
     * was acknowledged, so the *next* one still shows unread.
     */
    suspend fun dismissOutcome() = markOutcomeDismissed(status())

    /** Records [status] as acknowledged, if it is a terminal one. No-op for anything else. */
    private suspend fun markOutcomeDismissed(status: UpdaterStatus?) {
        val terminal = status?.takeIf { it.terminal } ?: return
        settings.set(settings.instanceAnchorSiteId(), SettingsService.UPDATE_LAST_OUTCOME_DISMISSED, outcomeToken(terminal))
    }

    /** Whether [dismissOutcome] was already called for this exact outcome. */
    suspend fun isOutcomeDismissed(status: UpdaterStatus?): Boolean {
        val terminal = status?.takeIf { it.terminal } ?: return false
        return settings.get(settings.instanceAnchorSiteId(), SettingsService.UPDATE_LAST_OUTCOME_DISMISSED) ==
            outcomeToken(terminal)
    }

    /**
     * Stable identity of one terminal outcome. The updater echoes the requestId it consumed, which is
     * exactly per-run; a status without one (an updater-initiated run, or a future updater that omits
     * it) falls back to the finish time so dismissing it can't also hide the next result.
     */
    private fun outcomeToken(status: UpdaterStatus): String =
        status.requestId.ifEmpty { "finishedAt:${status.finishedAt ?: status.updatedAt}" }

    /** Size-capped, throw-proof JSON read. Any problem (missing, huge, garbage) is null. */
    private suspend inline fun <reified T> readJson(path: Path): T? = withContext(Dispatchers.IO) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_FILE_BYTES) return@withContext null
            json.decodeFromString<T>(Files.readString(path))
        } catch (e: Exception) {
            logger.debug("Unreadable updater file {}", path, e)
            null
        }
    }

    companion object {
        const val SCHEMA = 1

        /** Bump when the file contract changes; the updater reports its own in the heartbeat. */
        const val PROTOCOL = 1
        const val REQUEST_FILE = "request.json"
        const val STATUS_FILE = "status.json"
        const val HEARTBEAT_FILE = "updater.json"

        /** Heartbeat older than this = updater not responding (it rewrites every ~10 s poll). */
        const val HEARTBEAT_FRESH_MS = 180_000L

        /** How long a written request may sit unacknowledged before "waiting" (a spinner, normal)
         *  becomes "unclaimed" (a warning). The updater polls every ~10s; 60s should be generous. */
        const val PENDING_PICKUP_MS = 60_000L

        /** Non-terminal status older than this = the updater died mid-run; show "unknown". */
        const val STATUS_STALE_MS = 15 * 60_000L

        /** Cap on any file read from the shared volumes (status carries a log tail; 64 KiB is ample). */
        const val MAX_FILE_BYTES = 64 * 1024L
    }
}
