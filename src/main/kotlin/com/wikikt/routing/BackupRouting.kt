package com.wikikt.routing

import com.wikikt.appContext
import com.wikikt.siteId
import com.wikikt.adminSiteId
import com.wikikt.auth.CSRF_FIELD
import com.wikikt.auth.isCsrfValid
import com.wikikt.service.BackupService
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

fun Route.configureBackupRouting() {
    route("/a/backup") {
        // Backup now lives on the combined Storage and backup page.
        get {
            call.respondRedirect("/a/storage", permanent = true)
        }

        // Content backup carries no credentials, so a plain GET download is fine.
        get("/export") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            call.attachZipHeader("content")
            call.respondOutputStream(ContentType.Application.Zip) {
                call.appContext.backup.writeContentBackup(call.adminSiteId(), this)
            }
        }

        // Full backup is a POST so its optional secrets password isn't logged in a URL/query string.
        // Root only: a full export dumps every account's password hash, sessions, API keys, and the
        // instance secrets — far beyond what a delegated manage:groups admin should be able to exfiltrate.
        post("/export/full") {
            if (!call.requireRoot()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.isCsrfValid(params[CSRF_FIELD])) {
                call.respond(HttpStatusCode.Forbidden, "Invalid CSRF token")
                return@post
            }
            val secretsPassword = params["secretsPassword"]?.ifBlank { null }
            call.attachZipHeader("full")
            call.respondOutputStream(ContentType.Application.Zip) {
                call.appContext.backup.writeFullBackup(call.adminSiteId(), this, secretsPassword)
            }
        }

        post("/restore") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val ctx = call.appContext
            var csrfOk = false
            var tooLarge = false
            var uploaded: Path? = null
            val fields = HashMap<String, String>()
            try {
                call.receiveMultipart().forEachPart { part ->
                    try {
                        when (part) {
                            is PartData.FormItem -> part.name?.let { fields[it] = part.value }
                            is PartData.FileItem -> {
                                // CSRF is validated from the form fields seen so far, before any bytes land.
                                csrfOk = call.isCsrfValid(fields[CSRF_FIELD])
                                if (!csrfOk || uploaded != null) return@forEachPart
                                val temp = Files.createTempFile("wikikt-backup-upload", ".zip")
                                val size = streamToFile(part.provider(), temp, BackupService.MAX_RESTORE_UPLOAD_BYTES)
                                if (size == null) {
                                    tooLarge = true
                                    Files.deleteIfExists(temp)
                                } else {
                                    uploaded = temp
                                }
                            }
                            else -> {}
                        }
                    } finally {
                        part.dispose()
                    }
                }
                when {
                    !csrfOk -> call.respond(HttpStatusCode.Forbidden, "Invalid CSRF token")
                    tooLarge -> call.respond(
                        MustacheContent("admin/storage.hbs", call.storageModel(error = "The uploaded archive is too large.")),
                    )
                    uploaded == null -> call.respond(
                        MustacheContent("admin/storage.hbs", call.storageModel(error = "Choose a backup file to restore.")),
                    )
                    else -> {
                        // A full restore replaces every account/group/permission from the uploaded archive,
                        // so a manage:groups admin could import a crafted backup that makes them root. Gate
                        // the full path on root; a non-root's full upload is rejected by restore() below.
                        val allowFull = fields["confirmFull"] == "1" && call.requireRoot()
                        val secretsPassword = fields["secretsPassword"]?.ifBlank { null }
                        val result = runCatching { ctx.backup.restore(call.adminSiteId(), uploaded!!, allowFull, secretsPassword) }
                        result.fold(
                            onSuccess = {
                                call.respond(MustacheContent("admin/storage.hbs", call.storageModel(restored = it)))
                            },
                            onFailure = { e ->
                                call.application.environment.log.warn("Backup restore failed", e)
                                call.respond(
                                    MustacheContent(
                                        "admin/storage.hbs",
                                        call.storageModel(error = e.message ?: "Restore failed."),
                                    ),
                                )
                            },
                        )
                    }
                }
            } finally {
                uploaded?.let { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }
}

/** Sets the download filename header for a `<scope>` backup ZIP (dated for the operator's convenience). */
private fun ApplicationCall.attachZipHeader(scope: String) {
    val filename = "wikikt-$scope-backup-${LocalDate.now(ZoneId.systemDefault())}.zip"
    response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, filename).toString(),
    )
}

/** Streams [channel] into [file], returning the byte count, or null if it exceeds [maxBytes]. */
private suspend fun streamToFile(channel: ByteReadChannel, file: Path, maxBytes: Long): Long? {
    var size = 0L
    java.io.BufferedOutputStream(Files.newOutputStream(file)).use { out ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            if (read > 0) {
                size += read
                if (size > maxBytes) return null
                out.write(buffer, 0, read)
            }
        }
    }
    return size
}
