package com.wikikt.service

import com.wikikt.BuildInfo
import com.wikikt.model.nowMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A published WikiKT release, as learned from the GitHub Releases API. */
data class ReleaseInfo(
    val version: SemVer,
    /** The raw tag, e.g. "v1.2.0" — shown verbatim and used in upgrade command snippets. */
    val tag: String,
    /** Release page on github.com (release notes live there). */
    val htmlUrl: String,
    /** ISO-8601 publish timestamp as GitHub reports it, or null. */
    val publishedAt: String?,
)

/** Outcome of an update check. Every state renders; none of them ever throws into a page. */
sealed interface UpdateCheck {
    /** The WIKIKT_UPDATE_CHECK kill switch is set: no network call can happen, UI cannot override. */
    data object Disabled : UpdateCheck

    /** No admin has opted in yet (or an admin declined) — the page shows the consent card, no I/O. */
    data object NotEnabled : UpdateCheck

    /** Dev/-SNAPSHOT build: there is no release to compare against, so checking is meaningless. */
    data object NotApplicable : UpdateCheck

    data class UpToDate(val checkedAt: Long) : UpdateCheck

    data class Available(val release: ReleaseInfo, val checkedAt: Long) : UpdateCheck

    /** The check could not complete; [message] is already scrubbed of internal detail. */
    data class Failed(val message: String, val checkedAt: Long) : UpdateCheck
}

/**
 * Lazily checks the GitHub Releases API for a newer WikiKT release. "Lazily" is load-bearing: there
 * is no background poller — a check happens only when a root admin views Administration > Updates
 * (and the cached result is stale), or presses "Check now". The request is deliberately anonymous
 * and constant: no version, hostname, or identifier is ever sent (the Updates page promises this).
 *
 * The result cache is in-memory only. It is instance-wide, 24 h fresh, and losing it on restart
 * costs exactly one HTTP GET — not worth a database row, and `app_settings` is per-site anyway.
 * Failures are cached too (shorter TTL): without that, every page view on a host with no egress
 * would block on connect timeouts, making the admin console unusable behind a firewall.
 */
