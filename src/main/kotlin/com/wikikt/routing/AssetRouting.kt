package com.wikikt.routing

import com.wikikt.adminSiteId
import com.wikikt.appContext
import com.wikikt.currentSite
import com.wikikt.siteId
import com.wikikt.auth.CSRF_FIELD
import com.wikikt.auth.isCsrfValid
import com.wikikt.db.ContentFormat
import com.wikikt.model.AssetRecord
import com.wikikt.model.AssetRef
import com.wikikt.model.normalizeAssetPath
import com.wikikt.model.slugFilename
import com.wikikt.model.toIsoString
import com.wikikt.model.validateWikiPath
import com.wikikt.service.AssetService
import com.wikikt.service.ImageType
import com.wikikt.service.MetadataStripper
import com.wikikt.service.SettingsService
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.encodeURLParameter
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
 * Shell adjustments for the /f pages, which render inside the admin console shell so the manager is
 * reachable from the console's Content section rather than only by URL.
 *
 * The shell is unconditional: opening /f takes a write:assets ALLOW rule, and `canAccessAdmin` counts
 * exactly that as admin-area access — so anyone who gets this far can reach the console regardless. The
 * sidebar gates each item on its own, so an asset-only editor still sees just Content > Assets.
 */
private suspend fun ApplicationCall.assetShellModel(): Map<String, Any?> {
    val site = currentSite()
    return mapOf(
        // No site switcher here. Unlike the /a pages, /f works on the site serving the request (the
        // editor's picker uploads through this same route while editing a page on that host), so the
        // switcher would claim to govern a list it has no say over. When the two disagree, the list
        // says which site's assets it is actually showing instead.
        "showSiteSwitcher" to false,
        "otherSiteManaged" to (adminSiteId() != site.id),
        "assetSiteName" to site.name,
    )
}

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

        // Cleanup aid (linked from the manager's Tools section): every asset, in every folder, that no
        // page or fragment links and that isn't the site logo/favicon. A constant path segment, so Ktor
        // matches it ahead of the "/{id}" detail route below.
        get("/unused") {
            if (!call.canUploadAssetsHere()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("assets/unused.hbs", call.unusedAssetsModel()))
        }

        // The mirror image of /unused: references to assets that aren't there. Also a constant segment,
        // so it matches ahead of "/{id}".
        get("/broken") {
            if (!call.canUploadAssetsHere()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("assets/broken.hbs", call.brokenAssetRefsModel()))
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

        // Move/rename: changes the asset's (locale, path) identity — a metadata-only update, since
        // bytes are stored by id. References in content are NOT rewritten; the form warns about them
        // and the broken-references report lists each one after the move.
        post("/{id}/rename") {
            val asset = call.manageableAsset() ?: return@post
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val ctx = call.appContext
            suspend fun fail(message: String) =
                call.respond(MustacheContent("assets/detail.hbs", call.assetDetailModel(asset, message)))
            val locale = com.wikikt.model.normalizeLocale(params["locale"]?.trim().orEmpty()) ?: run {
                fail("Choose a valid locale.")
                return@post
            }
            // Same normalization + naming rules as the upload path, so a move can't mint a path an
            // upload couldn't have created.
            val newPath = try {
                normalizeAssetPath(params["path"]?.trim().orEmpty()).also { validateWikiPath(it, allowExtension = true) }
            } catch (e: IllegalArgumentException) {
                fail(e.message ?: "Invalid path.")
                return@post
            }
            // Moving is creating the asset at the destination: the same per-path write:assets check
            // the upload runs, so an asset can't be moved into a subtree the user couldn't upload to.
            if (!ctx.permissions.check(call.currentUserId(), com.wikikt.service.AccessResolver.Perm.WRITE_ASSETS, asset.siteId, locale, newPath)) {
                call.respondForbidden()
                return@post
            }
            if (!ctx.assets.rename(asset.id, locale, newPath)) {
                fail("An asset already exists at $locale/$newPath.")
                return@post
            }
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

        // Serves an archived revision's bytes inline — opened by the History tab's "View" (new tab).
        // Gated by manage:assets on the asset's own site/path (via manageableAsset), like every /f action.
        get("/{id}/revision/{revId}") {
            val asset = call.manageableAsset() ?: return@get
            val revId = call.parameters["revId"]?.toUIntOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            // Confirm the revision belongs to THIS asset before serving its bytes (revId is path-scoped).
            val rev = call.appContext.assets.revisions(asset.id).find { it.id == revId } ?: run {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val file = call.appContext.assets.revFileForId(revId).toFile()
            if (!file.exists()) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            call.response.headers.append(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, rev.originalFilename).toString(),
            )
            call.response.headers.append(HttpHeaders.CacheControl, "private, no-cache")
            call.respond(LocalFileContent(file, contentType = ContentType.parse(rev.mime)))
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
    // Short private freshness window + ETag revalidation. Within max-age the browser reuses the
    // bytes with no request at all — sparing the resolve + permission check + 304 round-trip that
    // image-heavy pages otherwise repeat per image on every view. After it, the ETag (derived from
    // updatedAt) revalidates, so a replaced file propagates within the window — or immediately on a
    // hard reload. `private` because assets are permission-gated; shared caches must not hold them.
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
    response.headers.append(HttpHeaders.CacheControl, "private, max-age=300")
    respond(LocalFileContent(file, contentType = ContentType.parse(asset.mime)))
    return true
}

private suspend fun ApplicationCall.handleAssetUpload() {
    val ctx = appContext
    val siteId = siteId()
    val cfg = ctx.config.assets
    // Per-site cap (admin-set, falling back to config); governs both this form and the editor's picker upload.
    val maxFiles = ctx.settings.uploadFileLimit(siteId, cfg.maxFilesPerUpload)
    val stripMeta = ctx.settings.getBool(siteId, SettingsService.ASSETS_STRIP_METADATA, SettingsService.DEFAULT_STRIP_METADATA)
    val contentLength = request.contentLength()
    if (contentLength != null && contentLength > maxFiles.toLong() * cfg.maxUploadSizeBytes + 1_048_576L) {
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
                        if (fileCount > maxFiles) {
                            errors.add("$original: too many files (max $maxFiles)")
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
                        // Strip EXIF/XMP/IPTC metadata before the bytes are stored (privacy). No-op when disabled.
                        val finalSize = stripMetadataIfEnabled(temp, mime, stripMeta)
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
                                ctx.assets.replace(existing.id, mime, finalSize, original.take(500), temp, userId, assetHistoryLimit(siteId))
                                temps.remove(temp)
                                ok++
                            } else {
                                conflicts.add(path)
                            }
                            return@forEachPart
                        }
                        ctx.assets.create(siteId, locale, path, original.take(500), mime, finalSize, temp, userId)
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

/**
 * Strips privacy metadata (EXIF/XMP/IPTC) from the just-written [temp] image in place when [enabled],
 * returning the resulting file size. A no-op -- and no rewrite -- when disabled or when the format
 * carries nothing to remove. Runs on already-validated bytes bounded by the upload size limit.
 */
private fun stripMetadataIfEnabled(temp: Path, mime: String, enabled: Boolean): Long {
    if (enabled) {
        val original = Files.readAllBytes(temp)
        val stripped = MetadataStripper.strip(original, mime)
        if (!stripped.contentEquals(original)) {
            Files.write(temp, stripped)
            return stripped.size.toLong()
        }
    }
    return Files.size(temp)
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
    val usages = assetUsageIndex()
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
            updatedAt = it.updatedAt,
            usedBy = usages[AssetRef(it.locale, it.path)]?.size ?: 0,
            description = it.description.orEmpty(),
        )
    }
    // Embedded for the client-side folder browser. Escape "</" so the JSON can't close the <script>.
    val assetsJson = kotlinx.serialization.json.Json.encodeToString(assets).replace("</", "<\\/")
    return adminBaseModel() + assetShellModel() + mapOf(
        "assetCount" to assets.size,
        "assetsJson" to assetsJson,
        "message" to message,
        "defaultLocale" to ctx.config.defaultLocale,
        "localeOptions" to localeSelectOptions(ctx.settings.enabledLocales(siteId, ctx.config.defaultLocale), ctx.config.defaultLocale),
        "maxFiles" to ctx.settings.uploadFileLimit(siteId, ctx.config.assets.maxFilesPerUpload),
        "maxMb" to ctx.config.assets.maxUploadSizeBytes / 1024 / 1024,
    )
}

private suspend fun ApplicationCall.assetDetailModel(asset: AssetRecord, message: String? = null): Map<String, Any?> {
    val ctx = appContext
    val formats = displayFormats()
    val url = wikiViewUrl(asset.locale, asset.path)
    val usages = assetUsageIndex().usagesFor(asset)
    val brandingUses = brandingUsageByRef()[AssetRef(asset.locale, asset.path)].orEmpty()
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
    return adminBaseModel() + assetShellModel() + mapOf(
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
        // First upload vs. last time the file bytes changed (upload/replace/restore) — shown on the File tab.
        "uploadedAt" to DateDisplay.format(asset.createdAt, formats),
        "modifiedAt" to DateDisplay.format(asset.updatedAt, formats),
        "message" to message,
        "usages" to usages,
        "hasUsages" to usages.isNotEmpty(),
        "usageCount" to usages.size,
        // Branding (logo/favicon) uses of this asset, shown alongside the page/fragment list so an
        // asset that appears "used" but is referenced by no page is explained.
        "brandingUses" to brandingUses,
        "hasBrandingUses" to brandingUses.isNotEmpty(),
        // Anything at all pointing at the current path — gates the Move form's stale-reference warning.
        "hasReferences" to (usages.isNotEmpty() || brandingUses.isNotEmpty()),
        // For the Move form's locale select; a locale no longer enabled is kept so a move can't
        // silently change it (same rule as the page editor's select).
        "localeOptions" to localeSelectOptions(ctx.settings.enabledLocales(asset.siteId, ctx.config.defaultLocale), asset.locale),
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
    val stripMeta = ctx.settings.getBool(asset.siteId, SettingsService.ASSETS_STRIP_METADATA, SettingsService.DEFAULT_STRIP_METADATA)
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
                        size = stripMetadataIfEnabled(t, detected, stripMeta) // strip EXIF/XMP/IPTC before storing
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

/** One place an asset is referenced from. [locale] is blank for site-wide sources (settings, nav). */
private data class AssetUsage(val type: String, val label: String, val locale: String, val url: String)

/**
 * What one scanned source contributes: the asset references it makes, plus any directory-relative URLs
 * that source can't resolve (empty for page bodies, which resolve them against the page path).
 */
private data class AssetScan(
    val refs: Set<AssetService.ContentRef>,
    val unresolvable: Set<String>,
    val usage: AssetUsage,
)

/**
 * Every asset reference on this site, each paired with the place that makes it. Shared by
 * [assetUsageIndex] and [brokenAssetRefsModel] so "unused" and "broken" can never drift apart about
 * which surfaces get scanned.
 *
 * Covers the surfaces WikiKT itself renders content from:
 *  - page bodies **and infobox values** (infobox fields are Markdown-rendered, so they can embed images)
 *  - fragments
 *  - staged (not-yet-published) versions — without these an asset looks unused right up until the
 *    scheduled publish goes out with a broken image
 *  - the Markdown footer override
 *
 * Navigation targets are bare URLs rather than Markdown, so they come from [navAssetTargets] instead.
 *
 * Each source resolves its own URLs, because whether a *directory-relative* URL has a target at all
 * depends on where it is written: only a page body gets `resolveRelativeLinks` applied at render time.
 * That's why this returns resolved references rather than raw text — the base can't be reconstructed
 * afterwards.
 *
 * Deliberately NOT covered, and called out on the /f/unused page: page revision history (counting it
 * would keep an asset "used" until history rotates out, defeating the point of the tool) and the
 * admin escape hatches — custom CSS and injected head/body HTML.
 */
private suspend fun ApplicationCall.assetScanSources(): List<AssetScan> {
    val ctx = appContext
    val siteId = siteId()
    val default = ctx.config.defaultLocale
    val out = mutableListOf<AssetScan>()

    // Content whose render does NOT resolve directory-relative URLs. Only absolute refs are real here;
    // the relative ones are collected separately so the report can name them instead of silently
    // dropping them — they resolve to a different file per page, or to nothing at all.
    fun addAbsolute(content: String?, usage: AssetUsage) {
        if (content.isNullOrBlank()) return
        out.add(
            AssetScan(
                ctx.assets.referencedLocalUrls(content, default),
                ctx.assets.unresolvableRelativeRefs(content),
                usage,
            ),
        )
    }

    // A page body: PageRenderService.resolveRelativeLinks rewrites relative URLs against the page path,
    // so they're resolved here the same way — for HTML-format pages too, whose content is scanned as
    // raw HTML rather than parsed as Markdown. Fragments (Markdown only, matching renderBody) are
    // expanded into the body before that pass runs, so a relative URL inside a fragment resolves
    // against the *including* page too — picked up by rescanning the expanded text and keeping only
    // what needed the base (an absolute URL resolves identically either way and cancels out, so it
    // stays attributed to the fragment, not to the page).
    suspend fun addPageBody(content: String?, locale: String, path: String, format: ContentFormat, usage: AssetUsage) {
        if (content.isNullOrBlank()) return
        val html = format == ContentFormat.HTML
        val refs = ctx.assets.referencedLocalUrls(content, default, path, locale, html).toMutableSet()
        if (!html && content.contains(FRAGMENT_REFERENCE_PREFIX)) {
            val expanded = ctx.fragments.expand(siteId, content, locale, default)
            refs += ctx.assets.referencedLocalUrls(expanded, default, path, locale) -
                ctx.assets.referencedLocalUrls(expanded, default)
        }
        out.add(AssetScan(refs, emptySet(), usage))
    }

    val pages = ctx.pages.list(siteId)
    for (page in pages) {
        val usage = AssetUsage("page", page.path, page.locale, wikiViewUrl(page.locale, page.path))
        addPageBody(page.content, page.locale, page.path, page.contentFormat, usage)
        // Infobox values are rendered by InfoboxService.renderCard, which never runs the relative pass —
        // a relative URL there is left for the browser to resolve, so it has no target we can check.
        addAbsolute(page.infobox, usage)
    }
    for (fragment in ctx.fragments.list(siteId)) {
        addAbsolute(fragment.content, AssetUsage("fragment", fragment.key, fragment.locale, "/a/fragments/${fragment.id}/edit"))
    }
    val pagesById = pages.associateBy { it.id }
    for (staged in ctx.pages.listStaged(pages.map { it.id })) {
        val page = pagesById[staged.pageId] ?: continue
        val usage = AssetUsage("scheduled draft", page.path, page.locale, wikiViewUrl(page.locale, page.path))
        addPageBody(staged.content, page.locale, page.path, staged.contentFormat, usage)
        addAbsolute(staged.infobox, usage)
    }
    // The footer renders on every page, so it has no one page to resolve a relative URL against.
    addAbsolute(
        ctx.settings.get(siteId, com.wikikt.service.SettingsService.SITE_FOOTER_OVERRIDE),
        AssetUsage("setting", "Footer override", "", "/a/settings"),
    )
    return out
}

/** Cheap pre-check mirroring FragmentService's own early-out, so pages without fragments skip expansion. */
private const val FRAGMENT_REFERENCE_PREFIX = "{{fragment:"

/**
 * Navigation item targets, as raw URLs paired with their source. Item *icons* are MDI class names,
 * never asset URLs, so they can't reference an asset and are skipped.
 */
private suspend fun ApplicationCall.navAssetTargets(): List<Pair<String, AssetUsage>> {
    val ctx = appContext
    val out = mutableListOf<Pair<String, AssetUsage>>()
    for (menu in ctx.nav.listMenus(siteId())) {
        for (item in ctx.nav.items(menu.id)) {
            val target = item.target?.takeIf { it.isNotBlank() } ?: continue
            out.add(target to AssetUsage("navigation", item.label, "", "/a/navigation"))
        }
    }
    return out
}

/** Lowercased hostname → site id for every site that has one. Empty when no site is addressable. */
private suspend fun ApplicationCall.siteHostMap(): Map<String, UInt> =
    appContext.sites.all().mapNotNull { s -> s.hostname?.let { it.lowercase() to s.id } }.toMap()

/**
 * Every absolute-URL reference made by [source]'s content that targets a site in [hostToSite] — the
 * same surfaces [assetScanSources] walks (page bodies + infobox values, scheduled drafts, fragments,
 * the footer override, and navigation targets), each paired with the usage row naming its source.
 * Unlike the local scan there is no relative-URL resolution (a directory-relative URL can never leave
 * its site) and no fragment expansion (a fragment's own references stay attributed to the fragment).
 *
 * With [qualify] set (scanning a site other than the one being reported on), usage rows are
 * site-labeled — "page on <site>" — and their links protocol-relative to the source's own hostname,
 * or blank when the source site has none to build one from (the template then renders plain text).
 */
private suspend fun ApplicationCall.crossSiteRefsFrom(
    source: com.wikikt.model.SiteRecord,
    hostToSite: Map<String, UInt>,
    qualify: Boolean,
): List<Pair<AssetService.CrossSiteRef, AssetUsage>> {
    val ctx = appContext
    val default = ctx.config.defaultLocale
    val out = mutableListOf<Pair<AssetService.CrossSiteRef, AssetUsage>>()
    fun usage(type: String, label: String, locale: String, localUrl: String): AssetUsage = if (!qualify) {
        AssetUsage(type, label, locale, localUrl)
    } else {
        AssetUsage("$type on ${source.name}", label, locale, source.hostname?.let { "//$it$localUrl" }.orEmpty())
    }
    fun scanText(content: String?, usage: AssetUsage, html: Boolean = false) {
        if (content.isNullOrBlank()) return
        for (cr in ctx.assets.referencedCrossSiteUrls(content, default, hostToSite, html)) out.add(cr to usage)
    }
    val pages = ctx.pages.list(source.id)
    for (page in pages) {
        val u = usage("page", page.path, page.locale, wikiViewUrl(page.locale, page.path))
        scanText(page.content, u, page.contentFormat == ContentFormat.HTML)
        scanText(page.infobox, u)
    }
    val pagesById = pages.associateBy { it.id }
    for (staged in ctx.pages.listStaged(pages.map { it.id })) {
        val page = pagesById[staged.pageId] ?: continue
        val u = usage("scheduled draft", page.path, page.locale, wikiViewUrl(page.locale, page.path))
        scanText(staged.content, u, staged.contentFormat == ContentFormat.HTML)
        scanText(staged.infobox, u)
    }
    for (fragment in ctx.fragments.list(source.id)) {
        scanText(fragment.content, usage("fragment", fragment.key, fragment.locale, "/a/fragments/${fragment.id}/edit"))
    }
    scanText(
        ctx.settings.get(source.id, SettingsService.SITE_FOOTER_OVERRIDE),
        usage("setting", "Footer override", "", "/a/settings"),
    )
    for (menu in ctx.nav.listMenus(source.id)) {
        for (item in ctx.nav.items(menu.id)) {
            val target = item.target?.takeIf { it.isNotBlank() } ?: continue
            ctx.assets.crossSiteRef(target, AssetService.RefKind.LINK, default, hostToSite)
                ?.let { out.add(it to usage("navigation", item.label, "", "/a/navigation")) }
        }
    }
    return out
}

/**
 * Every reference to every asset on this site, keyed by the asset's (locale, path). One index feeds
 * the manager's "Used by" count, the detail page's usage list, and `/f/unused`, so those three can't
 * disagree about what "used" means. See [assetScanSources] for what is and isn't scanned.
 *
 * A reference is tallied against every asset serve-time resolution could pick for it — mirroring
 * `resolves` in [brokenAssetRefsModel], so the two reports agree by construction:
 *  - the locale it binds to at serve time (see [AssetService.ContentRef.effectiveRef]);
 *  - the default locale too when `assets.localeFallback` is on, since [AssetService.resolve] falls
 *    back to it — without this, a `/de/x.png` reference served by the `en` fallback shows nothing on
 *    /f/broken while `en/x.png` sits on /f/unused as safe to delete;
 *  - for a locale-relative embed outside any page (a fragment, the footer override), every locale the
 *    site renders in (enabled locales plus any locale existing content still uses): such content
 *    renders into pages of any locale, and the render-time asset pass (`renderAssetRefs`) binds the
 *    embed to each rendering page's locale in turn.
 */
private suspend fun ApplicationCall.assetUsageIndex(): Map<AssetRef, List<AssetUsage>> {
    val ctx = appContext
    val default = ctx.config.defaultLocale
    val fallback = ctx.config.assets.localeFallback
    val enabledLocales = ctx.settings.enabledLocales(siteId(), default)
    val out = mutableMapOf<AssetRef, MutableList<AssetUsage>>()

    // A single source can mention the same asset more than once (body *and* infobox), so it's only
    // counted once per source.
    fun tally(ref: AssetRef, usage: AssetUsage) {
        val list = out.getOrPut(ref) { mutableListOf() }
        if (list.none { it.type == usage.type && it.label == usage.label && it.locale == usage.locale }) {
            list.add(usage)
        }
    }

    val scans = assetScanSources()
    // Locales content actually renders in: the enabled list plus any locale existing content uses —
    // a page on a since-removed locale still renders, and still binds render-anywhere embeds to its
    // locale, so that locale must stay in the render-anywhere tally.
    val renderLocales = (enabledLocales + scans.mapNotNull { it.usage.locale.ifBlank { null } }).distinct()

    fun tallyServable(ref: AssetService.ContentRef, usage: AssetUsage) {
        val locales = when {
            // Explicit locale in the URL, or a link (never locale-rewritten): serves as parsed.
            ref.explicitLocale || ref.kind == AssetService.RefKind.LINK -> listOf(ref.ref.locale)
            // A page-scoped embed binds to its page's locale (matching ContentRef.effectiveRef). These type
            // strings are the ones assetScanSources creates for page-resolved content.
            usage.type == "page" || usage.type == "scheduled draft" -> listOf(usage.locale)
            // A render-anywhere embed (fragment, footer override) binds per rendering page.
            else -> renderLocales
        }
        for (locale in locales) tally(AssetRef(locale, ref.ref.path), usage)
        if (fallback) tally(AssetRef(default, ref.ref.path), usage)
    }

    for (scan in scans) {
        for (ref in scan.refs) tallyServable(ref, scan.usage)
    }
    for ((target, usage) in navAssetTargets()) {
        ctx.assets.resolveLocalAssetUrl(target, default)?.let {
            tally(it, usage)
            if (fallback) tally(AssetRef(default, it.path), usage)
        }
    }
    // Absolute-URL references to this site's hostname, wherever on the instance they are written —
    // the other sites' content AND this site's own (the local scan drops every scheme'd URL, so a page
    // absolutely referencing its own host is only ever seen here). Nothing can absolutely reference a
    // site that has no hostname, so the instance walk is skipped entirely then. Absolute URLs are
    // never locale-rewritten at render, so the parsed ref is the serve-time binding; the fallback
    // tally mirrors serve-time resolution like every other reference kind here.
    val site = currentSite()
    if (site.hostname != null) {
        val hostToSite = siteHostMap()
        for (s in ctx.sites.all()) {
            for ((cr, usage) in crossSiteRefsFrom(s, hostToSite, qualify = s.id != site.id)) {
                if (cr.siteId != site.id) continue
                tally(cr.ref.ref, usage)
                if (fallback) tally(AssetRef(default, cr.ref.ref.path), usage)
            }
        }
    }
    return out
}

/** The places referencing [asset], shaped for the detail template. */
private fun Map<AssetRef, List<AssetUsage>>.usagesFor(asset: AssetRecord): List<Map<String, Any?>> =
    this[AssetRef(asset.locale, asset.path)].orEmpty().map {
        mapOf(
            "type" to it.type,
            "label" to it.label,
            "locale" to it.locale,
            "hasLocale" to it.locale.isNotBlank(),
            "url" to it.url,
            // Blank for a cross-site source on a site with no hostname (no URL can reach it).
            "hasUrl" to it.url.isNotBlank(),
        )
    }

/**
 * Model for `/f/unused`: every asset on this site, across all folders and locales, that nothing in
 * [assetUsageIndex] references and that isn't the site logo or favicon. Reuses the same index as the
 * manager's "Used by" column, so "unused here" means exactly "Used by 0, and not branding".
 *
 * See [assetUsageIndex] for what is and isn't scanned; the deliberate omissions (revision history,
 * custom CSS, injected HTML) are why the page carries a "check before deleting" note.
 */
private suspend fun ApplicationCall.unusedAssetsModel(): Map<String, Any?> {
    val ctx = appContext
    val siteId = siteId()
    val formats = displayFormats()
    val usages = assetUsageIndex()
    val branding = brandingUsageByRef()
    // Same per-asset read filter as the list: a read:assets DENY on a subtree hides those assets here too.
    val unused = ctx.permissions.readableAssets(currentUserId(), siteId, ctx.assets.list(siteId))
        .filter { asset ->
            val ref = AssetRef(asset.locale, asset.path)
            usages[ref].isNullOrEmpty() && !branding.containsKey(ref)
        }
        .map {
            mapOf(
                "id" to it.id.toString(),
                "locale" to it.locale,
                "path" to it.path,
                "url" to wikiViewUrl(it.locale, it.path),
                "isImage" to it.mime.startsWith("image/"),
                "mime" to it.mime,
                "sizeKb" to (it.sizeBytes / 1024).coerceAtLeast(1),
                "uploadedAt" to DateDisplay.format(it.createdAt, formats),
                "modifiedAt" to DateDisplay.format(it.updatedAt, formats),
                // Raw values behind the formatted cells, for click-to-sort: the displayed text sorts
                // wrong ("10 KB" < "2 KB" as a string, and a localized date isn't lexicographic).
                "sizeBytes" to it.sizeBytes,
                "createdAtMillis" to it.createdAt,
                "updatedAtMillis" to it.updatedAt,
            )
        }
    return adminBaseModel() + assetShellModel() + mapOf(
        "unused" to unused,
        "hasUnused" to unused.isNotEmpty(),
        "unusedCount" to unused.size,
    )
}

/**
 * Assets (by locale+path) currently selected as the site logo and/or favicon, each mapped to the
 * label(s) describing how it's used ("Site logo", "Favicon"). Such an asset counts as "used" even
 * when no page or fragment links it, so it isn't flagged as unused (and its detail page can say why).
 * The stored setting is a canonical asset URL (blank when it's the bundled default), resolved back to
 * an [AssetRef] the same way page/fragment references are.
 */
private suspend fun ApplicationCall.brandingUsageByRef(): Map<AssetRef, List<String>> {
    val ctx = appContext
    val siteId = siteId()
    val default = ctx.config.defaultLocale
    val fallback = ctx.config.assets.localeFallback
    val out = mutableMapOf<AssetRef, MutableList<String>>()
    suspend fun add(key: String, label: String) {
        val ref = ctx.settings.get(siteId, key)?.ifBlank { null }
            ?.let { ctx.assets.resolveLocalAssetUrl(it, default) } ?: return
        out.getOrPut(ref) { mutableListOf() }.add(label)
        // The logo/favicon URL serves through the same locale fallback as any asset request, so when
        // the stored URL names another locale the default-locale asset backs it (or may be what's
        // actually served) — it must count as branding-used too.
        if (fallback && ref.locale != default) out.getOrPut(AssetRef(default, ref.path)) { mutableListOf() }.add(label)
    }
    add(com.wikikt.service.SettingsService.SITE_LOGO_URL, "Site logo")
    add(com.wikikt.service.SettingsService.SITE_FAVICON_URL, "Favicon")
    return out
}

// Ref-classification helpers (looksLikeFile / mustBeAsset / effectiveRef) live on
// AssetService.ContentRef — shared with the page editor's save-time warning so the two can't drift.

// --- /f/broken list state: text/locale filter, server-side sorting, pagination. Same URL scheme
//     and template pattern as /a/pages, but cut from the in-memory scan result rather than SQL —
//     the scan has to walk everything anyway to find what's broken. ---

/** Rows-per-page choices offered by the broken-references list. */
private val BROKEN_LIST_SIZES = listOf(10, 25, 50, 100)

/** Rows per page when the URL doesn't ask for a (valid) size. */
private const val BROKEN_LIST_DEFAULT_SIZE = 25

/** How many numbered page links sit either side of the current one before the list elides. */
private const val BROKEN_LIST_WINDOW = 2

/** Sortable columns of the broken-references table; the key is what `?sort=` carries. */
private enum class BrokenSortColumn(val key: String) {
    PATH("path"),
    LOCALE("locale"),
    REFS("refs");

    companion object {
        fun fromKey(key: String?): BrokenSortColumn? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A `/f/broken` link carrying the full list state, so changing any one of filter, sort, page, or size
 * keeps the others. Defaults are left out to keep the common URL clean; the free-text filter and the
 * locale are the only values that need escaping.
 */
private fun brokenListUrl(q: String, locale: String, sort: BrokenSortColumn, descending: Boolean, page: Int, size: Int): String {
    val params = buildList {
        if (q.isNotEmpty()) add("q=${q.encodeURLParameter()}")
        if (locale.isNotEmpty()) add("locale=${locale.encodeURLParameter()}")
        if (sort != BrokenSortColumn.PATH || descending) add("sort=${sort.key}")
        if (descending) add("dir=desc")
        if (page > 1) add("page=$page")
        if (size != BROKEN_LIST_DEFAULT_SIZE) add("size=$size")
    }
    return if (params.isEmpty()) "/f/broken" else "/f/broken?" + params.joinToString("&")
}

/**
 * Numbered pager links: the first and last page always, plus [BROKEN_LIST_WINDOW] either side of the
 * current one, with `…` gaps standing in for what's left out.
 */
private fun brokenListLinks(
    q: String,
    locale: String,
    sort: BrokenSortColumn,
    descending: Boolean,
    current: Int,
    totalPages: Int,
    size: Int,
): List<Map<String, Any?>> {
    val shown = (1..totalPages).filter {
        it == 1 || it == totalPages || (it >= current - BROKEN_LIST_WINDOW && it <= current + BROKEN_LIST_WINDOW)
    }
    val links = mutableListOf<Map<String, Any?>>()
    var previous = 0
    for (page in shown) {
        if (page - previous > 1) links += mapOf("gap" to true)
        links += mapOf(
            "gap" to false,
            "label" to page.toString(),
            "url" to brokenListUrl(q, locale, sort, descending, page, size),
            "current" to (page == current),
        )
        previous = page
    }
    return links
}

/**
 * Model for `/f/broken`: the inverse of `/f/unused` — references pointing at an asset that isn't there,
 * whether it was deleted, moved, renamed, or never uploaded. Scans the same surfaces as
 * [assetScanSources] (plus nav targets), so the two reports agree about what content exists.
 *
 * A reference counts as broken only when nothing could serve it: the (locale, path) it binds to at
 * serve time has no asset, and neither does the default locale when `assets.localeFallback` is on. That
 * mirrors [com.wikikt.service.AssetService.resolve], so the report can't flag an image a reader sees
 * perfectly well.
 *
 * The main table is filtered, sorted, and paginated by `?q=` (path substring) / `?locale=` / `?sort=` /
 * `?dir=` / `?page=` / `?size=`. Anything unrecognised falls back to the default (path, ascending,
 * first page, 25 rows) rather than erroring, since these come straight from user-editable URLs.
 */
private suspend fun ApplicationCall.brokenAssetRefsModel(): Map<String, Any?> {
    val ctx = appContext
    val siteId = siteId()
    val default = ctx.config.defaultLocale
    val fallback = ctx.config.assets.localeFallback
    val existing = ctx.assets.list(siteId).mapTo(mutableSetOf()) { AssetRef(it.locale, it.path) }

    // Same resolution order the asset route serves with — including the default-locale fallback, so a
    // /de/logo.png reference backed only by /en/logo.png isn't reported when fallback is enabled.
    fun resolves(ref: AssetRef): Boolean =
        ref in existing || (fallback && AssetRef(default, ref.path) in existing)

    val broken = mutableMapOf<AssetRef, MutableList<AssetUsage>>()
    fun record(ref: AssetRef, source: AssetUsage) {
        val list = broken.getOrPut(ref) { mutableListOf() }
        // One source referencing the same missing file twice (body *and* infobox) is still one place to fix.
        if (list.none { it.type == source.type && it.label == source.label && it.locale == source.locale }) {
            list.add(source)
        }
    }

    // Directory-relative refs written where nothing can resolve them, keyed by the URL as written.
    val unresolvable = mutableMapOf<String, MutableList<AssetUsage>>()

    for (scan in assetScanSources()) {
        for (ref in scan.refs) {
            if (!ref.mustBeAsset) continue
            val effective = ref.effectiveRef(scan.usage.locale)
            if (!resolves(effective)) record(effective, scan.usage)
        }
        for (url in scan.unresolvable) {
            unresolvable.getOrPut(url) { mutableListOf() }.let { list ->
                if (list.none { it.type == scan.usage.type && it.label == scan.usage.label && it.locale == scan.usage.locale }) {
                    list.add(scan.usage)
                }
            }
        }
    }
    // Nav targets are bare URLs: most point at pages, so only a file-looking one is an asset reference.
    // A relative one has no page to resolve against either, so it lands in the same warning list.
    for ((target, source) in navAssetTargets()) {
        val ref = ctx.assets.resolveLocalAssetUrl(target, default)
        if (ref == null) {
            ctx.assets.unresolvableRelativeRef(target, AssetService.RefKind.LINK)?.let { url ->
                unresolvable.getOrPut(url) { mutableListOf() }.add(source)
            }
            continue
        }
        if (AssetService.looksLikeFile(ref.path) && !resolves(ref)) record(ref, source)
    }

    // Absolute-URL references FROM this site's content to the instance's own sites. Own-host
    // references join the main table above: they serve through the same resolution as any local
    // reference, just written absolutely (and never locale-rewritten, so they bind as parsed).
    // References to a sibling site are existence-checked against that site with the CALLER's read
    // access — a target the caller can't read is listed as unchecked, never probed. Extension-less
    // links stay out entirely, exactly like local ones: a page not written yet is a normal wiki
    // state, not a broken file.
    val crossBroken = sortedMapOf<String, MutableList<AssetUsage>>()
    val crossUnchecked = sortedSetOf<String>()
    run {
        val hostToSite = siteHostMap()
        if (hostToSite.isEmpty()) return@run
        val hostById = ctx.sites.all().associate { it.id to it.hostname }
        val userId = currentUserId()
        for ((cr, usage) in crossSiteRefsFrom(currentSite(), hostToSite, qualify = false)) {
            if (!cr.ref.mustBeAsset) continue
            val ref = cr.ref.ref
            if (cr.siteId == siteId) {
                if (!resolves(ref)) record(ref, usage)
                continue
            }
            val host = hostById[cr.siteId] ?: continue
            val display = "//$host" + (if (ref.locale == default) "/${ref.path}" else "/${ref.locale}/${ref.path}")
            if (!ctx.permissions.check(userId, com.wikikt.service.AccessResolver.Perm.READ_ASSETS, cr.siteId, ref.locale, ref.path)) {
                crossUnchecked += display
            } else if (ctx.assets.resolve(cr.siteId, ref.locale, ref.path, fallback, default) == null) {
                crossBroken.getOrPut(display) { mutableListOf() }.let { list ->
                    if (list.none { it.type == usage.type && it.label == usage.label && it.locale == usage.locale }) {
                        list.add(usage)
                    }
                }
            }
        }
    }

    val q = request.queryParameters["q"]?.trim().orEmpty()
    val localeFilter = request.queryParameters["locale"]?.trim().orEmpty()
    val sort = BrokenSortColumn.fromKey(request.queryParameters["sort"]) ?: BrokenSortColumn.PATH
    val descending = request.queryParameters["dir"] == "desc"
    val size = request.queryParameters["size"]?.toIntOrNull()?.takeIf { it in BROKEN_LIST_SIZES }
        ?: BROKEN_LIST_DEFAULT_SIZE

    val all = broken.toList()
    // The dropdown offers the locales that actually have broken refs — there is no configured list of
    // site locales to draw from, and any other choice could only ever show an empty table.
    val localeOptions = all.map { it.first.locale }.distinct().sorted().map {
        mapOf("value" to it, "selected" to (it == localeFilter))
    }
    val filtered = all.filter { (ref, _) ->
        (localeFilter.isEmpty() || ref.locale == localeFilter) &&
            (q.isEmpty() || ref.path.contains(q, ignoreCase = true))
    }
    val comparator: Comparator<Pair<AssetRef, List<AssetUsage>>> = when (sort) {
        BrokenSortColumn.PATH -> compareBy({ it.first.path }, { it.first.locale })
        BrokenSortColumn.LOCALE -> compareBy({ it.first.locale }, { it.first.path })
        BrokenSortColumn.REFS -> compareBy({ it.second.size }, { it.first.path })
    }
    val sorted = filtered.sortedWith(if (descending) comparator.reversed() else comparator)

    val requestedPage = (request.queryParameters["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
    val totalPages = maxOf(1, (sorted.size + size - 1) / size)
    // A page beyond the end is clamped to the last one, so a stale link lands on real rows.
    val current = requestedPage.coerceAtMost(totalPages)

    val rows = sorted.drop((current - 1) * size).take(size)
        .map { (ref, sources) ->
            mapOf(
                "locale" to ref.locale,
                "path" to ref.path,
                "url" to wikiViewUrl(ref.locale, ref.path),
                // The upload path that would fix it, so the folder field can be prefilled from here.
                "folder" to ref.path.substringBeforeLast('/', ""),
                "refCount" to sources.size,
                "sources" to sources.sortedWith(compareBy({ it.type }, { it.label })).map {
                    mapOf(
                        "type" to it.type,
                        "label" to it.label,
                        "locale" to it.locale,
                        "hasLocale" to it.locale.isNotBlank(),
                        "url" to it.url,
                    )
                },
            )
        }

    // Header cells. Clicking the sorted column flips its direction; a fresh column starts ascending —
    // except References, where "most referenced first" is the useful first click.
    val columns = listOf(
        BrokenSortColumn.PATH to "Missing file",
        BrokenSortColumn.LOCALE to "Locale",
        BrokenSortColumn.REFS to "References",
    ).map { (column, label) ->
        val active = column == sort
        val nextDescending = if (active) !descending else column == BrokenSortColumn.REFS
        mapOf(
            "label" to label,
            "url" to brokenListUrl(q, localeFilter, column, nextDescending, 1, size),
            "ascending" to (active && !descending),
            "descending" to (active && descending),
            "ariaSort" to when {
                !active -> "none"
                descending -> "descending"
                else -> "ascending"
            },
        )
    }

    val firstRow = if (sorted.isEmpty()) 0 else (current - 1) * size + 1
    val unresolvableRows = unresolvable.entries.sortedBy { it.key }.map { (url, sources) ->
        mapOf(
            "url" to url,
            // What it should be written as: absolute, no locale — binds to the page's locale and falls
            // back to the default. Only offered when the relative form has no "../", which has no
            // single absolute equivalent.
            "suggestion" to if (url.startsWith("..")) null else "/${url.removePrefix("./")}",
            "refCount" to sources.size,
            "sources" to sources.sortedWith(compareBy({ it.type }, { it.label })).map {
                mapOf(
                    "type" to it.type,
                    "label" to it.label,
                    "locale" to it.locale,
                    "hasLocale" to it.locale.isNotBlank(),
                    "url" to it.url,
                )
            },
        )
    }
    return adminBaseModel() + assetShellModel() + mapOf(
        "broken" to rows,
        // The section (filter form included) shows whenever anything at all is broken — a filter that
        // matches nothing must still render the form, or there'd be no way to clear it.
        "hasBroken" to all.isNotEmpty(),
        "brokenCount" to all.size,
        "hasRows" to rows.isNotEmpty(),
        "filteredCount" to sorted.size,
        "isFiltered" to (q.isNotEmpty() || localeFilter.isNotEmpty()),
        "q" to q,
        "localeOptions" to localeOptions,
        "columns" to columns,
        "firstRow" to firstRow,
        "lastRow" to firstRow + rows.size - if (rows.isEmpty()) 0 else 1,
        "multiplePages" to (totalPages > 1),
        "hasPrev" to (current > 1),
        "prevUrl" to brokenListUrl(q, localeFilter, sort, descending, current - 1, size),
        "hasNext" to (current < totalPages),
        "nextUrl" to brokenListUrl(q, localeFilter, sort, descending, current + 1, size),
        "pageLinks" to brokenListLinks(q, localeFilter, sort, descending, current, totalPages, size),
        "sizeOptions" to BROKEN_LIST_SIZES.map {
            mapOf("label" to it.toString(), "url" to brokenListUrl(q, localeFilter, sort, descending, 1, it), "current" to (it == size))
        },
        "clearUrl" to brokenListUrl("", "", sort, descending, 1, size),
        // Hidden form fields carrying sort/size through a filter submit — only when non-default, so
        // applying a filter keeps the URL as clean as clicking a link would.
        "sortParam" to sort.key.takeIf { sort != BrokenSortColumn.PATH },
        "dirParam" to "desc".takeIf { descending },
        "sizeParam" to size.toString().takeIf { size != BROKEN_LIST_DEFAULT_SIZE },
        "unresolvable" to unresolvableRows,
        "hasUnresolvable" to unresolvableRows.isNotEmpty(),
        "unresolvableCount" to unresolvableRows.size,
        // File references to the instance's OTHER sites with nothing at the target (own-host absolute
        // refs are folded into the main table instead). Unfiltered/unpaged: short list by nature.
        "crossBroken" to crossBroken.map { (url, sources) ->
            mapOf(
                "url" to url,
                "refCount" to sources.size,
                "sources" to sources.sortedWith(compareBy({ it.type }, { it.label })).map {
                    mapOf(
                        "type" to it.type,
                        "label" to it.label,
                        "locale" to it.locale,
                        "hasLocale" to it.locale.isNotBlank(),
                        "url" to it.url,
                    )
                },
            )
        },
        "hasCrossBroken" to crossBroken.isNotEmpty(),
        "crossBrokenCount" to crossBroken.size,
        "crossUnchecked" to crossUnchecked.toList(),
        "hasCrossUnchecked" to crossUnchecked.isNotEmpty(),
    )
}

