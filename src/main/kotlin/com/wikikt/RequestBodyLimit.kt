package com.wikikt

import com.wikikt.config.AssetConfig
import com.wikikt.service.BackupService
import com.wikikt.service.SettingsService
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

/**
 * Room for the non-file parts of a multipart upload -- form fields, part headers, boundaries -- on top
 * of the file bytes a route accepts.
 */
internal const val MULTIPART_OVERHEAD_BYTES = 1_048_576L

/** The largest body an upload of [files] files may have: each at the per-file cap, plus the form around them. */
internal fun AssetConfig.uploadBodyLimit(files: Int): Long = files.toLong() * maxUploadSizeBytes + MULTIPART_OVERHEAD_BYTES

private val ASSET_REPLACE_PATH = Regex("/f/[^/]+/replace")

/**
 * Caps the size of a request body. Every route that reads one through `receive` -- form posts, the
 * JSON API -- holds the whole body in memory, and nothing bounded that: an anonymous client could POST
 * gigabytes at /login and have the server buffer them. The cap is
 * [com.wikikt.config.WikiKtConfig.maxRequestBodyBytes] for everything except the three multipart routes
 * that stream files to disk under caps of their own, which keep the ceilings their handlers already
 * enforce: an upload of as many files as any site may allow, a one-file replace, and a backup restore.
 *
 * Ktor's plugin refuses a declared Content-Length over the limit before the route runs, without reading
 * a byte, and cuts a chunked or under-declared body off at the limit as it streams in. Both surface as
 * PayloadTooLargeException, which the StatusPages catch-all in Application.kt answers with a plain 413.
 * Installed before [configureNulByteGuard], whose JSON check buffers the body: the receive pipeline runs
 * its interceptors in installation order, so the limit has to wrap the channel first.
 */
fun Application.configureRequestBodyLimit() {
    install(RequestBodyLimit) {
        bodyLimit { call -> call.requestBodyLimit() }
    }
}

private fun ApplicationCall.requestBodyLimit(): Long {
    val config = application.appContext.config
    if (request.httpMethod == HttpMethod.Post) {
        val path = request.path()
        when {
            // Asset upload: the per-site file count is a database read the plugin can't make, so the
            // ceiling is the most files any site may be allowed; the handler's own check applies the
            // site's number. Every ceiling leaves the same room for the form around the file bytes.
            path == "/f" -> return config.assets.uploadBodyLimit(SettingsService.MAX_UPLOAD_FILE_LIMIT)
            ASSET_REPLACE_PATH.matches(path) -> return config.assets.uploadBodyLimit(1)
            path == "/a/backup/restore" -> return BackupService.MAX_RESTORE_UPLOAD_BYTES + MULTIPART_OVERHEAD_BYTES
        }
    }
    return config.maxRequestBodyBytes
}
