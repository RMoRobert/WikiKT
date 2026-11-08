package com.wikikt

import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.URLDecodeException
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.contentCharset
import io.ktor.server.request.contentType
import io.ktor.server.request.path
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray

private const val NUL = '\u0000'

/**
 * Answers 400 to any request carrying a NUL character (U+0000) in what the app reads from it: the
 * decoded path, query parameter names and values, form fields, JSON strings, and multipart part names,
 * filenames and field values. PostgreSQL text columns cannot hold 0x00, so one reaching a query fails
 * the statement, and the request with it: an anonymous `GET /en/%00` was enough for a 500. H2 (dev and
 * the test suite) stores it happily, which is why nothing caught it.
 *
 * Rejected rather than stripped: nothing legitimate in a URL, a form or wiki text needs the character,
 * and silently rewriting input would make what is saved differ from what was sent. Checked once, here,
 * so no route (present or future) has to care. Deliberately not covered: request headers (Netty already
 * refuses a NUL in a header with its own 400), session cookies (encrypted and signed, so their contents
 * are ours), and file content (binary, written to disk, never to a text column). Nor is wiki content
 * that arrives inside a backup archive or through git sync: both are admin-run.
 *
 * Installed after [configureSecurity], so the 400 still carries the security headers.
 */
fun Application.configureNulByteGuard() {
    intercept(ApplicationCallPipeline.Plugins) {
        // The raw path is still percent-encoded; decode it as routing will. A malformed escape would
        // fail routing with a 400 anyway, so give the same answer here.
        val path = try {
            call.request.path().decodeURLPart()
        } catch (e: URLDecodeException) {
            throw BadRequestException("Malformed path encoding", e)
        }
        // queryParameters decodes on first use, and throws BadRequestException itself when malformed.
        if (NUL in path || call.request.queryParameters.hasNul()) throw nulRejected()
    }

    // JSON is checked as text, before ContentNegotiation turns it into a request class this guard would
    // otherwise have to walk. Buffering costs nothing extra: the converter reads the whole body anyway.
    // Only application/json is decoded by ContentNegotiation here (see Serialization.kt).
    receivePipeline.intercept(ApplicationReceivePipeline.Before) { body ->
        if (body !is ByteReadChannel) return@intercept
        // A malformed Content-Type is left for the receive itself to reject, exactly as before.
        val type = runCatching { call.request.contentType() }.getOrNull() ?: return@intercept
        if (!type.match(ContentType.Application.Json)) return@intercept
        val bytes = body.toByteArray()
        if (jsonHasNul(String(bytes, call.request.contentCharset() ?: Charsets.UTF_8))) throw nulRejected()
        proceedWith(ByteReadChannel(bytes))
    }

    // Form and multipart bodies are checked after Ktor decodes them, so the check sees exactly the
    // strings the route will.
    receivePipeline.intercept(ApplicationReceivePipeline.After) { body ->
        when (body) {
            is Parameters -> if (body.hasNul()) throw nulRejected()
            // Parts stream in as the route reads them, so each is checked as it is handed over.
            is MultiPartData -> proceedWith(NulRejectingMultiPart(body))
        }
    }
}

/**
 * Whether JSON [text] holds a NUL, raw or as a `\u0000` escape. An escape counts only when its backslash
 * is not itself escaped (an odd run of backslashes before the `u`), so text that merely spells out
 * `\u0000` (sent as `\\u0000`) passes. `\u0000` is the only escape that decodes to NUL: zero has no
 * upper/lower-case spelling, and no surrogate pair produces it.
 */
internal fun jsonHasNul(text: String): Boolean {
    if (NUL in text) return true
    var at = text.indexOf("\\u0000")
    while (at >= 0) {
        var backslashes = 0
        while (at - backslashes >= 0 && text[at - backslashes] == '\\') backslashes++
        if (backslashes % 2 == 1) return true
        at = text.indexOf("\\u0000", at + 1)
    }
    return false
}

private fun Parameters.hasNul(): Boolean =
    entries().any { (name, values) -> NUL in name || values.any { NUL in it } }

private fun nulRejected() = BadRequestException("NUL character in request")

/** Hands parts through unchanged, refusing (400) the first whose name, filename or text value holds a NUL. */
private class NulRejectingMultiPart(private val parts: MultiPartData) : MultiPartData {
    override suspend fun readPart(): PartData? {
        val part = parts.readPart() ?: return null
        val nul = NUL in part.name.orEmpty() ||
            (part is PartData.FormItem && NUL in part.value) ||
            (part is PartData.FileItem && NUL in part.originalFileName.orEmpty())
        if (nul) {
            part.release()
            throw nulRejected()
        }
        return part
    }
}
