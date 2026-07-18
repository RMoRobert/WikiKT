package com.wikikt.routing

import com.wikikt.appContext
import com.wikikt.siteId
import com.wikikt.auth.csrfField
import com.wikikt.db.ContentFormat
import com.wikikt.model.UpdatePageRequest
import com.wikikt.model.isReservedFirstSegment
import com.wikikt.model.normalizePagePath
import com.wikikt.model.parseTags
import com.wikikt.model.validateWikiPath
import com.wikikt.model.toIsoString
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.put
import io.ktor.server.application.call
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.configureWikiRouting() {
    get("/e/{path...}") {
        handleWikiRequest(call.parameters.getAll("path") ?: emptyList(), edit = true)
    }

    get("/{path...}") {
        val segments = call.parameters.getAll("path") ?: emptyList()
        if (segments.firstOrNull()?.let(::isReservedFirstSegment) == true) return@get
        handleWikiRequest(segments, edit = false)
    }

    post("/e/{path...}") {
        handleWikiSave(call.parameters.getAll("path") ?: emptyList())
    }

    get("/h/{path...}") {
        handleHistory(call.parameters.getAll("path") ?: emptyList())
    }

    post("/h/{path...}") {
        handleRestore(call.parameters.getAll("path") ?: emptyList())
    }

    get("/new") {
        handleNewPageForm()
    }

    post("/new") {
        handleNewPageCreate()
    }

    // Live-preview endpoint for the editor: renders through the real pipeline (so callouts, icons,
    // and future fragments match the saved page) and returns sanitized HTML. Read-only, so no CSRF.
    post("/preview") {
        val ctx = call.appContext
        val siteId = call.siteId()
        val userId = call.currentUserId()
        if (userId == null || !ctx.permissions.canCreatePagesOnSite(userId, siteId)) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val params = call.receiveParameters()
        val content = params["content"] ?: ""
        val format = runCatching {
            ContentFormat.valueOf((params["contentFormat"] ?: "MARKDOWN").uppercase())
        }.getOrDefault(ContentFormat.MARKDOWN)
        // Render in the edited page's locale so locale-relative asset refs preview correctly.
        val locale = params["locale"]?.takeIf { isLocaleSegment(it) } ?: ctx.config.defaultLocale
        // The path lets the preview resolve directory-relative links the same way the saved page will.
        val path = params["path"].orEmpty()
        call.respondText(call.renderContent(siteId, content, format, locale, path), ContentType.Text.Html)
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleWikiRequest(
    segments: List<String>,
    edit: Boolean,
) {
    val ctx = call.appContext
    val siteId = call.siteId()

    // A bare locale root ("/en") always resolves to that locale's reserved home page. The empty root
    // ("/") goes to the primary locale's home when locale prefixes are forced, else to the unprefixed
    // home page (so an un-prefixed site keeps its landing URL at "/home", not "/en/home").
    if (!edit) {
        val homeRedirect = when {
            segments.size == 1 && isLocaleSegment(segments[0]) -> "/${segments[0]}/$HOME_PAGE_PATH"
            segments.isEmpty() ->
                if (ctx.settings.getBool(siteId, com.wikikt.service.SettingsService.LOCALE_FORCE_PREFIX)) {
                    "/${ctx.config.defaultLocale}/$HOME_PAGE_PATH"
                } else {
                    "/$HOME_PAGE_PATH"
                }
            else -> null
        }
        if (homeRedirect != null) {
            call.respondRedirect(homeRedirect)
            return
        }
    }

    val wikiPath = parseWikiPath(if (edit) listOf("e") + segments else segments, ctx.config.defaultLocale)
        ?: run {
            call.respond(HttpStatusCode.NotFound, MustacheContent("error.hbs", call.errorModel("Not found", 404)))
            return
        }

    if (wikiPath.edit != edit) {
        call.respond(HttpStatusCode.NotFound, MustacheContent("error.hbs", call.errorModel("Not found", 404)))
        return
    }

    val page = ctx.pages.resolveByPath(siteId, wikiPath.locale, wikiPath.pagePath)
    val userId = call.currentUserId()

    if (page == null) {
        if (wikiPath.edit && ctx.permissions.canCreatePagesOnSite(userId, siteId)) {
            if (!wikiPath.localeExplicit && ctx.settings.getBool(siteId, com.wikikt.service.SettingsService.LOCALE_FORCE_PREFIX)) {
                call.redirectCanonical(wikiEditUrl(wikiPath.locale, wikiPath.pagePath))
                return
            }
            call.respond(
                MustacheContent(
                    "page/edit.hbs",
                    editModel(siteId, wikiPath, null, "", null, "", "MARKDOWN", true, null, emptyList(), userId, saved = false, isNew = true),
                ),
            )
            return
        }
        // No page here — an asset may live at this path (same namespace), serve it if so.
        if (!wikiPath.edit && call.serveAssetIfPresent(wikiPath.locale, wikiPath.pagePath)) return
        // Friendly 404 with the full site chrome; offers to create the page if the user is allowed to.
        // The global StatusPages NotFound handler renders this model (stashed) so the 404 status sticks.
        call.attributes.put(NotFoundModelKey, notFoundModel(siteId, wikiPath, userId))
        call.respond(HttpStatusCode.NotFound)
        return
    }

    // A page exists but the URL omitted the locale. When locale prefixes are forced, canonicalize to
    // /<locale>/…; otherwise serve the unprefixed URL as-is with the locale inferred.
    if (!wikiPath.localeExplicit && ctx.settings.getBool(siteId, com.wikikt.service.SettingsService.LOCALE_FORCE_PREFIX)) {
        call.redirectCanonical(
            if (wikiPath.edit) wikiEditUrl(wikiPath.locale, wikiPath.pagePath)
            else wikiViewUrl(wikiPath.locale, wikiPath.pagePath),
        )
        return
    }

    if (!ctx.permissions.canViewPage(userId, page)) {
        call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Access denied", 403)))
        return
    }

    if (wikiPath.edit) {
        if (!ctx.permissions.canEditPage(userId, page)) {
            call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Access denied", 403)))
            return
        }
        // For a live page with a staged version, continue editing the staged content; else the live content.
        val staged = if (page.published) ctx.pages.stagedFor(page.id) else null
        val eTitle = staged?.title ?: page.title
        val eDescription = if (staged != null) staged.description else page.description
        val eContent = staged?.content ?: page.content
        val eFormat = (staged?.contentFormat ?: page.contentFormat).name
        call.respond(
            MustacheContent(
                "page/edit.hbs",
                editModel(
                    siteId, wikiPath, page.id.toString(), eTitle, eDescription, eContent, eFormat,
                    page.published, page.publishAt, page.tags, userId, saved = false, isNew = false,
                    isLive = page.published, staged = staged, metaRobots = page.metaRobots,
                    infobox = staged?.infobox ?: page.infobox,
                    customCss = page.customCss, customJs = page.customJs,
                ),
            ),
        )
        return
    }

    val canEdit = ctx.permissions.canEditPage(userId, page)
    val staged = if (page.published) ctx.pages.stagedFor(page.id) else null
    // Editors can preview the staged version with ?staged; everyone else always sees live.
    val previewStaged = call.request.queryParameters.contains("staged") && canEdit && staged != null
    val src = if (previewStaged) staged else null
    // Live view serves the cached body + infobox; a staged preview is transient/editor-only, so render live.
    val html: String
    val infoboxHtml: String?
    if (src != null) {
        html = call.renderContent(siteId, src.content, src.contentFormat, wikiPath.locale, page.path)
        infoboxHtml = call.renderInfoboxLive(siteId, page.path, page.tags, src.infobox, wikiPath.locale)
    } else {
        val rendered = call.renderCachedBody(page)
        html = rendered.first
        infoboxHtml = rendered.second
    }
    val authorName = page.updatedBy?.let { ctx.users.findById(it)?.username }
    // Editor-only note: names of matched templates the page has no data for yet (a page can match more
    // than one). Every infobox is optional — this is purely informational. Resolved only for editors.
    val infoboxMissingNames = if (canEdit) ctx.infobox.unfilledTemplateNames(siteId, page.path, page.tags, page.infobox) else emptyList()
    call.respond(
        MustacheContent(
            "page/view.hbs",
            viewModel(
                siteId, wikiPath, src?.title ?: page.title, if (src != null) src.description else page.description, html,
                page.updatedAt, page.published, page.tags, userId, canEdit = canEdit,
                contentFormat = src?.contentFormat ?: page.contentFormat,
                pageMetaRobots = page.metaRobots, updatedByName = authorName,
                staged = staged, previewingStaged = previewStaged, infoboxHtml = infoboxHtml,
                infoboxMissingNames = infoboxMissingNames,
                customCss = page.customCss, customJs = page.customJs,
            ),
        ),
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.handleWikiSave(segments: List<String>) {
    val ctx = call.appContext
    val siteId = call.siteId()
    val wikiPath = parseWikiPath(listOf("e") + segments, ctx.config.defaultLocale)
        ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }

    val userId = call.currentUserId()
    if (userId == null) {
        call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Access denied", 403)))
        return
    }

    val params = call.receiveParameters()
    if (!call.validateFormCsrf(params)) return
    val existing = ctx.pages.resolveByPath(siteId, wikiPath.locale, wikiPath.pagePath)
    val editUrl = wikiEditUrl(wikiPath.locale, wikiPath.pagePath)

    // Creating a page requires write:pages at the target site+path; editing requires write:pages on
    // the existing page. Both are site- and path-scoped through the resolver.
    val mayWrite = if (existing == null) {
        ctx.permissions.check(userId, com.wikikt.service.AccessResolver.Perm.WRITE_PAGES, siteId, wikiPath.locale, wikiPath.pagePath)
    } else {
        ctx.permissions.canEditPage(userId, existing)
    }
    if (!mayWrite) {
        call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Access denied", 403)))
        return
    }

    // Discard a staged version (button on a live page's editor).
    if (existing != null && existing.published && params["discardStaged"] != null) {
        ctx.pages.discardStaged(existing.id)
        call.respondRedirect(editUrl)
        return
    }

    // Delete the page (Page actions → Delete). Edit permission was already verified above; deletion
    // cascades revisions/staged/tags/acls/search in PageService. Redirect to the locale home.
    if (existing != null && params["deletePage"] != null) {
        if (!ctx.permissions.check(userId, com.wikikt.service.AccessResolver.Perm.DELETE_PAGES, existing.siteId, existing.locale, existing.path)) {
            call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Access denied", 403)))
            return
        }
        ctx.pages.delete(existing.id)
        call.respondRedirect(if (wikiPath.locale == ctx.config.defaultLocale) "/" else "/${wikiPath.locale}")
        return
    }

    // Convert the stored content format (Page actions → Convert). Only Markdown→HTML is supported: render
    // the live Markdown through the normal pipeline once and re-save it as HTML (update() keeps a revision).
    // HTML→Markdown has no converter, so the view-page modal never offers it; ignore anything unexpected.
    if (existing != null && params["convertFormat"] != null) {
        val target = runCatching { ContentFormat.valueOf(params["convertFormat"]!!.uppercase()) }.getOrNull()
        if (target == ContentFormat.HTML && existing.contentFormat == ContentFormat.MARKDOWN) {
            val htmlContent = call.renderContent(siteId, existing.content, ContentFormat.MARKDOWN, existing.locale, existing.path)
            ctx.pages.update(
                existing.id,
                UpdatePageRequest(content = htmlContent, contentFormat = ContentFormat.HTML.name),
                updatedBy = userId,
            )
        }
        call.respondRedirect(wikiViewUrl(existing.locale, existing.path))
        return
    }

    val title = params["title"]?.trim().orEmpty()
    val description = params["description"]?.trim()
    // Per-page robots override: only a known directive is honored; anything else means "inherit site default".
    val metaRobots = params["metaRobots"]?.trim()?.ifBlank { null }
        ?.takeIf { it in com.wikikt.service.SettingsService.META_ROBOTS_OPTIONS }
    // Per-page custom code (unsanitized <head> injection) is gated by manage:theme. For a non-privileged
    // editor we pass null (leave existing values untouched — and reject any crafted POST field), so only a
    // user who can see the Custom Code tab can set/clear it. Blank from a privileged editor clears it.
    val canManageCode = ctx.permissions.canManageTheme(userId)
    val customCss = if (canManageCode) params["customCss"] ?: "" else null
    val customJs = if (canManageCode) params["customJs"] ?: "" else null
    val tags = parseTags(params["tags"])
    val content = params["content"] ?: ""
    val contentFormat = params["contentFormat"] ?: "MARKDOWN"
    // Infobox values from the template-driven form: coerce each posted `infobox.<field>` to the field's
    // JSON type and rebuild the object. Null when no template is bound to this path (leaves infobox
    // unchanged on update); "" when a template applies but every field is empty (clears it). Resolved by
    // the current path — matching the template the editor rendered — so a same-save move keeps its data.
    val infoboxJson = infoboxFromParams(ctx, siteId, wikiPath.pagePath, tags, params)
    val isLive = existing != null && existing.published
    // Form path/locale are authoritative (URL is fallback); changing them on an existing page is a move.
    val targetLocale = params["locale"]?.trim()?.ifBlank { null } ?: wikiPath.locale
    val targetPath = runCatching { normalizePagePath(params["path"]?.trim()?.ifBlank { null } ?: wikiPath.pagePath) }.getOrNull()

    suspend fun reRender(message: String?) {
        call.respond(
            MustacheContent(
                "page/edit.hbs",
                editModel(
                    siteId, wikiPath, existing?.id?.toString(), title, description, content, contentFormat,
                    published = existing?.published ?: true, publishAt = existing?.publishAt, tags, userId,
                    saved = false, isNew = existing == null, error = message, isLive = isLive,
                    metaRobots = metaRobots, infobox = infoboxJson,
                    customCss = customCss, customJs = customJs,
                ),
            ),
        )
    }

    if (title.isBlank()) {
        reRender("Title is required")
        return
    }
    if (targetPath == null) {
        reRender("Please enter a path.")
        return
    }

    // Validates the target (locale + path), then moves the page; returns an error message or null.
    suspend fun moveOrError(pageId: UInt): String? {
        if (!isLocaleSegment(targetLocale)) return "Locale must be a 2-letter code (optionally with a region, e.g. en or fr-ca)."
        val pathError = runCatching { validateWikiPath(targetPath, allowExtension = false) }.exceptionOrNull()
        if (pathError != null) return pathError.message
        // Moving needs manage:pages on the source and write:pages at the destination.
        if (existing != null &&
            !ctx.permissions.check(userId, com.wikikt.service.AccessResolver.Perm.MANAGE_PAGES, existing.siteId, existing.locale, existing.path)
        ) {
            return "You don't have permission to move this page."
        }
        if (!ctx.permissions.check(userId, com.wikikt.service.AccessResolver.Perm.WRITE_PAGES, siteId, targetLocale, targetPath)) {
            return "You don't have write access at the destination path."
        }
        if (!ctx.pages.move(pageId, targetLocale, targetPath, userId)) return "A page already exists at '$targetPath' ($targetLocale)."
        return null
    }

    // Page-level publish controls (first-publish): only meaningful for new/draft pages.
    val zone = call.displayZone()
    val published = params["published"] != null
    val publishAt = if (published) null else params["publishAt"]?.trim()?.ifBlank { null }?.let { parsePublishAt(it, zone) }
    val pathChanged = existing != null && (targetLocale != existing.locale || targetPath != existing.path)

    when {
        existing == null -> {
            val pathError = runCatching { validateWikiPath(targetPath, allowExtension = false) }.exceptionOrNull()
            if (pathError != null) {
                reRender(pathError.message)
                return
            }
            if (ctx.pages.resolveByPath(siteId, targetLocale, targetPath) != null) {
                reRender("A page already exists at that path.")
                return
            }
            ctx.pages.create(
                siteId,
                com.wikikt.model.CreatePageRequest(
                    locale = targetLocale, path = targetPath, title = title, description = description,
                    metaRobots = metaRobots,
                    content = content, contentFormat = contentFormat, published = published, publishAt = publishAt, tags = tags,
                    infobox = infoboxJson, customCss = customCss, customJs = customJs,
                ),
                updatedBy = userId,
            )
            call.respondRedirect(wikiViewUrl(targetLocale, targetPath))
        }
        !existing.published -> {
            // Draft page: existing first-publish flow (+ move if path/locale changed).
            if (pathChanged) {
                moveOrError(existing.id)?.let { reRender(it); return }
            }
            ctx.pages.update(
                existing.id,
                UpdatePageRequest(title = title, description = description, metaRobots = metaRobots, content = content, contentFormat = contentFormat, published = published, publishAt = publishAt, tags = tags, infobox = infoboxJson, customCss = customCss, customJs = customJs),
                updatedBy = userId,
            )
            call.respondRedirect(wikiViewUrl(targetLocale, targetPath))
        }
        else -> {
            // Live page: Update now, or stage (optionally scheduled). Tags/path apply only on Update now.
            val fmt = runCatching { ContentFormat.valueOf(contentFormat.uppercase()) }.getOrDefault(ContentFormat.MARKDOWN)
            when (params["applyMode"] ?: "now") {
                "staged", "scheduled" -> {
                    if (pathChanged) {
                        reRender("To move the page, choose \"Update live now\" — a path change can't be staged.")
                        return
                    }
                    val stagedAt = if (params["applyMode"] == "scheduled") {
                        params["stagedPublishAt"]?.trim()?.ifBlank { null }?.let { parsePublishAt(it, zone) }
                    } else {
                        null
                    }
                    ctx.pages.upsertStaged(existing.id, title, description?.ifBlank { null }, content, fmt, stagedAt, userId, infobox = infoboxJson?.ifBlank { null })
                    call.respondRedirect(editUrl)
                }
                else -> {
                    if (pathChanged) {
                        moveOrError(existing.id)?.let { reRender(it); return }
                    }
                    ctx.pages.update(
                        existing.id,
                        UpdatePageRequest(title = title, description = description, metaRobots = metaRobots, content = content, contentFormat = contentFormat, tags = tags, infobox = infoboxJson, customCss = customCss, customJs = customJs),
                        updatedBy = userId,
                    )
                    ctx.pages.discardStaged(existing.id)
                    call.respondRedirect(wikiViewUrl(targetLocale, targetPath))
                }
            }
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.handleHistory(segments: List<String>) {
    val ctx = call.appContext
    val siteId = call.siteId()
    val wikiPath = parseWikiPath(segments, ctx.config.defaultLocale)?.takeIf { !it.edit }
        ?: run {
            call.respond(HttpStatusCode.NotFound, MustacheContent("error.hbs", call.errorModel("Not found", 404)))
            return
        }

    val page = ctx.pages.resolveByPath(siteId, wikiPath.locale, wikiPath.pagePath) ?: run {
        call.respond(HttpStatusCode.NotFound, MustacheContent("error.hbs", call.errorModel("Page not found", 404)))
        return
    }
    if (!wikiPath.localeExplicit && ctx.settings.getBool(siteId, com.wikikt.service.SettingsService.LOCALE_FORCE_PREFIX)) {
        call.redirectCanonical(wikiHistoryUrl(wikiPath.locale, wikiPath.pagePath))
        return
    }
    val userId = call.currentUserId()
    if (!ctx.permissions.canViewPage(userId, page)) {
        call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Access denied", 403)))
        return
    }
    // Viewing revision history is a separate verb (read:history), scoped to this page's site+path.
    // The list and single-revision views both flow through here.
    if (!ctx.permissions.canViewHistory(userId, page)) {
        call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Access denied", 403)))
        return
    }
    val canEdit = ctx.permissions.canEditPage(userId, page)
    val historyUrl = wikiHistoryUrl(wikiPath.locale, wikiPath.pagePath)
    val viewUrl = wikiViewUrl(wikiPath.locale, wikiPath.pagePath)
    val username = userId?.let { ctx.users.findById(it)?.username }
    val formats = call.displayFormats()

    val revNumber = call.request.queryParameters["rev"]?.toIntOrNull()
    if (revNumber != null) {
        val revision = ctx.pages.revision(page.id, revNumber) ?: run {
            call.respond(HttpStatusCode.NotFound, MustacheContent("error.hbs", call.errorModel("Revision not found", 404)))
            return
        }
        val html = call.renderContent(siteId, revision.content, revision.contentFormat, wikiPath.locale, page.path)
        val author = revision.createdBy?.let { ctx.users.findById(it)?.username } ?: "—"
        call.respond(
            MustacheContent(
                "page/revision.hbs",
                mapOf(
                    "title" to page.title,
                    "revisionNumber" to revision.revisionNumber,
                    "createdAt" to DateDisplay.format(revision.createdAt, formats),
                    "author" to author,
                    "htmlContent" to html,
                    "viewUrl" to viewUrl,
                    "historyUrl" to historyUrl,
                    "canEdit" to canEdit,
                    "csrfField" to call.csrfField(),
                    "username" to username,
                    "loggedIn" to (userId != null),
                    "canAdmin" to ctx.permissions.canAccessAdmin(userId),
                ) + call.navModel(wikiPath.pagePath, wikiPath.locale),
            ),
        )
        return
    }

    val revisions = ctx.pages.revisions(page.id).map { revision ->
        mapOf(
            "revisionNumber" to revision.revisionNumber,
            "createdAt" to DateDisplay.format(revision.createdAt, formats),
            "author" to (revision.createdBy?.let { ctx.users.findById(it)?.username } ?: "—"),
            "viewUrl" to "$historyUrl?rev=${revision.revisionNumber}",
        )
    }
    call.respond(
        MustacheContent(
            "page/history.hbs",
            mapOf(
                "title" to page.title,
                "viewUrl" to viewUrl,
                "historyUrl" to historyUrl,
                "revisions" to revisions,
                "empty" to revisions.isEmpty(),
                "canEdit" to canEdit,
                "csrfField" to call.csrfField(),
                "username" to username,
                "loggedIn" to (userId != null),
                "canAdmin" to ctx.permissions.canAccessAdmin(userId),
            ) + call.navModel(wikiPath.pagePath, wikiPath.locale),
        ),
    )
}

private suspend fun io.ktor.server.routing.RoutingContext.handleRestore(segments: List<String>) {
    val ctx = call.appContext
    val siteId = call.siteId()
    val wikiPath = parseWikiPath(segments, ctx.config.defaultLocale)?.takeIf { !it.edit }
        ?: run {
            call.respond(HttpStatusCode.BadRequest)
            return
        }
    val page = ctx.pages.resolveByPath(siteId, wikiPath.locale, wikiPath.pagePath) ?: run {
        call.respond(HttpStatusCode.NotFound, MustacheContent("error.hbs", call.errorModel("Page not found", 404)))
        return
    }
    val userId = call.currentUserId()
    if (userId == null || !ctx.permissions.canEditPage(userId, page)) {
        call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Access denied", 403)))
        return
    }
    val params = call.receiveParameters()
    if (!call.validateFormCsrf(params)) return
    val revNumber = params["rev"]?.toIntOrNull() ?: run {
        call.respond(HttpStatusCode.BadRequest)
        return
    }
    if (!ctx.pages.restoreRevision(page.id, revNumber, userId)) {
        call.respond(HttpStatusCode.NotFound, MustacheContent("error.hbs", call.errorModel("Revision not found", 404)))
        return
    }
    call.respondRedirect(wikiViewUrl(wikiPath.locale, wikiPath.pagePath))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleNewPageForm() {
    val ctx = call.appContext
    val userId = call.currentUserId()
    if (userId == null || !ctx.permissions.canCreatePagesOnSite(userId, call.siteId())) {
        call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Access denied", 403)))
        return
    }
    val from = call.request.queryParameters["from"]?.ifBlank { null }
    val fromLocale = call.request.queryParameters["fromLocale"]?.ifBlank { null } ?: ctx.config.defaultLocale
    call.respond(MustacheContent("page/new.hbs", call.newPageModel(userId, from, fromLocale, error = null)))
}

private suspend fun io.ktor.server.routing.RoutingContext.handleNewPageCreate() {
    val ctx = call.appContext
    val siteId = call.siteId()
    val userId = call.currentUserId()
    if (userId == null || !ctx.permissions.canCreatePagesOnSite(userId, siteId)) {
        call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Access denied", 403)))
        return
    }
    val params = call.receiveParameters()
    if (!call.validateFormCsrf(params)) return
    val locale = params["locale"]?.trim()?.ifBlank { null } ?: ctx.config.defaultLocale
    val from = params["from"]?.trim()?.ifBlank { null }
    val fromLocale = params["fromLocale"]?.trim()?.ifBlank { null } ?: ctx.config.defaultLocale
    val path = runCatching { normalizePagePath(params["path"]?.trim().orEmpty()) }.getOrNull()
    if (path == null) {
        call.respond(MustacheContent("page/new.hbs", call.newPageModel(userId, from, fromLocale, error = "Please enter a path.")))
        return
    }
    val pathError = runCatching { validateWikiPath(path, allowExtension = false) }.exceptionOrNull()
    if (pathError != null) {
        call.respond(MustacheContent("page/new.hbs", call.newPageModel(userId, from, fromLocale, error = pathError.message)))
        return
    }
    // The coarse gate above only proves the caller may create SOMEWHERE on the site. Enforce write:pages
    // against the exact target path, matching the JSON create endpoint — otherwise a path-scoped editor
    // could create pages outside their granted subtree.
    if (!ctx.permissions.check(userId, com.wikikt.service.AccessResolver.Perm.WRITE_PAGES, siteId, locale, path)) {
        call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Access denied", 403)))
        return
    }
    if (ctx.pages.resolveByPath(siteId, locale, path) != null) {
        call.respond(
            MustacheContent("page/new.hbs", call.newPageModel(userId, from, fromLocale, error = "A page already exists at that path.")),
        )
        return
    }
    if (from != null) {
        // Only copy from a source the caller may actually view — otherwise "duplicate" would exfiltrate the
        // content of a page they are denied (a per-path DENY, no grant, or an unpublished draft). An
        // unreadable/absent source is treated identically: no copy, just a blank new page.
        val source = ctx.pages.resolveByPath(siteId, fromLocale, normalizePagePath(from))
            ?.takeIf { ctx.permissions.canViewPage(userId, it) }
        if (source != null) {
            ctx.pages.create(
                siteId,
                com.wikikt.model.CreatePageRequest(
                    locale = locale,
                    path = path,
                    title = source.title,
                    content = source.content,
                    contentFormat = source.contentFormat.name,
                ),
                updatedBy = userId,
            )
        }
    }
    call.respondRedirect(wikiEditUrl(locale, path))
}

private suspend fun io.ktor.server.application.ApplicationCall.newPageModel(
    userId: UInt,
    from: String?,
    fromLocale: String,
    error: String?,
): Map<String, Any?> {
    val ctx = appContext
    val siteId = siteId()
    val username = ctx.users.findById(userId)?.username
    val enabled = ctx.settings.enabledLocales(siteId, ctx.config.defaultLocale)
    return mapOf(
        "csrfField" to csrfField(),
        "defaultLocale" to ctx.config.defaultLocale,
        "localeOptions" to localeSelectOptions(enabled, ctx.config.defaultLocale),
        "from" to from,
        "fromLocale" to fromLocale,
        "isDuplicate" to (from != null),
        "sourceLabel" to from,
        "error" to error,
        "username" to username,
        "loggedIn" to true,
        "canAdmin" to ctx.permissions.canAccessAdmin(userId),
    ) + navModel()
}

/** Fallback breadcrumb label for a path segment with no backing page: "user-guide" -> "User Guide". */
internal fun humanizePathSegment(segment: String): String =
    segment.split('-', '_').filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

/**
 * Language-switcher options for the current page: the current locale plus every OTHER [enabled] locale
 * in which this exact page exists (so the globe never links to a missing translation). The caller
 * gates on multiple enabled locales and hides the switcher when this has fewer than two entries.
 */
private suspend fun buildLocaleSwitcher(
    siteId: UInt,
    ctx: com.wikikt.AppContext,
    enabled: List<String>,
    currentLocale: String,
    path: String,
): List<Map<String, Any?>> =
    enabled.mapNotNull { loc ->
        val exists = loc == currentLocale || ctx.pages.findByLocaleAndPath(siteId, loc, path) != null
        if (!exists) {
            null
        } else {
            mapOf(
                "locale" to loc,
                "label" to localeLabel(loc),
                "url" to wikiViewUrl(loc, path),
                "current" to (loc == currentLocale),
            )
        }
    }

/** Endonym for a locale code (e.g. "en"→"English", "pt"→"Português"), falling back to the raw code. */
private fun localeLabel(code: String): String {
    val locale = java.util.Locale.forLanguageTag(code)
    val name = locale.getDisplayLanguage(locale)
    return if (name.isBlank()) code else name.replaceFirstChar { it.titlecase(locale) }
}

private suspend fun io.ktor.server.routing.RoutingContext.viewModel(
    siteId: UInt,
    wikiPath: WikiPathRequest,
    title: String,
    description: String?,
    htmlContent: String,
    updatedAtMillis: Long,
    published: Boolean,
    tags: List<String>,
    userId: UInt?,
    canEdit: Boolean,
    contentFormat: ContentFormat,
    pageMetaRobots: String? = null,
    updatedByName: String? = null,
    staged: com.wikikt.model.PageStagedRecord? = null,
    previewingStaged: Boolean = false,
    infoboxHtml: String? = null,
    infoboxMissingNames: List<String> = emptyList(),
    customCss: String? = null,
    customJs: String? = null,
): Map<String, Any?> {
    val ctx = call.appContext
    val formats = call.displayFormats()
    val username = userId?.let { ctx.users.findById(it)?.username }
    // SEO: meta description and robots fall back to the site-wide defaults when the page has none.
    val s = com.wikikt.service.SettingsService
    val metaDescription = description?.ifBlank { null }
        ?: ctx.settings.get(siteId, s.SITE_DESCRIPTION)?.ifBlank { null }
    val metaRobots = pageMetaRobots?.ifBlank { null }
        ?: ctx.settings.get(siteId, s.SITE_META_ROBOTS)?.ifBlank { null }
        ?: s.DEFAULT_META_ROBOTS
    // Table of contents (site-wide): mode (floating/column/off) + side (left/right). Built client-side
    // from the rendered H1/H2 headings; the template just provides the container and CSS hooks.
    val tocMode = ctx.settings.get(siteId, s.SITE_TOC_MODE)?.ifBlank { null } ?: s.DEFAULT_TOC_MODE
    val tocSide = ctx.settings.get(siteId, s.SITE_TOC_SIDE)?.ifBlank { null } ?: s.DEFAULT_TOC_SIDE
    // The side column always renders (it carries the "page details" box); the TOC list within it is
    // gated by tocEnabled. With the TOC off the column falls back to plain (non-card) styling.
    val asideMode = if (tocMode == "off") "column" else tocMode
    // Breadcrumb trail: Home > ancestor > … > current page. Ancestor crumbs link to (and are labelled
    // by) the real page at that path when one exists; otherwise they show a humanized path segment with
    // no link (so we never link to a non-existent ancestor). The leaf uses the page's actual title.
    val breadcrumbs = mutableListOf<Map<String, Any?>>(
        mapOf(
            "label" to "Home",
            "url" to if (wikiPath.locale == ctx.config.defaultLocale) "/" else "/${wikiPath.locale}",
            "hasUrl" to true,
            "isCurrent" to false,
            "isHome" to true,
        ),
    )
    val crumbSegments = wikiPath.pagePath.split("/").filter { it.isNotBlank() }
    var crumbPath = ""
    for ((i, segment) in crumbSegments.withIndex()) {
        crumbPath = if (crumbPath.isEmpty()) segment else "$crumbPath/$segment"
        if (i == crumbSegments.lastIndex) {
            breadcrumbs += mapOf("label" to title, "hasUrl" to false, "isCurrent" to true)
        } else {
            val ancestor = ctx.pages.resolveByPath(siteId, wikiPath.locale, crumbPath)
            breadcrumbs += mapOf(
                "label" to (ancestor?.title ?: humanizePathSegment(segment)),
                "url" to wikiViewUrl(wikiPath.locale, crumbPath),
                "hasUrl" to (ancestor != null),
                "isCurrent" to false,
            )
        }
    }
    // The globe only appears when the site has more than one content locale enabled; within that,
    // it lists only locales in which this page actually exists (so there's always somewhere to go).
    val enabled = ctx.settings.enabledLocales(siteId, ctx.config.defaultLocale)
    val localeSwitcher = if (enabled.size > 1) {
        buildLocaleSwitcher(siteId, ctx, enabled, wikiPath.locale, wikiPath.pagePath)
    } else {
        emptyList()
    }
    val canCreate = ctx.permissions.canCreatePagesOnSite(userId, siteId)
    val canViewHistory = ctx.permissions.canViewHistory(userId) // coarse: nav-link affordance; the /h route re-checks per page
    return mapOf(
        "breadcrumbs" to breadcrumbs,
        // The home page doesn't show the breadcrumb band ("Home › Home" says nothing).
        "showBreadcrumbs" to (wikiPath.pagePath != HOME_PAGE_PATH),
        "title" to title,
        "description" to description,
        "hasDescription" to !description.isNullOrBlank(),
        // <head> SEO: description meta uses the site default when the page has none; robots is always set.
        "metaDescription" to metaDescription,
        "hasMetaDescription" to !metaDescription.isNullOrBlank(),
        "metaRobots" to metaRobots,
        "tags" to tags.map { mapOf("name" to it, "url" to tagUrl(it)) },
        "hasTags" to tags.isNotEmpty(),
        "infoboxHtml" to infoboxHtml,
        "hasInfobox" to (infoboxHtml != null),
        "htmlContent" to htmlContent,
        // Date only (no time) in the viewer's long-date style — the "last modified" line is a
        // date-level fact; exact times live on the history page.
        "updatedAt" to DateDisplay.formatDate(updatedAtMillis, formats),
        "locale" to wikiPath.locale,
        "pagePath" to wikiPath.pagePath,
        "viewUrl" to wikiViewUrl(wikiPath.locale, wikiPath.pagePath),
        "editUrl" to wikiEditUrl(wikiPath.locale, wikiPath.pagePath),
        "historyUrl" to wikiHistoryUrl(wikiPath.locale, wikiPath.pagePath),
        "duplicateUrl" to "/new?from=" +
            java.net.URLEncoder.encode(wikiPath.pagePath, Charsets.UTF_8) + "&fromLocale=" + wikiPath.locale,
        // Hide the whole dropdown when every item in it is permission-gated off (e.g. guests who can
        // neither edit, create, nor view history) — otherwise the icon opens an empty menu.
        "pageActions" to (canEdit || canCreate || canViewHistory),
        // Language switcher (globe): shown only with multiple locales enabled AND >1 translation of
        // this page (see the `enabled.size > 1` gate above); lists only locales where the page exists.
        "localeSwitcher" to localeSwitcher,
        "hasLocaleSwitcher" to (localeSwitcher.size > 1),
        // Page actions → Convert/Delete post back to the edit route; they need a CSRF token, and Convert
        // only offers Markdown→HTML (the other direction has no converter).
        "csrfField" to call.csrfField(),
        "isMarkdown" to (contentFormat == ContentFormat.MARKDOWN),
        "canEdit" to canEdit,
        "canCreate" to canCreate,
        "canViewHistory" to canViewHistory,
        "tocEnabled" to (tocMode != "off"),
        "asideClass" to "page-aside--$asideMode page-aside--$tocSide",
        "updatedByName" to updatedByName,
        "hasUpdatedBy" to (updatedByName != null),
        "published" to published,
        "hasStaged" to (staged != null),
        "stagedScheduled" to (staged?.publishAt != null),
        "stagedScheduledAt" to staged?.publishAt?.let { DateDisplay.format(it, formats) },
        "previewingStaged" to previewingStaged,
        "stagedPreviewUrl" to wikiViewUrl(wikiPath.locale, wikiPath.pagePath) + "?staged",
        "username" to username,
        "loggedIn" to (userId != null),
        "canAdmin" to ctx.permissions.canAccessAdmin(userId),
        // Header search box: scope to the page's locale by default; prefill is empty until a query.
        "searchLocale" to wikiPath.locale,
        "searchQ" to "",
        // Per-page custom code injected into <head> on view only (see view.hbs). CSS is wrapped in a
        // <style> tag on render; JS is emitted verbatim (the author supplies their own <script> tags).
        "pageCustomCss" to customCss?.ifBlank { null },
        "pageCustomJs" to customJs?.ifBlank { null },
    ) + infoboxNudgeModel(infoboxMissingNames) + call.navModel(wikiPath.pagePath, wikiPath.locale)
}

/**
 * Model keys for the "you should fill this in" nudge, shared by the page-view banner and the Page Info
 * General tab: a boolean gate, the comma-joined template names, and a noun/pronoun pre-pluralized in
 * Kotlin (so the Mustache side never has to branch on count) — "an infobox"/"it" for one name,
 * "infoboxes"/"them" for several.
 */
private fun infoboxNudgeModel(missingNames: List<String>): Map<String, Any?> = mapOf(
    "infoboxMissing" to missingNames.isNotEmpty(),
    "infoboxMissingNames" to missingNames.joinToString(", "),
    "infoboxMissingNoun" to if (missingNames.size <= 1) "an infobox" else "infoboxes",
    "infoboxMissingPronoun" to if (missingNames.size <= 1) "it" else "them",
)

/**
 * Rebuilds the infobox JSON from the posted `infobox.<templateSlug>.<field>` form params, coercing each
 * value to its field's type, for EVERY template matched at [path]/[tags] (a page can match more than
 * one — each gets its own namespaced slice so same-named fields across templates never collide).
 * Returns null when nothing is matched (so an update leaves the stored infobox untouched), or "" when
 * templates are matched but no field on any of them has a value (so it's cleared). Booleans are stored
 * only when explicitly Yes/No (the tri-state "—" option omits the field).
 */
private suspend fun infoboxFromParams(
    ctx: com.wikikt.AppContext,
    siteId: UInt,
    path: String,
    tags: List<String>,
    params: io.ktor.http.Parameters,
): String? {
    val matches = ctx.infobox.resolveAllFor(siteId, path, tags)
    if (matches.isEmpty()) return null
    val root = kotlinx.serialization.json.buildJsonObject {
        for (template in matches) {
            val sub = kotlinx.serialization.json.buildJsonObject {
                for (field in template.fields) {
                    val key = "infobox.${template.slug}.${field.name}"
                    when (field.type.lowercase()) {
                        // Tri-state select: "true"/"false" store the boolean; "" (or absent) omits the field.
                        "boolean" -> when (params[key]?.trim()) {
                            "true" -> put(field.name, true)
                            "false" -> put(field.name, false)
                        }
                        "array" -> {
                            val values = params.getAll(key)?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                            if (values.isNotEmpty()) {
                                put(field.name, kotlinx.serialization.json.JsonArray(values.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                            }
                        }
                        else -> {
                            val value = params[key]?.trim()
                            if (!value.isNullOrEmpty()) put(field.name, value)
                        }
                    }
                }
            }
            if (sub.isNotEmpty()) put(template.slug, sub)
        }
    }
    return if (root.isEmpty()) "" else root.toString()
}

private suspend fun io.ktor.server.routing.RoutingContext.editModel(
    siteId: UInt,
    wikiPath: WikiPathRequest,
    pageId: String?,
    title: String,
    description: String?,
    content: String,
    contentFormat: String,
    published: Boolean,
    publishAt: Long?,
    tags: List<String>,
    userId: UInt?,
    saved: Boolean,
    isNew: Boolean,
    error: String? = null,
    isLive: Boolean = false,
    staged: com.wikikt.model.PageStagedRecord? = null,
    metaRobots: String? = null,
    infobox: String? = null,
    customCss: String? = null,
    customJs: String? = null,
): Map<String, Any?> {
    val ctx = call.appContext
    val formats = call.displayFormats()
    val username = userId?.let { ctx.users.findById(it)?.username }
    // Per-page custom code (unsanitized) is gated by manage:theme — controls whether the Custom Code tab
    // renders and whether posted values are honored (mirrors the site-wide custom-HTML/CSS gate).
    val canManageCode = ctx.permissions.canManageTheme(userId)
    // Infobox editor: one tab per template matched at this page's path/tags (empty if none), each
    // pre-filled from the current infobox JSON. A page can match more than one template — each gets its
    // own tab, titled plainly "Infobox" when it's the only one, or "Infobox - <name>" when there are
    // several, so the common single-template case stays uncluttered.
    val infoboxTabsRaw = ctx.infobox.formFor(siteId, wikiPath.pagePath, tags, infobox)
    val multiInfobox = infoboxTabsRaw.size > 1
    val infoboxTabs = infoboxTabsRaw.map { tab ->
        val name = tab["templateName"] as String
        tab + mapOf("tabLabel" to if (multiInfobox) "Infobox - $name" else "Infobox")
    }
    val infoboxMissingTabNames = infoboxTabs.filter { it["unfilled"] == true }.map { it["templateName"] as String }
    val infoboxFirstTabButtonId = infoboxTabs.firstOrNull()?.let { "pi-tab-infobox-${it["tabId"]}" }.orEmpty()
    // Leftover infobox data no longer mapped to any template applied here (see InfoboxService.hasOrphanedData).
    val infoboxHasOrphans = ctx.infobox.hasOrphanedData(siteId, wikiPath.pagePath, tags, infobox)
    // "Linked from": other pages whose content references this page's canonical URL. Computed on the fly
    // (rare; only meaningful for an existing page) so an editor can see what a move/rename would break.
    val backlinks = if (!isNew && pageId != null) {
        ctx.pages.backlinks(siteId, wikiPath.locale, wikiPath.pagePath, ctx.config.defaultLocale).map {
            mapOf(
                "title" to it.title,
                "url" to wikiViewUrl(it.locale, it.path),
                "locale" to it.locale,
                "path" to it.path,
            )
        }
    } else {
        emptyList()
    }
    // Per-page robots <select>: "Use site default" (empty) plus the standard directives.
    val metaRobotsOptions = listOf(
        mapOf("value" to "", "label" to "Use site default", "selected" to metaRobots.isNullOrBlank()),
    ) + com.wikikt.service.SettingsService.META_ROBOTS_OPTIONS.map { opt ->
        mapOf("value" to opt, "label" to opt, "selected" to (metaRobots == opt))
    }
    val applyDefault = when {
        staged?.publishAt != null -> "scheduled"
        staged != null -> "staged"
        else -> "now"
    }
    return mapOf(
        "csrfField" to call.csrfField(),
        "pageId" to pageId,
        "title" to title,
        "description" to (description ?: ""),
        "metaRobotsOptions" to metaRobotsOptions,
        "tagsText" to tags.joinToString(", "),
        "content" to content,
        "contentFormat" to contentFormat,
        "locale" to wikiPath.locale,
        "pagePath" to wikiPath.pagePath,
        "viewUrl" to wikiViewUrl(wikiPath.locale, wikiPath.pagePath),
        "saveUrl" to wikiEditUrl(wikiPath.locale, wikiPath.pagePath),
        "contentFormatMarkdown" to (contentFormat == "MARKDOWN"),
        "contentFormatHtml" to (contentFormat == "HTML"),
        "published" to published,
        "publishAt" to formatPublishAt(publishAt, formats.zone),
        "saved" to saved,
        "isNew" to isNew,
        "error" to error,
        // Staging: live pages get the "Apply" control; new/draft pages keep published+publishAt.
        "showPublishControls" to (isNew || !isLive),
        "showApply" to isLive,
        "hasStaged" to (staged != null),
        "stagedScheduled" to (staged?.publishAt != null),
        "stagedScheduledAt" to staged?.publishAt?.let { DateDisplay.format(it, formats) },
        "stagedPublishAtInput" to formatPublishAt(staged?.publishAt, formats.zone),
        "applyNowSel" to (applyDefault == "now"),
        "applyStagedSel" to (applyDefault == "staged"),
        "applyScheduledSel" to (applyDefault == "scheduled"),
        "username" to username,
        "loggedIn" to (userId != null),
        "canAdmin" to ctx.permissions.canAccessAdmin(userId),
        // Global default for the plain (monospace, no inline styling) editor view.
        "plainEditor" to ctx.settings.getBool(siteId, com.wikikt.service.SettingsService.EDITOR_PLAIN_VIEW),
        // Used by the editor's link-path autocomplete to build canonical view URLs.
        "defaultLocale" to ctx.config.defaultLocale,
        // Locale <select> for the Page Info (move) panel — enabled set plus this page's current locale.
        "localeOptions" to localeSelectOptions(ctx.settings.enabledLocales(siteId, ctx.config.defaultLocale), wikiPath.locale),
        // "Linked from" list (shown in Page Info, beside the move/path field).
        "backlinks" to backlinks,
        "hasBacklinks" to backlinks.isNotEmpty(),
        "backlinkCount" to backlinks.size,
        // Infobox tabs: one per matched template (empty hides the whole tab group).
        "infoboxTabs" to infoboxTabs,
        "hasInfoboxTabs" to infoboxTabs.isNotEmpty(),
        // Jump target for the General tab's nudge link — the first Infobox tab's button id.
        "infoboxFirstTabButtonId" to infoboxFirstTabButtonId,
        // Muted "leftover data" note on the General tab when stored data no longer maps to a template here.
        "infoboxHasOrphans" to infoboxHasOrphans,
        // Custom Code tab (per-page CSS/JS injected into <head> on view): gated by manage:theme. Values
        // are the raw stored text for the textareas; blank when none.
        "canManageCode" to canManageCode,
        "pageCustomCss" to (customCss ?: ""),
        "pageCustomJs" to (customJs ?: ""),
    ) + infoboxNudgeModel(infoboxMissingTabNames)
}

/**
 * Renders content live (no cache): expands {{fragment:key}} + Markdown → sanitized HTML, then resolves
 * per-locale asset refs. Used for staged previews, revisions, the editor live-preview, and format
 * conversion. Live page views instead serve the cached body via [renderCachedBody].
 */
private suspend fun io.ktor.server.application.ApplicationCall.renderContent(
    siteId: UInt,
    content: String,
    format: ContentFormat,
    locale: String,
    pagePath: String,
): String {
    val ctx = appContext
    return renderAssetRefs(ctx.renderCache.renderBody(siteId, content, format, locale, pagePath), locale, ctx.config.defaultLocale)
}

/** A live page's rendered body + infobox card, served from the render cache (rendered + stored on a
 *  miss), with the per-locale asset-ref pass applied fresh so image/alt changes are never stale. */
private suspend fun io.ktor.server.application.ApplicationCall.renderCachedBody(page: com.wikikt.model.PageRecord): Pair<String, String?> {
    val ctx = appContext
    val rendered = ctx.renderCache.getOrRender(page)
    val body = renderAssetRefs(rendered.body, page.locale, ctx.config.defaultLocale)
    val infobox = rendered.infoboxHtml?.let { renderAssetRefs(it, page.locale, ctx.config.defaultLocale) }
    return body to infobox
}

/** Renders an infobox card live (no cache) for a staged/preview version, with the asset-ref pass applied. */
private suspend fun io.ktor.server.application.ApplicationCall.renderInfoboxLive(
    siteId: UInt,
    path: String,
    tags: List<String>,
    infoboxJson: String?,
    locale: String,
): String? {
    val ctx = appContext
    val html = ctx.renderCache.infoboxFor(siteId, path, tags, infoboxJson) ?: return null
    return renderAssetRefs(html, locale, ctx.config.defaultLocale)
}

/** 301-redirects to a canonical locale-qualified URL (preserving the query string). Returns true. */
private suspend fun io.ktor.server.application.ApplicationCall.redirectCanonical(base: String): Boolean {
    val q = request.queryString()
    respondRedirect(if (q.isEmpty()) base else "$base?$q", permanent = true)
    return true
}

/** The sentinel an author puts in the alt position (`![{alt}](url)`) to request the asset's default alt.
 *  Curly braces (vs a `:shortcode:`) keep it clear of the emoji/icon namespace; `{alt}` has no leading dot
 *  so it never collides with the `{.class}` decoration syntax either. */
const val DEFAULT_ALT_TOKEN = "{alt}"

/**
 * Render-time pass over `<img>` references that resolves them against the **page's** locale:
 *  - A locale-relative asset src (no explicit locale segment, e.g. `/branding/logo.png`) is rewritten to
 *    the page's locale (`/<pageLocale>/branding/logo.png`) so each translation serves its own asset, with
 *    the bytes falling back to the default locale at serve time. An explicit `/<locale>/…` is left as-is.
 *  - The [DEFAULT_ALT_TOKEN] (`{alt}`) alt is replaced with the resolved asset's stored alt (empty if none).
 * Skips entirely for default-locale pages with no `{alt}` token, so the common case pays no cost.
 */
private suspend fun io.ktor.server.application.ApplicationCall.renderAssetRefs(
    html: String,
    pageLocale: String,
    defaultLocale: String,
): String {
    val needsAlt = html.contains(DEFAULT_ALT_TOKEN)
    val needsLocale = pageLocale != defaultLocale
    if (!needsAlt && !needsLocale) return html
    val doc = org.jsoup.Jsoup.parseBodyFragment(html)
    val imgs = doc.select("img")
    if (imgs.isEmpty()) return html
    val byRef = appContext.assets.list(siteId()).associateBy { com.wikikt.model.AssetRef(it.locale, it.path) }
    for (img in imgs) {
        val src = img.attr("src")
        val ref = appContext.assets.resolveLocalAssetUrl(src, defaultLocale) ?: continue
        val firstSeg = src.substringBefore('?').substringBefore('#').removePrefix("/").substringBefore('/')
        val explicitLocale = isLocaleSegment(firstSeg) && src.removePrefix("/").contains('/')
        // Locale-relative → bind to the page's locale (so a fr page serves the fr asset).
        val effective = if (needsLocale && !explicitLocale) {
            img.attr("src", "/$pageLocale/${ref.path}")
            com.wikikt.model.AssetRef(pageLocale, ref.path)
        } else {
            ref
        }
        if (needsAlt && img.attr("alt") == DEFAULT_ALT_TOKEN) {
            val alt = (byRef[effective] ?: byRef[com.wikikt.model.AssetRef(defaultLocale, effective.path)])?.altText
            img.attr("alt", alt.orEmpty())
        }
    }
    return doc.body().html()
}

/**
 * Model for the chrome-wrapped error page (error.hbs): the message/code plus the same header, sidebar
 * nav, and footer as a normal page, so any 404/403/413 stays navigable. Branding is injected globally.
 * The chrome lookups are best-effort — if they fail (e.g. mid-shutdown) we still render the bare message.
 */
internal suspend fun io.ktor.server.application.ApplicationCall.errorModel(message: String, code: Int): Map<String, Any?> {
    val chrome = runCatching {
        val ctx = appContext
        val userId = currentUserId()
        mapOf(
            "loggedIn" to (userId != null),
            "canAdmin" to ctx.permissions.canAccessAdmin(userId),
            "username" to userId?.let { ctx.users.findById(it)?.username },
            "canCreate" to ctx.permissions.canCreatePagesOnSite(userId, siteId()),
            // Error pages aren't locale-scoped; default the header search to the site locale.
            "searchLocale" to ctx.config.defaultLocale,
            "searchQ" to "",
        ) + navModel()
    }.getOrDefault(emptyMap())
    return mapOf("message" to message, "code" to code) + chrome
}

/**
 * Set by the wiki view handler when a missing page should render the rich, chrome-wrapped 404 instead of
 * the bare error page. Read by the global StatusPages NotFound handler (which owns the 404 status), so the
 * nice page survives — responding with MustacheContent from the route alone would be replaced by it.
 */
internal val NotFoundModelKey = io.ktor.util.AttributeKey<Map<String, Any?>>("wikiNotFoundModel")

/**
 * Builds the (one-level-nested) sidebar item models from the flat, ordered [items]: a depth-1 link
 * becomes a child of the nearest preceding top-level link; headers/dividers end the current group.
 * [activeTargets] are the URL forms of the current page — used to mark the active item and auto-expand
 * the group containing it.
 */
private fun buildNavItemModels(
    items: List<com.wikikt.model.NavItemRecord>,
    activeTargets: Set<String>,
): List<Map<String, Any?>> {
    fun linkModel(it: com.wikikt.model.NavItemRecord): MutableMap<String, Any?> = mutableMapOf(
        "isHeader" to false,
        "isDivider" to false,
        "label" to it.label,
        "icon" to it.icon,
        "hasIcon" to (it.icon != null),
        "target" to it.target,
        "active" to (it.target != null && it.target in activeTargets),
    )
    val out = mutableListOf<MutableMap<String, Any?>>()
    var parent: MutableMap<String, Any?>? = null
    for (it in items) {
        when {
            it.isDivider -> {
                out.add(mutableMapOf("isDivider" to true, "isHeader" to false))
                parent = null
            }
            it.isHeader -> {
                out.add(
                    mutableMapOf(
                        "isHeader" to true, "isDivider" to false, "label" to it.label,
                        "icon" to it.icon, "hasIcon" to (it.icon != null),
                    ),
                )
                parent = null
            }
            it.depth >= 1 && parent != null -> {
                @Suppress("UNCHECKED_CAST")
                val kids = parent!!["children"] as MutableList<Map<String, Any?>>
                kids.add(linkModel(it))
                parent!!["hasChildren"] = true
            }
            else -> {
                val m = linkModel(it)
                m["children"] = mutableListOf<Map<String, Any?>>()
                m["hasChildren"] = false
                out.add(m)
                parent = m
            }
        }
    }
    // Auto-expand a group when the current page is its parent link or one of its children.
    for (m in out) {
        if (m["hasChildren"] == true) {
            @Suppress("UNCHECKED_CAST")
            val kids = m["children"] as List<Map<String, Any?>>
            m["expanded"] = (m["active"] == true) || kids.any { it["active"] == true }
        }
    }
    return out
}

/** The current page's target forms a nav link may use (unprefixed and locale-qualified). */
private fun activeNavTargets(locale: String, pagePath: String): Set<String> =
    setOf("/$pagePath", "/$locale/$pagePath")

/**
 * The wiki sidebar model shared by the page view and every chrome page (via partials/sidebar.hbs),
 * scoped to [pagePath] in [locale]. Encodes the site's navigation mode (Administration > Navigation):
 * the static curated menu (`static`), the auto-built drill-down site tree (`tree`), both with a
 * per-visitor switch remembered in the `wk-nav-view` cookie (`both`), or no sidebar (`none`). Pass ""
 * for pages not tied to a path (search, tag, login) to get the default (unscoped) menu and a root tree.
 */
internal suspend fun io.ktor.server.application.ApplicationCall.navModel(
    pagePath: String = "",
    locale: String = appContext.config.defaultLocale,
): Map<String, Any?> {
    val ctx = appContext
    val siteId = siteId()
    val userId = currentUserId()
    val mode = ctx.settings.navMode(siteId)
    val staticMode = mode == "static" || mode == "both"
    val treeMode = mode == "tree" || mode == "both"

    // --- Static curated menu ---
    val activeTargets = if (pagePath.isNotEmpty()) activeNavTargets(locale, pagePath) else emptySet()
    val navItems = if (staticMode) buildNavItemModels(ctx.nav.itemsForPath(siteId, pagePath), activeTargets) else emptyList()
    val canManageNav = staticMode && ctx.permissions.canManageNavigation(userId)
    // The static pane shows when it has items OR the viewer can edit the menu (to add the first item).
    val staticHasContent = staticMode && (navItems.isNotEmpty() || canManageNav)

    // --- Auto-built site tree (locale- and permission-filtered) ---
    // Only queried when a tree is actually shown, so the default (static) install pays nothing extra.
    var treeJson = "[]"
    var treeHasContent = false
    if (treeMode) {
        val pages = ctx.pages.list(siteId).filter { it.locale == locale }
        val roots = com.wikikt.service.SiteNavTree.build(ctx.permissions.readablePages(userId, pages))
        treeHasContent = roots.isNotEmpty()
        // Escape "</" so a page title can't close the embedding <script> (same guard as the asset browser).
        treeJson = kotlinx.serialization.json.Json.encodeToString(roots).replace("</", "<\\/")
    }

    // --- Which pane(s) show, and the initial view (both-mode remembers the visitor's choice per browser) ---
    val bothAvailable = staticHasContent && treeHasContent
    val showToggle = mode == "both" && bothAvailable
    val treeActive = when {
        treeHasContent && !staticHasContent -> true                // the tree is the only pane
        showToggle -> request.cookies["wk-nav-view"] == "tree"     // both panes: cookie choice, default static
        else -> false
    }
    val staticActive = staticHasContent && !treeActive

    return mapOf(
        "navMode" to mode,
        "showSidebar" to (staticHasContent || treeHasContent),
        "navShowStatic" to staticHasContent,
        "navShowTree" to treeHasContent,
        "navShowToggle" to showToggle,
        "navStaticActive" to staticActive,
        "navTreeActive" to treeActive,
        "navItems" to navItems,
        "hasNav" to navItems.isNotEmpty(),
        "canManageNav" to canManageNav,
        "editMenuUrl" to "/a/navigation/for/$pagePath",
        "navTreeJson" to treeJson,
        "navTreePath" to pagePath,
    )
}

/**
 * Model for the chrome-wrapped 404 (page/not-found.hbs): the same header/sidebar/footer as a real page,
 * plus a "create this page" offer when the viewer may create pages. Branding keys are injected globally.
 */
private suspend fun io.ktor.server.routing.RoutingContext.notFoundModel(
    siteId: UInt,
    wikiPath: WikiPathRequest,
    userId: UInt?,
): Map<String, Any?> {
    val ctx = call.appContext
    val username = userId?.let { ctx.users.findById(it)?.username }
    return mapOf(
        "locale" to wikiPath.locale,
        "pagePath" to wikiPath.pagePath,
        "editUrl" to wikiEditUrl(wikiPath.locale, wikiPath.pagePath),
        "canCreate" to ctx.permissions.canCreatePagesOnSite(userId, siteId),
        "loggedIn" to (userId != null),
        "canAdmin" to ctx.permissions.canAccessAdmin(userId),
        "username" to username,
        // Header search box defaults to this locale.
        "searchLocale" to wikiPath.locale,
        "searchQ" to "",
    ) + call.navModel(wikiPath.pagePath, wikiPath.locale)
}

/** Parses a `<input type="datetime-local">` value (interpreted in the viewer's [zone]) to epoch millis. */
internal fun parsePublishAt(value: String, zone: java.time.ZoneId): Long? = DateDisplay.parseInput(value, zone)

/** Formats epoch millis to a `datetime-local` value in the viewer's [zone], or "" if null. */
private fun formatPublishAt(millis: Long?, zone: java.time.ZoneId): String = DateDisplay.toInput(millis, zone)
