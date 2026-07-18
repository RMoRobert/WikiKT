package com.wikikt.service

import com.wikikt.db.ContentFormat
import com.wikikt.model.nowMillis
import com.wikikt.routing.normalizeLocaleSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory

/** Snapshot of the `gitSync.*` settings taken at the start of one sync run. */
data class GitSyncSettings(
    val mode: String,
    val repoUrl: String,
    val branch: String,
    val username: String,
    val token: String,
    val authorName: String,
    val authorEmail: String,
    val intervalMinutes: Int,
) {
    val enabled: Boolean get() = mode != "off" && repoUrl.isNotBlank()
}

data class GitSyncResult(val ok: Boolean, val message: String, val commitId: String? = null)

/**
 * Synchronizes wiki content with a git repository by shelling out to the system `git` binary
 * Requires git on the host.
 *
 * Repository layout and page file format are [PageFileFormat] (WikiJS-compatible); assets are
 * plain files at `{locale}/{path}`, except the default locale sits at the repo root).
 * Push and pull are symmetric ([repoPathFor] / [fileTarget]): push writes default-locale
 * content to the root and other locales under `{locale}/`; pull reads a root file as the default
 * locale and normalizes a lowercase locale folder like `pt-br` to `pt-BR`
 *
 * Modes: `push` mirrors the wiki into the repo, `pull` mirrors the repo into the
 * wiki, `bidirectional` pulls then pushes. Commits are batched — one commit per sync run carrying
 * everything that changed since the last one, not one per page save. Pulls are diff-based against
 * [SettingsService.GIT_SYNC_LAST_SYNCED_COMMIT]: only files the repo actually changed since the
 * last sync are applied (so an old repo copy never clobbers a newer wiki edit), and repo-side
 * deletions delete the wiki page. When that commit is unknown (first sync, remote force-push) the
 * pull falls back to upserting every file and deletes nothing. Imports go through
 * [ContentImporter], so every imported change still lands in the page's revision history.
 */
