package com.wikikt.routing

import com.wikikt.appContext
import com.wikikt.siteId
import com.wikikt.auth.CSRF_HEADER
import com.wikikt.auth.LoginThrottle
import com.wikikt.auth.PasswordPolicy
import com.wikikt.auth.UserSession
import com.wikikt.auth.generateCsrfToken
import com.wikikt.auth.isApiCsrfValid
import com.wikikt.model.parseId
import com.wikikt.model.AssetPickerDto
import com.wikikt.model.CreateGroupRequest
import com.wikikt.model.CreatePageAliasRequest
import com.wikikt.model.CreatePageRequest
import com.wikikt.model.CreateUserRequest
import com.wikikt.model.LoginRequest
import com.wikikt.model.UpdateGroupRequest
import com.wikikt.model.UpdatePageRequest
import com.wikikt.model.UpdateUserRequest
import com.wikikt.model.toDto
import com.wikikt.service.PageService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set

/**
 * CSRF guard for state-changing API calls. Returns true to proceed; on failure it writes a 403
 * and returns false, so callers should `return@<verb>` immediately. Cookie-less requests (login,
 * or a future API-key client) pass through — only session-cookie auth is CSRF-prone.
 */
private suspend fun io.ktor.server.application.ApplicationCall.requireApiCsrf(): Boolean {
    if (isApiCsrfValid()) return true
    respond(HttpStatusCode.Forbidden, mapOf("error" to "Missing or invalid $CSRF_HEADER header"))
    return false
}

// Auth request bodies (login credentials) are a few hundred bytes at most; reject anything larger up
// front so an anonymous caller can't force a big parse/buffer before the throttle even runs. This
// guards the declared Content-Length; a chunked body without one still streams, but the production
// reverse proxy caps request size and form endpoints are already bounded by Ktor's form-field limit.
private const val MAX_AUTH_BODY_BYTES = 4L * 1024

private suspend fun io.ktor.server.application.ApplicationCall.enforceAuthBodyCap(): Boolean {
    val length = request.contentLength()
    if (length != null && length > MAX_AUTH_BODY_BYTES) {
        respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Request body too large"))
        return false
    }
    return true
}

