package com.wikikt.routing

import com.wikikt.appContext
import com.wikikt.siteId
import com.wikikt.auth.CSRF_FIELD
import com.wikikt.auth.isCsrfValid
import com.wikikt.model.AssetRecord
import com.wikikt.model.AssetRef
import com.wikikt.model.normalizeAssetPath
import com.wikikt.model.slugFilename
import com.wikikt.model.toIsoString
import com.wikikt.model.validateWikiPath
import com.wikikt.service.ImageType
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.http.content.LocalFileContent
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.request.contentLength
import io.ktor.server.request.header
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.nio.file.Files
import java.nio.file.Path

/** Whether the caller has any write:assets grant (coarse gate for the manager; per-file paths are re-checked). */
private suspend fun ApplicationCall.canUploadAssetsHere(): Boolean =
    appContext.permissions.canUploadAssets(currentUserId())

/**
 * Loads the asset named by the `{id}` route param and enforces manage:assets against its OWN
 * site/locale/path. Responds (400/404/403) and returns null on any failure, so a caller on one site
 * can't view or mutate an asset belonging to another site by guessing its id.
 */
private suspend fun ApplicationCall.manageableAsset(): AssetRecord? {
    val id = parameters["id"]?.toUIntOrNull() ?: run {
        respond(HttpStatusCode.BadRequest)
        return null
    }
    val asset = appContext.assets.findById(id) ?: run {
        respond(HttpStatusCode.NotFound)
        return null
    }
    if (!appContext.permissions.check(currentUserId(), com.wikikt.service.AccessResolver.Perm.MANAGE_ASSETS, asset.siteId, asset.locale, asset.path)) {
        respondForbidden()
        return null
    }
    return asset
}

fun Route.configureAssetRouting() {
    // The file/asset manager. The manager itself needs write:assets on the current site; each
    // per-asset action is gated by manage:assets against that asset's OWN site/path — so an asset on
    // another site (reached by guessing its id) can't be viewed or mutated without a rule for it.
    route("/f") {
        get {
            if (!call.canUploadAssetsHere()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("assets/list.hbs", call.assetListModel(message = null)))
        }

        post {
            if (!call.canUploadAssetsHere()) {
                call.respondForbidden()
                return@post
            }
            call.handleAssetUpload()
        }

        get("/{id}") {
            val asset = call.manageableAsset() ?: return@get
            call.respond(MustacheContent("assets/detail.hbs", call.assetDetailModel(asset)))
        }

        post("/{id}/delete") {
            val asset = call.manageableAsset() ?: return@post
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            call.appContext.assets.delete(asset.id)
            call.respondRedirect("/f")
        }

        post("/{id}/meta") {
            val asset = call.manageableAsset() ?: return@post
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            call.appContext.assets.updateMeta(asset.id, params["altText"], params["description"])
            call.respondRedirect("/f/${asset.id}")
        }

        post("/{id}/replace") {
            val asset = call.manageableAsset() ?: return@post
            call.handleAssetReplace(asset)
        }

        post("/{id}/restore/{revId}") {
            val asset = call.manageableAsset() ?: return@post
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val revId = call.parameters["revId"]?.toUIntOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            call.appContext.assets.restore(asset.id, revId, call.currentUserId(), call.assetHistoryLimit())
            call.respondRedirect("/f/${asset.id}")
        }

        post("/{id}/pending/delete") {
            val asset = call.manageableAsset() ?: return@post
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            call.appContext.assets.discardPending(asset.id)
            call.respondRedirect("/f/${asset.id}")
        }
    }
}

/**
 * Serves the asset at (locale, path) if one exists, gated by the page view baseline. Returns true if
 * it handled the response (served or denied), false if there's no asset (caller falls through to 404).
 */