class UpdateService(
    private val settings: SettingsService,
    /** WikiKtConfig.updateCheckAllowed — the WIKIKT_UPDATE_CHECK kill switch. */
    private val allowed: Boolean,
    private val currentVersion: String = BuildInfo.version,
    private val releaseBuild: Boolean = BuildInfo.isRelease,
    /** Test seam (mirrors EmailService's injected sender): returns the response body, throws on failure. */
    private val fetch: suspend (url: String) -> String = ::fetchViaJdkClient,
    private val clock: () -> Long = ::nowMillis,
) {
    private val logger = LoggerFactory.getLogger(UpdateService::class.java)

    private data class Cached(val result: UpdateCheck, val at: Long, val failed: Boolean)

    @Volatile private var cached: Cached? = null

    /** When "Check now" last actually forced a fetch; null = never (a first force is always allowed). */
    @Volatile private var lastForceAt: Long? = null
    private val inFlight = Mutex()

    /** Whether an admin has enabled checks; null = never asked (consent card). */
    suspend fun optIn(): Boolean? =
        settings.get(settings.instanceAnchorSiteId(), SettingsService.UPDATE_CHECK_ENABLED)?.toBooleanStrictOrNull()

    suspend fun setOptIn(enabled: Boolean) {
        settings.setBool(settings.instanceAnchorSiteId(), SettingsService.UPDATE_CHECK_ENABLED, enabled)
    }

    /**
     * The current update status, from cache when fresh. [force] bypasses the TTL ("Check now") but is
     * itself rate-limited to once per [FORCE_MIN_INTERVAL_MS] so the button can't hammer GitHub.
     * Never throws: network/parse problems come back as [UpdateCheck.Failed].
     */
    suspend fun check(force: Boolean = false): UpdateCheck {
        if (!allowed) return UpdateCheck.Disabled
        if (!releaseBuild) return UpdateCheck.NotApplicable
        if (optIn() != true) return UpdateCheck.NotEnabled

        fun forceAllowed(at: Long) = force && lastForceAt.let { it == null || (at - it) >= FORCE_MIN_INTERVAL_MS }

        val now = clock()
        cached?.let { c ->
            val ttl = if (c.failed) FAILURE_TTL_MS else SUCCESS_TTL_MS
            if (!forceAllowed(now) && (now - c.at) < ttl) return c.result
        }

        // Single-flight: two admins loading the page concurrently produce one request. Re-check the
        // cache (and the force rate limit) inside the lock — the winner has usually already filled
        // the cache, or spent the force allowance, for the waiter.
        return inFlight.withLock {
            val t = clock()
            val stillForced = forceAllowed(t)
            cached?.let { c ->
                val ttl = if (c.failed) FAILURE_TTL_MS else SUCCESS_TTL_MS
                if (!stillForced && (t - c.at) < ttl) return@withLock c.result
            }
            if (stillForced) lastForceAt = t
            val result = runCatching { fetchLatest(t) }.getOrElse { e ->
                // Scrubbed message to the page; real cause at DEBUG only (mirrors the StatusPages rule
                // of never echoing internals).
                logger.debug("Update check failed", e)
                UpdateCheck.Failed("Could not reach github.com to check for releases.", t)
            }
            cached = Cached(result, t, failed = result is UpdateCheck.Failed)
            result
        }
    }

    private suspend fun fetchLatest(now: Long): UpdateCheck {
        val body = fetch(LATEST_RELEASE_URL)
        val obj = Json.parseToJsonElement(body).jsonObject
        val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return UpdateCheck.UpToDate(now)
        val latest = SemVer.parse(tag)
        val current = SemVer.parse(currentVersion)
        // Unparseable on EITHER side means "no update available", never "update available": offering
        // an install against a version we can't order is how a bad tag becomes a bad upgrade.
        if (latest == null || current == null || latest <= current) return UpdateCheck.UpToDate(now)
        return UpdateCheck.Available(
            ReleaseInfo(
                version = latest,
                tag = tag,
                htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: RELEASES_PAGE_URL,
                publishedAt = obj["published_at"]?.jsonPrimitive?.content,
            ),
            now,
        )
    }

    companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/RMoRobert/WikiKT/releases/latest"
        const val RELEASES_PAGE_URL = "https://github.com/RMoRobert/WikiKT/releases"
        const val SUCCESS_TTL_MS = 24 * 60 * 60 * 1000L
        const val FAILURE_TTL_MS = 60 * 60 * 1000L
        const val FORCE_MIN_INTERVAL_MS = 5 * 60 * 1000L

        /** Reject bodies past this size instead of buffering them (a hijacked endpoint must not OOM us). */
        const val MAX_RESPONSE_BYTES = 256 * 1024

        // One GET a day doesn't justify a client dependency: the JDK's client, blocking inside
        // Dispatchers.IO, is the same pattern GitSyncService uses for subprocesses. HTTP/1.1 avoids
        // rare h2-upgrade stalls behind corporate proxies; Redirect.NORMAL never follows HTTPS->HTTP.
        private val http: HttpClient by lazy {
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1)
                .build()
        }

        internal suspend fun fetchViaJdkClient(url: String): String = withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                // No version in the UA, no query params, nothing instance-identifying: the request must
                // stay indistinguishable from any other anonymous fetch of this URL. (GitHub 403s
                // UA-less requests, so one is required.)
                .header("User-Agent", "WikiKT-update-check")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} from $url" }
            // .use matters: an unclosed ofInputStream body leaks the connection. readNBytes caps the
            // buffer; a body larger than the cap is refused, not truncated (truncated JSON would just
            // become a parse failure anyway, but the explicit error is clearer in the debug log).
            response.body().use { stream ->
                val bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1)
                check(bytes.size <= MAX_RESPONSE_BYTES) { "Response from $url exceeds $MAX_RESPONSE_BYTES bytes" }
                String(bytes, Charsets.UTF_8)
            }
        }
    }
}