fun Route.configureApiRouting() {
    route("/u/v1") {
        post("/auth/login") {
            if (!call.enforceAuthBodyCap()) return@post
            val ctx = call.appContext
            val request = call.receive<LoginRequest>()
            // Same brute-force throttle as the form login — without it this endpoint would be the
            // unthrottled way to guess passwords.
            val clientKey = call.request.origin.remoteHost
            if (LoginThrottle.isLockedOut(clientKey, request.username)) {
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many failed attempts. Try again shortly."))
                return@post
            }
            val user = ctx.users.authenticate(request.username, request.password)
                ?: run {
                    LoginThrottle.recordFailure(clientKey, request.username)
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid credentials"))
                    return@post
                }
            LoginThrottle.recordSuccess(clientKey, request.username)
            val csrfToken = generateCsrfToken()
            val sessionId = ctx.sessions.create(user.id, ctx.config.session.maxAgeSeconds * 1000)
            call.sessions.set(UserSession(sessionId, csrfToken))
            // Return the CSRF token so a (same-origin) JS client can echo it on later mutations.
            call.response.headers.append(CSRF_HEADER, csrfToken)
            call.respond(ctx.users.toDto(user))
        }

        get("/auth/me") {
            val ctx = call.appContext
            val userId = call.currentUserId()
                ?: run {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@get
                }
            val user = ctx.users.findById(userId)
                ?: run {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@get
                }
            call.sessions.get<UserSession>()?.csrfToken?.takeIf { it.isNotEmpty() }?.let {
                call.response.headers.append(CSRF_HEADER, it)
            }
            call.respond(ctx.users.toDto(user))
        }

        // Live search (powers the header dropdown). locale absent/blank = all locales.
        get("/search") {
            val ctx = call.appContext
            val siteId = call.siteId()
            val userId = call.currentUserId()
            val q = call.request.queryParameters["q"]?.trim().orEmpty()
            val locale = call.request.queryParameters["locale"]?.takeIf { it.isNotBlank() }
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 7).coerceIn(1, 50)
            val results = if (q.length >= PageService.MIN_SEARCH_LENGTH) {
                searchResults(siteId, ctx, q, locale, limit, userId)
            } else {
                emptyList()
            }
            call.respond(results)
        }

        // Lightweight asset list powering the asset-browser picker (insert image, logo/favicon, …).
        // Gated by the same view baseline that serves the asset bytes.
        get("/assets") {
            val ctx = call.appContext
            val siteId = call.siteId()
            val userId = call.currentUserId()
            if (!ctx.permissions.check(userId, com.wikikt.service.AccessResolver.Perm.READ_ASSETS, siteId, ctx.config.defaultLocale, "")) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            // Filter per-asset: the coarse gate above only proves read:assets somewhere, but a DENY on a
            // subtree must still hide those assets' metadata (paths/filenames), not just their bytes.
            val visible = ctx.permissions.readableAssets(userId, siteId, ctx.assets.list(siteId))
            call.respond(
                visible.map {
                    AssetPickerDto(
                        id = it.id.toString(),
                        locale = it.locale,
                        path = it.path,
                        url = wikiViewUrl(it.locale, it.path),
                        mime = it.mime,
                        sizeBytes = it.sizeBytes,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt,
                        hasAlt = !it.altText.isNullOrBlank(),
                    )
                },
            )
        }

        // Lightweight fragment list powering the editor's fragment-include affordance (the icon after
        // each {{fragment:key}} that opens it in its editor). Gated by manage:pages — the same baseline
        // that guards the fragment admin editor those icons link to, so non-managers see no icons.
        get("/fragments") {
            val ctx = call.appContext
            val userId = call.currentUserId()
            if (!ctx.permissions.canManagePages(userId)) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            call.respond(
                ctx.fragments.list(call.siteId()).map {
                    com.wikikt.model.FragmentPickerDto(
                        id = it.id.toString(),
                        locale = it.locale,
                        key = it.key,
                        title = it.title,
                    )
                },
            )
        }

        // All distinct tags in use — powers the editor's tag autocomplete. Same view baseline as /assets.
        get("/tags") {
            val ctx = call.appContext
            val siteId = call.siteId()
            val userId = call.currentUserId()
            if (!ctx.permissions.check(userId, com.wikikt.service.AccessResolver.Perm.READ_PAGES, siteId, ctx.config.defaultLocale, "")) {
                call.respond(HttpStatusCode.Forbidden)
                return@get
            }
            call.respond(ctx.pages.allTags(siteId))
        }

        route("/users") {
            get {
                if (!call.requireManageUsers()) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                val ctx = call.appContext
                call.respond(ctx.users.list().map { ctx.users.toDto(it) })
            }

            post {
                if (!call.requireApiCsrf()) return@post
                if (!call.requireManageUsers()) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }
                val ctx = call.appContext
                val request = call.receive<CreateUserRequest>()
                PasswordPolicy.validate(request.password, ctx.config.minPasswordLength)?.let {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to it))
                    return@post
                }
                val user = try {
                    ctx.users.create(request, actorIsRoot = ctx.permissions.isRoot(call.currentUserId()))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
                    return@post
                }
                call.respond(HttpStatusCode.Created, ctx.users.toDto(user))
            }

            get("/{id}") {
                if (!call.requireManageUsers()) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                val id = call.parameters["id"]?.let(::parseId) ?: run {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                val ctx = call.appContext
                val user = ctx.users.findById(id) ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                call.respond(ctx.users.toDto(user))
            }

            put("/{id}") {
                if (!call.requireApiCsrf()) return@put
                if (!call.requireManageUsers()) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@put
                }
                val id = call.parameters["id"]?.let(::parseId) ?: run {
                    call.respond(HttpStatusCode.BadRequest)
                    return@put
                }
                val ctx = call.appContext
                val request = call.receive<UpdateUserRequest>()
                request.password?.let { pw ->
                    PasswordPolicy.validate(pw, ctx.config.minPasswordLength)?.let {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to it))
                        return@put
                    }
                }
                val user = try {
                    ctx.users.update(id, request, actorIsRoot = ctx.permissions.isRoot(call.currentUserId()))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to e.message))
                    return@put
                } ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@put
                }
                call.respond(ctx.users.toDto(user))
            }

            delete("/{id}") {
                if (!call.requireApiCsrf()) return@delete
                if (!call.requireManageUsers()) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@delete
                }
                val id = call.parameters["id"]?.let(::parseId) ?: run {
                    call.respond(HttpStatusCode.BadRequest)
                    return@delete
                }
                if (!call.appContext.users.delete(id)) {
                    call.respond(HttpStatusCode.NotFound)
                    return@delete
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }

        route("/groups") {
            get {
                if (!call.requireManageGroups()) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                call.respond(call.appContext.groups.list().map { it.toDto() })
            }

            post {
                if (!call.requireApiCsrf()) return@post
                if (!call.requireManageGroups()) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }
                val request = call.receive<CreateGroupRequest>()
                call.respond(HttpStatusCode.Created, call.appContext.groups.create(request).toDto())
            }

            get("/{id}") {
                if (!call.requireManageGroups()) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                val id = call.parameters["id"]?.let(::parseId) ?: run {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                val group = call.appContext.groups.findById(id)?.toDto() ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                call.respond(group)
            }

            put("/{id}") {
                if (!call.requireApiCsrf()) return@put
                if (!call.requireManageGroups()) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@put
                }
                val id = call.parameters["id"]?.let(::parseId) ?: run {
                    call.respond(HttpStatusCode.BadRequest)
                    return@put
                }
                val request = call.receive<UpdateGroupRequest>()
                val group = call.appContext.groups.update(id, request)?.toDto() ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@put
                }
                call.respond(group)
            }

            delete("/{id}") {
                if (!call.requireApiCsrf()) return@delete
                if (!call.requireManageGroups()) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@delete
                }
                val id = call.parameters["id"]?.let(::parseId) ?: run {
                    call.respond(HttpStatusCode.BadRequest)
                    return@delete
                }
                try {
                    if (!call.appContext.groups.delete(id)) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respond(HttpStatusCode.NoContent)
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                }
            }
        }

        route("/pages") {
            get {
                val ctx = call.appContext
                val userId = call.currentUserId()
                // Enumerating the whole site is a management/API operation, not a public read: gate it so
                // an anonymous caller can't force an unbounded full-site load. (Results are still
                // per-viewer filtered below, so it never widens what a caller can see.)
                if (!ctx.permissions.canManagePages(userId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                val siteId = call.siteId()
                // filterViewable resolves the caller's permissions ONCE for the whole list (not per page).
                val pages = ctx.permissions.filterViewable(userId, ctx.pages.list(siteId)) { it }
                // toDto() (no ACL args) omits viewAcl/editAcl: those expose internal user/group ids and
                // are only for management, not for a content-read response.
                call.respond(pages.map { it.toDto() })
            }

            // Lightweight list for link-path autocomplete in the editor (no page content).
            get("/paths") {
                val ctx = call.appContext
                val userId = call.currentUserId()
                // Editor-only feature: gate it so an anonymous caller can't force a whole-site list load.
                // Non-editors don't use the editor, so they lose nothing.
                if (!ctx.permissions.canCreatePages(userId)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                val siteId = call.siteId()
                val pages = ctx.permissions.filterViewable(userId, ctx.pages.listLinkTargets(siteId)) { it }
                call.respond(
                    pages.map {
                        com.wikikt.model.PagePathDto(locale = it.locale, path = it.path, title = it.title)
                    },
                )
            }

            post {
                if (!call.requireApiCsrf()) return@post
                val ctx = call.appContext
                val siteId = call.siteId()
                val userId = call.currentUserId()
                val request = call.receive<CreatePageRequest>()
                // write:pages must be granted for the exact target site + path.
                if (!ctx.permissions.check(userId, com.wikikt.service.AccessResolver.Perm.WRITE_PAGES, siteId, request.locale, request.path)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }
                // Per-page custom code is unsanitized (stored script/style), so setting it needs manage:theme.
                if ((request.customCss != null || request.customJs != null) && !ctx.permissions.canManageTheme(userId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "manage:theme required to set custom code"))
                    return@post
                }
                val page = ctx.pages.create(siteId, request, userId)
                call.respond(HttpStatusCode.Created, ctx.pages.toDto(page))
            }

            get("/by-path") {
                val ctx = call.appContext
                val siteId = call.siteId()
                val locale = call.request.queryParameters["locale"] ?: ctx.config.defaultLocale
                val path = call.request.queryParameters["path"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path query parameter required"))
                    return@get
                }
                val page = ctx.pages.resolveByPath(siteId, locale, path) ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val userId = call.currentUserId()
                if (!ctx.permissions.canViewPage(userId, page)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                call.respond(page.toDto()) // no ACLs in a read response (see GET /pages)
            }

            get("/{id}") {
                val ctx = call.appContext
                val id = call.parameters["id"]?.let(::parseId) ?: run {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                val page = ctx.pages.findById(id) ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val userId = call.currentUserId()
                if (!ctx.permissions.canViewPage(userId, page)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@get
                }
                call.respond(page.toDto()) // no ACLs in a read response (see GET /pages)
            }

            put("/{id}") {
                if (!call.requireApiCsrf()) return@put
                val ctx = call.appContext
                val id = call.parameters["id"]?.let(::parseId) ?: run {
                    call.respond(HttpStatusCode.BadRequest)
                    return@put
                }
                val existing = ctx.pages.findById(id) ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@put
                }
                val userId = call.currentUserId()
                if (!ctx.permissions.canEditPage(userId, existing)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@put
                }
                val request = call.receive<UpdatePageRequest>()
                // Per-page custom code is unsanitized (stored script/style), so changing it needs manage:theme.
                if ((request.customCss != null || request.customJs != null) && !ctx.permissions.canManageTheme(userId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "manage:theme required to set custom code"))
                    return@put
                }
                val page = ctx.pages.update(id, request, userId) ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@put
                }
                call.respond(ctx.pages.toDto(page))
            }

            delete("/{id}") {
                if (!call.requireApiCsrf()) return@delete
                val ctx = call.appContext
                val id = call.parameters["id"]?.let(::parseId) ?: run {
                    call.respond(HttpStatusCode.BadRequest)
                    return@delete
                }
                val existing = ctx.pages.findById(id) ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@delete
                }
                val userId = call.currentUserId()
                // Deleting requires delete:pages on the page's own site + path (so a cross-site id
                // can't be deleted without a rule granting it there).
                if (!ctx.permissions.check(userId, com.wikikt.service.AccessResolver.Perm.DELETE_PAGES, existing.siteId, existing.locale, existing.path)) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@delete
                }
                if (!ctx.pages.delete(id)) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        post("/page-aliases") {
            if (!call.requireApiCsrf()) return@post
            val ctx = call.appContext
            val userId = call.currentUserId()
            if (!ctx.permissions.canCreatePagesOnSite(userId, call.siteId())) {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
            val request = call.receive<CreatePageAliasRequest>()
            call.respond(HttpStatusCode.Created, ctx.pages.createAlias(request))
        }
    }
}