internal suspend fun ApplicationCall.serveAssetIfPresent(locale: String, path: String): Boolean {
    val ctx = appContext
    val siteId = siteId()
    val asset = ctx.assets.resolve(siteId, locale, path, ctx.config.assets.localeFallback, ctx.config.defaultLocale)
        ?: return false
    val userId = currentUserId()
    if (!ctx.permissions.check(userId, com.wikikt.service.AccessResolver.Perm.READ_ASSETS, siteId, asset.locale, asset.path)) {
        respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", errorModel("Access denied", 403)))
        return true
    }
    val file = ctx.assets.fileForId(asset.id).toFile()
    if (!file.exists()) {
        application.environment.log.warn("Asset ${asset.id} (${asset.locale}/${asset.path}) has no file on disk")
        respond(HttpStatusCode.NotFound, MustacheContent("error.hbs", errorModel("Not found", 404)))
        return true
    }
    // Revalidate on every request via an ETag derived from updatedAt, so a replaced file shows
    // immediately (changed ETag → fresh bytes) while unchanged files get a cheap 304.
    val etag = "\"${asset.id}-${asset.updatedAt}\""
    if (request.header(HttpHeaders.IfNoneMatch) == etag) {
        respond(HttpStatusCode.NotModified)
        return true
    }
    // X-Content-Type-Options: nosniff is set globally by the SecurityHeaders plugin.
    // Structured header: Ktor quotes/escapes the filename and rejects control chars, so a hostile
    // uploaded filename can't inject extra header content or break out of the parameter.
    response.headers.append(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, asset.originalFilename).toString(),
    )
    response.headers.append(HttpHeaders.ETag, etag)
    response.headers.append(HttpHeaders.CacheControl, "private, no-cache")
    respond(LocalFileContent(file, contentType = ContentType.parse(asset.mime)))
    return true
}