class GitSyncService(
    private val settings: SettingsService,
    private val sites: SiteService,
    private val pages: PageService,
    private val assets: AssetService,
    private val repoDir: Path,
    private val importer: ContentImporter,
    private val defaultLocale: String,
) {
    private val logger = LoggerFactory.getLogger(GitSyncService::class.java)

    /**
     * One lock per site. Runs of the SAME site are serialized — a scheduled sync and a manual "Sync
     * now" for one site share its clone dir and must never interleave — but different sites sync
     * concurrently, so one site's slow or hung remote can't block every other site. (Each site's git
     * network work runs in its own subprocess; DB writes still funnel through the shared connection.)
     */
    private val siteMutexes = java.util.concurrent.ConcurrentHashMap<UInt, Mutex>()
    private fun mutexFor(siteId: UInt): Mutex = siteMutexes.computeIfAbsent(siteId) { Mutex() }

    /**
     * Detached scope for manually-triggered runs. A manual sync must NOT run on the request's call
     * scope: a git fetch/push can outlive the reverse proxy's response timeout, and when the browser
     * gets the resulting 504 the call scope is cancelled — aborting the sync partway and losing the
     * recorded outcome. Running here instead lets the sync finish and record its result (success or a
     * redacted git error) into the status settings the admin page reads, regardless of the browser.
     */
    private val runScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Sites with a manual run currently queued or executing — drives the "in progress" admin UI. */
    private val runningSites = java.util.concurrent.ConcurrentHashMap.newKeySet<UInt>()

    /** A manually-triggerable run, mapped to the corresponding suspend entry point. */
    enum class ManualAction { SYNC, EXPORT, IMPORT }

    /**
     * Fire-and-forget a manual [action] for [siteId] on [runScope] so the HTTP request returns at
     * once. Returns false (and does nothing) when a manual run for this site is already queued or
     * running, so a second click can't stack a duplicate. The run records its own outcome via
     * [locked]; [isRunning] reports progress and the status settings carry the final result.
     */
    fun triggerManual(siteId: UInt, action: ManualAction): Boolean {
        if (!runningSites.add(siteId)) return false
        runScope.launch {
            try {
                when (action) {
                    ManualAction.SYNC -> syncNow(siteId)
                    ManualAction.EXPORT -> exportEverything(siteId)
                    ManualAction.IMPORT -> importEverything(siteId)
                }
            } catch (e: Throwable) {
                logger.warn("Manual git sync failed for site {}", siteId, e)
            } finally {
                runningSites.remove(siteId)
            }
        }
        return true
    }

    /** Whether a manually-triggered run for [siteId] is currently queued or executing. */
    fun isRunning(siteId: UInt): Boolean = runningSites.contains(siteId)

    /** Each site mirrors to its own repo, so it gets its own local clone under the shared sync dir. */
    private fun siteRepoDir(siteId: UInt): Path = repoDir.resolve("site-$siteId")

    suspend fun currentSettings(siteId: UInt): GitSyncSettings {
        val s = SettingsService
        return GitSyncSettings(
            mode = settings.get(siteId, s.GIT_SYNC_MODE)?.ifBlank { null } ?: "off",
            repoUrl = settings.get(siteId, s.GIT_SYNC_REPO_URL).orEmpty().trim(),
            branch = settings.get(siteId, s.GIT_SYNC_BRANCH)?.trim()?.ifBlank { null } ?: s.DEFAULT_GIT_SYNC_BRANCH,
            username = settings.get(siteId, s.GIT_SYNC_USERNAME).orEmpty().trim(),
            token = settings.get(siteId, s.GIT_SYNC_TOKEN).orEmpty(),
            authorName = settings.get(siteId, s.GIT_SYNC_AUTHOR_NAME)?.trim()?.ifBlank { null } ?: "WikiKT",
            authorEmail = settings.get(siteId, s.GIT_SYNC_AUTHOR_EMAIL)?.trim()?.ifBlank { null } ?: "wikikt@localhost",
            intervalMinutes = settings.get(siteId, s.GIT_SYNC_INTERVAL_MINUTES)?.toIntOrNull()
                ?: s.DEFAULT_GIT_SYNC_INTERVAL_MINUTES,
        )
    }

    /** Runs one sync for [siteId] per its configured mode, recording the outcome in its status settings. */
    suspend fun syncNow(siteId: UInt): GitSyncResult = locked(siteId) { s, dir ->
        when (s.mode) {
            "push" -> exportCommitPush(siteId, dir, s, prepare(dir, s))
            "pull" -> importFromRemote(siteId, dir, s, prepare(dir, s), everything = false)
            "bidirectional" -> {
                val remote = prepare(dir, s)
                val pulled = importFromRemote(siteId, dir, s, remote, everything = false)
                if (!pulled.ok) return@locked pulled
                val pushed = exportCommitPush(siteId, dir, s, remote)
                if (!pushed.ok) return@locked pushed
                GitSyncResult(true, "${pulled.message} ${pushed.message}", pushed.commitId)
            }
            else -> GitSyncResult(false, "Synchronization mode is off — choose a mode first.")
        }
    }

    /** Force action ("Export Everything to Git"): full push regardless of the configured mode. */
    suspend fun exportEverything(siteId: UInt): GitSyncResult = locked(siteId) { s, dir -> exportCommitPush(siteId, dir, s, prepare(dir, s)) }

    /**
     * Force action ("Import Everything from Git"): upserts every file in the repository into
     * the wiki regardless of mode. Never deletes wiki content.
     */
    suspend fun importEverything(siteId: UInt): GitSyncResult = locked(siteId) { s, dir ->
        importFromRemote(siteId, dir, s, prepare(dir, s), everything = true)
    }

    /**
     * Called every minute by the background scheduler; for EACH site runs [syncNow] when its mode is
     * enabled, the interval isn't "manual only", and enough time has passed since its last run.
     */
    suspend fun autoSyncTick(now: Long) {
        for (site in sites.all()) {
            val s = currentSettings(site.id)
            if (!s.enabled || s.intervalMinutes <= 0) continue
            val lastRun = settings.get(site.id, SettingsService.GIT_SYNC_LAST_RUN_AT)?.toLongOrNull() ?: 0
            if (now - lastRun < s.intervalMinutes * 60_000L) continue
            val result = syncNow(site.id)
            if (!result.ok) logger.warn("Scheduled git sync failed for site {}: {}", site.id, result.message)
        }
    }

    /**
     * Removes a site's local clone when the site is deleted. The clone's `.git/config` embeds the push
     * credential (`https://user:token@host/…`), so leaving it behind would orphan that token on disk —
     * and a future site reusing the numeric id would inherit the stale clone. Takes the site's run lock
     * so it can't race an in-flight sync, drops the lock entry afterward, and is best-effort: a disk
     * hiccup is logged rather than failing the site deletion (whose DB rows are already the source of
     * truth). No-op when the clone was never created.
     */
    suspend fun deleteClone(siteId: UInt) {
        mutexFor(siteId).withLock {
            val dir = siteRepoDir(siteId)
            withContext(Dispatchers.IO) {
                runCatching {
                    if (Files.exists(dir)) {
                        Files.walk(dir).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
                    }
                }.onFailure { logger.warn("Could not fully remove git-sync clone for deleted site {} at {}", siteId, dir, it) }
            }
        }
        siteMutexes.remove(siteId)
    }

    /** Takes the site's run lock, snapshots [siteId]'s settings + clone dir, runs [body] safely, records outcome. */
    private suspend fun locked(siteId: UInt, body: suspend (GitSyncSettings, Path) -> GitSyncResult): GitSyncResult = mutexFor(siteId).withLock {
        val s = currentSettings(siteId)
        val dir = siteRepoDir(siteId)
        val result = if (s.repoUrl.isBlank()) {
            GitSyncResult(false, "No repository URL configured.")
        } else {
            runCatching { body(s, dir) }.getOrElse { e ->
                logger.warn("Git sync failed", e)
                GitSyncResult(false, redact(e.message ?: "Git sync failed.", s))
            }
        }
        settings.set(siteId, SettingsService.GIT_SYNC_LAST_RUN_AT, nowMillis().toString())
        settings.setBool(siteId, SettingsService.GIT_SYNC_LAST_OK, result.ok)
        settings.set(siteId, SettingsService.GIT_SYNC_LAST_RESULT, redact(result.message, s))
        settings.set(siteId, SettingsService.GIT_SYNC_LAST_COMMIT, result.commitId.orEmpty())
        result
    }

    /** The state of the remote branch after [prepare]: does it exist, and at which commit. */
    private data class RemoteState(val branchExists: Boolean, val head: String?)

    /**
     * Gets the local clone ready: init + remote on first use, fetch, and the working tree checked
     * out on the target branch at the remote head (when the remote branch exists).
     */
    private suspend fun prepare(dir: Path, s: GitSyncSettings): RemoteState {
        withContext(Dispatchers.IO) { Files.createDirectories(dir) }
        runCatching { git(dir, "--version") }.getOrElse {
            throw IllegalStateException("The git command is not available on this server. Install git to use synchronization.")
        }
        val url = authenticatedUrl(s)
        if (!withContext(Dispatchers.IO) { Files.exists(dir.resolve(".git")) }) {
            git(dir, "init")
            git(dir, "remote", "add", "origin", url)
        } else {
            git(dir, "remote", "set-url", "origin", url) // picks up URL/credential changes
        }
        git(dir, "fetch", "origin")
        val branchExists = gitOk(dir, "rev-parse", "--verify", "--quiet", "refs/remotes/origin/${s.branch}")
        if (gitOk(dir, "rev-parse", "--verify", "--quiet", "HEAD")) {
            git(dir, "checkout", "-B", s.branch)
        } else {
            git(dir, "symbolic-ref", "HEAD", "refs/heads/${s.branch}") // unborn repo: just point HEAD
        }
        if (branchExists) {
            git(dir, "reset", "--hard", "origin/${s.branch}")
            git(dir, "clean", "-fd")
        }
        return RemoteState(branchExists, if (branchExists) git(dir, "rev-parse", "refs/remotes/origin/${s.branch}").trim() else null)
    }

    // --- Push (wiki -> repository) ---

    /** Exports the full wiki state over the working tree, commits the delta (if any), and pushes. */
    private suspend fun exportCommitPush(siteId: UInt, dir: Path, s: GitSyncSettings, remote: RemoteState): GitSyncResult {
        val written = exportAll(siteId, dir)
        git(dir, "add", "-A")
        val changes = git(dir, "status", "--porcelain").trim().lines().count { it.isNotBlank() }
        var commitId: String? = null
        if (changes > 0) {
            git(
                dir, "-c", "user.name=${s.authorName}", "-c", "user.email=${s.authorEmail}",
                "commit", "-m", "docs: sync $changes change${if (changes == 1) "" else "s"} from WikiKT",
            )
            commitId = git(dir, "rev-parse", "--short", "HEAD").trim()
        }
        if (!gitOk(dir, "rev-parse", "--verify", "--quiet", "HEAD")) {
            return GitSyncResult(true, "Nothing to push — the wiki has no content yet.")
        }
        git(dir, "push", "-u", "origin", s.branch)
        settings.set(siteId, SettingsService.GIT_SYNC_LAST_SYNCED_COMMIT, git(dir, "rev-parse", "HEAD").trim())
        return if (commitId != null) {
            GitSyncResult(true, "Pushed $changes change${if (changes == 1) "" else "s"} to ${s.branch} (commit $commitId, $written files total).", commitId)
        } else {
            GitSyncResult(true, "No wiki changes to push ($written files, already in sync).", remote.head?.let { git(dir, "rev-parse", "--short", it).trim() })
        }
    }

    /** Writes every page and asset into the working tree (after clearing it). Returns file count. */
    private suspend fun exportAll(siteId: UInt, dir: Path): Int {
        val pageList = pages.list(siteId)
        val assetList = assets.list(siteId)
        return withContext(Dispatchers.IO) {
            clearWorkingTree(dir)
            var count = 0
            for (page in pageList) {
                Files.writeString(safeResolve(dir, repoPathFor(page.locale, PageFileFormat.pageFileName(page))), PageFileFormat.pageFileBody(page))
                count++
            }
            for (asset in assetList) {
                val source = assets.fileForId(asset.id)
                if (!Files.exists(source)) continue // metadata row without bytes; skip rather than fail
                Files.copy(source, safeResolve(dir, repoPathFor(asset.locale, asset.path)), StandardCopyOption.REPLACE_EXISTING)
                count++
            }
            count
        }
    }

    // --- Pull (repository -> wiki) ---

    /**
     * Imports repository content into the wiki. Diff mode applies only what changed between the
     * last-synced commit and the remote head (including deletions); [everything] (or an unknown
     * last-synced commit) upserts all tracked files and deletes nothing.
     */
    private suspend fun importFromRemote(siteId: UInt, dir: Path, s: GitSyncSettings, remote: RemoteState, everything: Boolean): GitSyncResult {
        val head = remote.head
            ?: return if (everything) {
                GitSyncResult(false, "The remote branch '${s.branch}' has no commits to import.")
            } else {
                GitSyncResult(true, "Nothing to pull — the remote branch has no commits yet.")
            }
        val last = settings.get(siteId, SettingsService.GIT_SYNC_LAST_SYNCED_COMMIT)?.ifBlank { null }
        val diffBase = last?.takeIf { !everything && gitOk(dir, "cat-file", "-e", "$it^{commit}") }
        val changes: List<Pair<Char, String>> = when {
            diffBase == null -> git(dir, "ls-files").trim().lines().filter { it.isNotBlank() }.map { 'A' to it }
            diffBase == head -> emptyList()
            else -> git(dir, "diff", "--name-status", "--no-renames", diffBase, head).trim().lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) null else line[0] to line.substring(tab + 1)
                }
        }

        var upserted = 0
        var deleted = 0
        var skipped = 0
        for ((status, file) in changes) {
            val target = fileTarget(dir, file) ?: run { skipped++; continue }
            when (status) {
                'D' -> if (deleteTarget(siteId, target)) deleted++ else skipped++
                // A, M, T (typechange), and the ls-files fallback all mean "make the wiki match the file".
                else -> when (upsertTarget(siteId, target)) {
                    ContentImporter.Outcome.APPLIED -> upserted++
                    ContentImporter.Outcome.UNCHANGED -> {}
                    ContentImporter.Outcome.SKIPPED -> skipped++
                }
            }
        }
        settings.set(siteId, SettingsService.GIT_SYNC_LAST_SYNCED_COMMIT, head)
        val skippedNote = if (skipped > 0) ", $skipped skipped" else ""
        return GitSyncResult(
            true,
            if (changes.isEmpty()) "Nothing to pull — already at the remote head."
            else "Pulled ${git(dir, "rev-parse", "--short", head).trim()}: $upserted imported, $deleted deleted$skippedNote.",
        )
    }

    /** What a repo file maps to in the wiki. Pages are `.md`/`.html`; everything else is an asset. */
    private sealed interface FileTarget {
        data class Page(val locale: String, val path: String, val format: ContentFormat, val file: Path) : FileTarget
        data class Asset(val locale: String, val path: String, val file: Path) : FileTarget
    }

    /**
     * The repo-relative path for a page/asset — the inverse of [fileTarget]. Default-locale content
     * sits at the repo root (mirroring WikiJS and keeping bidirectional round-trips stable); every
     * other locale is namespaced under `{locale}/`.
     */
    private fun repoPathFor(locale: String, subPath: String): String =
        if (locale == defaultLocale) subPath else "$locale/$subPath"

    /**
     * Maps a repo-relative file to its wiki identity — the inverse of [repoPathFor]. A leading locale
     * folder sets the locale (`pt-br/x.md` → locale `pt-BR`); a file whose first segment isn't a locale
     * is taken to be in the default locale, so a WikiJS export — English at the repo root, other locales
     * in `{locale}/` subfolders — imports as-is. Returns null only for the impossible empty path.
     */
    private fun fileTarget(dir: Path, relative: String): FileTarget? {
        val leadingLocale = if ('/' in relative) normalizeLocaleSegment(relative.substringBefore('/')) else null
        val locale = leadingLocale ?: defaultLocale
        val rest = if (leadingLocale != null) relative.substringAfter('/') else relative
        if (rest.isEmpty()) return null
        val file = dir.resolve(relative).normalize()
        if (!file.startsWith(dir)) return null
        return when {
            rest.endsWith(".md") -> FileTarget.Page(locale, rest.removeSuffix(".md"), ContentFormat.MARKDOWN, file)
            rest.endsWith(".html") -> FileTarget.Page(locale, rest.removeSuffix(".html"), ContentFormat.HTML, file)
            else -> FileTarget.Asset(locale, rest, file)
        }
    }

    private suspend fun upsertTarget(siteId: UInt, target: FileTarget): ContentImporter.Outcome = when (target) {
        is FileTarget.Page -> {
            val raw = withContext(Dispatchers.IO) {
                if (Files.exists(target.file)) Files.readString(target.file) else null
            }
            if (raw == null) ContentImporter.Outcome.SKIPPED
            else importer.upsertPage(siteId, target.locale, target.path, target.format, raw)
        }
        is FileTarget.Asset -> importer.upsertAsset(siteId, target.locale, target.path, target.file)
    }

    private suspend fun deleteTarget(siteId: UInt, target: FileTarget): Boolean = when (target) {
        is FileTarget.Page -> pages.findByLocaleAndPath(siteId, target.locale, target.path)?.let { pages.delete(it.id) } ?: false
        is FileTarget.Asset -> assets.findByLocaleAndPath(siteId, target.locale, target.path)?.let { assets.delete(it.id) } ?: false
    }

    // --- Working tree + git plumbing ---

    /** Deletes everything in the working tree except `.git`, so removals in the wiki delete files. */
    private fun clearWorkingTree(dir: Path) {
        Files.list(dir).use { entries ->
            entries.filter { it.fileName.toString() != ".git" }.forEach { entry ->
                Files.walk(entry).use { walk ->
                    walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    /** Resolves a repo-relative file path, refusing traversal outside the clone, creating parents. */
    private fun safeResolve(dir: Path, relative: String): Path {
        val resolved = dir.resolve(relative).normalize()
        require(resolved.startsWith(dir) && !resolved.startsWith(dir.resolve(".git"))) {
            "Refusing to write outside the sync directory: $relative"
        }
        require(!resolved.isDirectory()) { "Path collides with a directory: $relative" }
        Files.createDirectories(resolved.parent)
        return resolved
    }

    /**
     * The remote URL with HTTPS credentials embedded (`https://user:token@host/…`) when a token is
     * configured. Non-HTTP(S) URLs (SSH) pass through untouched and use the host's own git auth
     * (ssh keys / agent / credential helpers).
     */
    internal fun authenticatedUrl(s: GitSyncSettings): String {
        if (s.token.isBlank() || !s.repoUrl.matches(Regex("^https?://.*"))) return s.repoUrl
        val schemeEnd = s.repoUrl.indexOf("://") + 3
        val rest = s.repoUrl.substring(schemeEnd)
        if (rest.substringBefore('/').contains('@')) return s.repoUrl // already has userinfo
        val user = URLEncoder.encode(s.username.ifBlank { "git" }, Charsets.UTF_8)
        val token = URLEncoder.encode(s.token, Charsets.UTF_8)
        return s.repoUrl.substring(0, schemeEnd) + "$user:$token@" + rest
    }

    /** Strips the configured token from a message before it's stored or shown. */
    private fun redact(message: String, s: GitSyncSettings): String =
        if (s.token.isBlank()) message
        else message.replace(s.token, "***").replace(URLEncoder.encode(s.token, Charsets.UTF_8), "***")

    /** Runs git in [dir], returning stdout; non-zero exit throws with combined output. */
    private suspend fun git(dir: Path, vararg args: String): String {
        val (exit, out, err) = run(dir, *args)
        check(exit == 0) { "git ${args.firstOrNull { !it.startsWith("-") } ?: args.first()} failed: ${err.ifBlank { out }.trim()}" }
        return out
    }

    /** Runs git, reporting only whether it exited 0 (for existence probes like rev-parse --verify). */
    private suspend fun gitOk(dir: Path, vararg args: String): Boolean = run(dir, *args).first == 0

    private suspend fun run(dir: Path, vararg args: String): Triple<Int, String, String> = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(dir.toFile())
            .apply {
                environment()["GIT_TERMINAL_PROMPT"] = "0" // fail fast, never prompt
                // Hard allowlist of transports. This is the guarantee that a configured repo URL can
                // never invoke a command-executing transport such as `ext::sh -c ...` (or `fd::`),
                // which git would otherwise run as this process. Only real fetch/push transports are
                // permitted; `file` stays enabled for local-path remotes (and the test suite).
                environment()["GIT_ALLOW_PROTOCOL"] = "https:http:ssh:git:file"
            }
            .start()
        coroutineScope {
            val err = async { process.errorStream.bufferedReader().readText() }
            val out = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("git ${args.first()} timed out after ${GIT_TIMEOUT_SECONDS}s")
            }
            Triple(process.exitValue(), out, err.await())
        }
    }

    companion object {
        private const val GIT_TIMEOUT_SECONDS = 120L

        /**
         * Accepts only the repo-URL shapes we actually fetch/push over: `https`/`http`/`ssh`/`git`
         * scheme URLs, or scp-style `[user@]host:path`. This rejects command-executing pseudo-URLs
         * like `ext::sh -c ...` and `file://…` at the point an admin sets the value, so a bad URL
         * never even reaches git (defense-in-depth over the GIT_ALLOW_PROTOCOL transport allowlist).
         * Blank is allowed — it just means "sync not configured".
         */
        fun isAllowedRepoUrl(url: String): Boolean {
            val u = url.trim()
            if (u.isEmpty()) return true
            val scheme = Regex("^(https?|ssh|git)://\\S+$")
            // scp-form: host (optionally user@) then a single ':' then a path whose first char is not
            // another ':' — so `ext::sh …` (double colon) and anything with whitespace are rejected.
            val scp = Regex("^[A-Za-z0-9._-]+(@[A-Za-z0-9._-]+)?:[^:\\s/][^\\s]*$")
            return u.matches(scheme) || u.matches(scp)
        }
    }
}