private suspend fun ApplicationCall.handleAssetUpload() {
    val ctx = appContext
    val siteId = siteId()
    val cfg = ctx.config.assets
    val contentLength = request.contentLength()
    if (contentLength != null && contentLength > cfg.maxFilesPerUpload.toLong() * cfg.maxUploadSizeBytes + 1_048_576L) {
        respond(HttpStatusCode.PayloadTooLarge, MustacheContent("error.hbs", errorModel("Upload too large", 413)))
        return
    }
    val ajax = request.header("X-Wk-Ajax") != null
    val userId = currentUserId()
    val fields = HashMap<String, String>()
    var csrfChecked = false
    var csrfFailed = false
    var fileCount = 0
    var ok = 0
    val errors = mutableListOf<String>()
    val conflicts = mutableListOf<String>()
    val temps = mutableListOf<Path>()

    try {
        receiveMultipart().forEachPart { part ->
            try {
                when (part) {
                    is PartData.FormItem -> part.name?.let { fields[it] = part.value }
                    is PartData.FileItem -> {
                        // CSRF is validated from the first form part before any bytes are streamed.
                        if (!csrfChecked) {
                            csrfChecked = true
                            csrfFailed = !isCsrfValid(fields[CSRF_FIELD])
                        }
                        if (csrfFailed) return@forEachPart
                        fileCount++
                        val original = part.originalFileName?.takeIf { it.isNotBlank() } ?: "file"
                        if (fileCount > cfg.maxFilesPerUpload) {
                            errors.add("$original: too many files (max ${cfg.maxFilesPerUpload})")
                            return@forEachPart
                        }
                        val temp = ctx.assets.newTempFile()
                        temps.add(temp)
                        val size = streamToTemp(part.provider(), temp, cfg.maxUploadSizeBytes)
                        if (size == null) {
                            errors.add("$original: exceeds ${cfg.maxUploadSizeBytes / 1024 / 1024} MB")
                            return@forEachPart
                        }
                        val mime = ImageType.detect(Files.newInputStream(temp).use { it.readNBytes(16) })
                        if (mime == null || mime !in cfg.allowedMimeTypes) {
                            errors.add("$original: unsupported file type")
                            return@forEachPart
                        }
                        val folder = fields["folder"]?.trim()?.trim('/').orEmpty()
                        val locale = fields["locale"]?.trim()?.ifBlank { null } ?: ctx.config.defaultLocale
                        val candidate = if (folder.isEmpty()) slugFilename(original) else "$folder/${slugFilename(original)}"
                        val path = try {
                            normalizeAssetPath(candidate).also { validateWikiPath(it, allowExtension = true) }
                        } catch (e: IllegalArgumentException) {
                            errors.add("$original: ${e.message}")
                            return@forEachPart
                        }
                        // Precise per-file check: the user must have write:assets for THIS site+path.
                        if (!ctx.permissions.check(userId, com.wikikt.service.AccessResolver.Perm.WRITE_ASSETS, siteId, locale, path)) {
                            errors.add("$original: not allowed at this path")
                            return@forEachPart
                        }
                        val existing = ctx.assets.findByLocaleAndPath(siteId, locale, path)
                        if (existing != null) {
                            // Same path: overwrite (archives the old as a revision) only when asked; otherwise
                            // report it as a conflict so the client can confirm before replacing.
                            if (fields["overwrite"]?.trim() == "1") {
                                ctx.assets.replace(existing.id, mime, size, original.take(500), temp, userId, assetHistoryLimit(siteId))
                                temps.remove(temp)
                                ok++
                            } else {
                                conflicts.add(path)
                            }
                            return@forEachPart
                        }
                        ctx.assets.create(siteId, locale, path, original.take(500), mime, size, temp, userId)
                        temps.remove(temp) // moved into place by create()
                        ok++
                    }
                    else -> {}
                }
            } finally {
                part.dispose()
            }
        }
    } finally {
        temps.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    if (csrfFailed) {
        if (ajax) {
            respond(HttpStatusCode.Forbidden, com.wikikt.model.UploadResultDto(0, emptyList(), listOf("Invalid or missing CSRF token")))
        } else {
            respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", errorModel("Invalid or missing CSRF token", 403)))
        }
        return
    }
    // The picker uploads via AJAX and re-fetches the asset list itself; it just needs the outcome
    // (especially which paths already exist, so it can offer to overwrite).
    if (ajax) {
        respond(com.wikikt.model.UploadResultDto(ok, conflicts, errors))
        return
    }
    val message = buildString {
        append("Uploaded $ok file(s).")
        if (conflicts.isNotEmpty()) append(" Already exist: ${conflicts.joinToString("; ")}")
        if (errors.isNotEmpty()) append(" Skipped: ${errors.joinToString("; ")}")
    }
    respond(MustacheContent("assets/list.hbs", assetListModel(message)))
}

/** Streams [channel] into [temp], returning the byte count, or null if it exceeds [maxBytes]. */
private suspend fun streamToTemp(channel: ByteReadChannel, temp: Path, maxBytes: Long): Long? {
    var size = 0L
    java.io.BufferedOutputStream(Files.newOutputStream(temp)).use { out ->
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

private suspend fun ApplicationCall.assetListModel(message: String?): Map<String, Any?> {
    val ctx = appContext
    val siteId = siteId()
    val counts = assetUsageCounts()
    // The manager gate (canUploadAssetsHere) only proves write:assets somewhere; filter per-asset so a
    // read:assets DENY on a subtree hides those assets' metadata from the list, not just their bytes.
    val assets = ctx.permissions.readableAssets(currentUserId(), siteId, ctx.assets.list(siteId)).map {
        com.wikikt.model.AssetAdminDto(
            id = it.id.toString(),
            locale = it.locale,
            path = it.path,
            url = wikiViewUrl(it.locale, it.path),
            mime = it.mime,
            sizeBytes = it.sizeBytes,
            createdAt = it.createdAt,
            usedBy = counts[AssetRef(it.locale, it.path)] ?: 0,
            description = it.description.orEmpty(),
        )
    }
    // Embedded for the client-side folder browser. Escape "</" so the JSON can't close the <script>.
    val assetsJson = kotlinx.serialization.json.Json.encodeToString(assets).replace("</", "<\\/")
    return adminBaseModel() + mapOf(
        // The asset manager is a standalone tool, reachable with only write:assets — a user may not have
        // admin access. Drop the admin-shell "Exit" affordance and instead let the header show the
        // Administration gear (→ /a) exactly when this user can actually reach it, like account pages.
        "adminArea" to false,
        "canAdmin" to ctx.permissions.canAccessAdmin(currentUserId()),
        "assetCount" to assets.size,
        "assetsJson" to assetsJson,
        "message" to message,
        "defaultLocale" to ctx.config.defaultLocale,
        "localeOptions" to localeSelectOptions(ctx.settings.enabledLocales(siteId, ctx.config.defaultLocale), ctx.config.defaultLocale),
        "maxFiles" to ctx.config.assets.maxFilesPerUpload,
        "maxMb" to ctx.config.assets.maxUploadSizeBytes / 1024 / 1024,
    )
}

private suspend fun ApplicationCall.assetDetailModel(asset: AssetRecord, message: String? = null): Map<String, Any?> {
    val ctx = appContext
    val formats = displayFormats()
    val url = wikiViewUrl(asset.locale, asset.path)
    val usages = assetUsages(asset)
    val pending = ctx.assets.pendingFor(asset.id)
    val revisions = ctx.assets.revisions(asset.id).map {
        mapOf(
            "id" to it.id.toString(),
            "assetId" to asset.id.toString(),
            "version" to it.versionNumber,
            "mime" to it.mime,
            "sizeKb" to (it.sizeBytes / 1024).coerceAtLeast(1),
            "filename" to it.originalFilename,
            "createdAt" to DateDisplay.format(it.createdAt, formats),
        )
    }
    return adminBaseModel() + mapOf(
        // Standalone tool, like the asset list: reachable with only manage:assets, so show the
        // Administration gear (→ /a) only when this user can reach it rather than the admin-shell "Exit".
        "adminArea" to false,
        "canAdmin" to ctx.permissions.canAccessAdmin(currentUserId()),
        "id" to asset.id.toString(),
        "locale" to asset.locale,
        "path" to asset.path,
        "url" to url,
        "markdown" to "![${asset.originalFilename}]($url)",
        "altText" to asset.altText.orEmpty(),
        "description" to asset.description.orEmpty(),
        "mime" to asset.mime,
        "sizeKb" to (asset.sizeBytes / 1024).coerceAtLeast(1),
        "originalFilename" to asset.originalFilename,
        "message" to message,
        "usages" to usages,
        "hasUsages" to usages.isNotEmpty(),
        "usageCount" to usages.size,
        "revisions" to revisions,
        "hasRevisions" to revisions.isNotEmpty(),
        "maxVersions" to ctx.config.assets.maxAssetVersions,
        "hasPending" to (pending != null),
        "pendingScheduledAt" to pending?.publishAt?.let { DateDisplay.format(it, formats) },
        "pendingFilename" to pending?.originalFilename,
    )
}

/**
 * The per-site cap on retained asset revisions: the UI-set `history.maxAssetRevisions` setting, falling
 * back to the `wikikt.assets.maxAssetVersions` config/env when unset. Defaults to [site] or this
 * request's site.
 */
private suspend fun ApplicationCall.assetHistoryLimit(site: UInt? = null): Int {
    val resolved = site ?: siteId()
    return appContext.settings.getHistoryLimit(
        resolved,
        com.wikikt.service.SettingsService.HISTORY_MAX_ASSET_REVISIONS,
        appContext.config.assets.maxAssetVersions,
    )
}

/** Reads a single replacement file from a multipart POST (CSRF-first), then replaces the asset in place. */
private suspend fun ApplicationCall.handleAssetReplace(asset: AssetRecord) {
    val ctx = appContext
    val cfg = ctx.config.assets
    val contentLength = request.contentLength()
    if (contentLength != null && contentLength > cfg.maxUploadSizeBytes + 1_048_576L) {
        respond(HttpStatusCode.PayloadTooLarge, MustacheContent("error.hbs", errorModel("Upload too large", 413)))
        return
    }
    val userId = currentUserId()
    val fields = HashMap<String, String>()
    var csrfChecked = false
    var csrfFailed = false
    var temp: java.nio.file.Path? = null
    var mime: String? = null
    var size = 0L
    var originalName = asset.originalFilename
    var error: String? = null

    try {
        receiveMultipart().forEachPart { part ->
            try {
                when (part) {
                    is PartData.FormItem -> part.name?.let { fields[it] = part.value }
                    is PartData.FileItem -> {
                        if (!csrfChecked) {
                            csrfChecked = true
                            csrfFailed = !isCsrfValid(fields[CSRF_FIELD])
                        }
                        if (csrfFailed || temp != null) return@forEachPart // first file only
                        val orig = part.originalFileName?.takeIf { it.isNotBlank() } ?: asset.originalFilename
                        val t = ctx.assets.newTempFile()
                        val s = streamToTemp(part.provider(), t, cfg.maxUploadSizeBytes)
                        if (s == null) {
                            error = "File exceeds ${cfg.maxUploadSizeBytes / 1024 / 1024} MB."
                            runCatching { Files.deleteIfExists(t) }
                            return@forEachPart
                        }
                        val detected = ImageType.detect(Files.newInputStream(t).use { it.readNBytes(16) })
                        if (detected == null || detected !in cfg.allowedMimeTypes) {
                            error = "Unsupported file type."
                            runCatching { Files.deleteIfExists(t) }
                            return@forEachPart
                        }
                        temp = t
                        mime = detected
                        size = s
                        originalName = orig
                    }
                    else -> {}
                }
            } finally {
                part.dispose()
            }
        }
    } finally {
        if ((csrfFailed || error != null) && temp != null) {
            runCatching { Files.deleteIfExists(temp!!) }
            temp = null
        }
    }

    if (csrfFailed) {
        respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", errorModel("Invalid or missing CSRF token", 403)))
        return
    }
    val ready = temp
    val detectedMime = mime
    if (ready == null || detectedMime == null) {
        respond(MustacheContent("assets/detail.hbs", assetDetailModel(asset, message = error ?: "No image provided.")))
        return
    }
    if ((fields["when"] ?: "now") == "scheduled") {
        val at = parsePublishAt(fields["scheduleAt"]?.trim().orEmpty(), displayZone())
        if (at == null) {
            runCatching { Files.deleteIfExists(ready) }
            respond(MustacheContent("assets/detail.hbs", assetDetailModel(asset, message = "Choose a valid schedule time.")))
            return
        }
        ctx.assets.schedule(asset.id, detectedMime, size, originalName.take(500), ready, at, userId)
    } else {
        ctx.assets.replace(asset.id, detectedMime, size, originalName.take(500), ready, userId, assetHistoryLimit(asset.siteId))
    }
    respondRedirect("/f/${asset.id}")
}

/** Pages and fragments that reference [asset] (by its locale+path). */
private suspend fun ApplicationCall.assetUsages(asset: AssetRecord): List<Map<String, Any?>> {
    val ctx = appContext
    val siteId = siteId()
    val target = AssetRef(asset.locale, asset.path)
    val out = mutableListOf<Map<String, Any?>>()
    for (page in ctx.pages.list(siteId)) {
        if (target in ctx.assets.referencedAssetPaths(page.content, ctx.config.defaultLocale)) {
            out.add(
                mapOf(
                    "type" to "page",
                    "label" to page.path,
                    "locale" to page.locale,
                    "url" to wikiViewUrl(page.locale, page.path),
                ),
            )
        }
    }
    for (fragment in ctx.fragments.list(siteId)) {
        if (target in ctx.assets.referencedAssetPaths(fragment.content, ctx.config.defaultLocale)) {
            out.add(
                mapOf(
                    "type" to "fragment",
                    "label" to fragment.key,
                    "locale" to fragment.locale,
                    "url" to "/a/fragments/${fragment.id}/edit",
                ),
            )
        }
    }
    return out
}

/** Reference counts per asset (locale+path) across all pages and fragments, for the list view. */
private suspend fun ApplicationCall.assetUsageCounts(): Map<AssetRef, Int> {
    val ctx = appContext
    val siteId = siteId()
    val counts = mutableMapOf<AssetRef, Int>()
    fun tally(content: String) {
        for (ref in ctx.assets.referencedAssetPaths(content, ctx.config.defaultLocale)) {
            counts[ref] = (counts[ref] ?: 0) + 1
        }
    }
    for (page in ctx.pages.list(siteId)) tally(page.content)
    for (fragment in ctx.fragments.list(siteId)) tally(fragment.content)
    return counts
}
