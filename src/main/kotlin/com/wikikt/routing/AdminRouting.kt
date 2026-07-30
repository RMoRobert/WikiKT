package com.wikikt.routing

import com.wikikt.appContext
import com.wikikt.siteId
import com.wikikt.adminSiteId
import com.wikikt.auth.PasswordPolicy
import com.wikikt.auth.csrfField
import com.wikikt.model.CreateGroupRequest
import com.wikikt.model.CreateUserRequest
import com.wikikt.model.InfoboxFieldDef
import com.wikikt.model.RuleEffect
import com.wikikt.model.RuleMatchType
import com.wikikt.model.UpdateGroupRequest
import com.wikikt.model.UpdateUserRequest
import com.wikikt.model.toDto
import com.wikikt.service.InfoboxService
import com.wikikt.service.SafeRegex
import com.wikikt.service.UpdateCheck
import com.wikikt.service.UpdateService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import com.wikikt.auth.AdminSiteSession
import com.wikikt.service.SiteDeleteResult
import com.wikikt.model.parseId

fun Route.configureAdminRouting() {
    route("/a") {
        get {
            if (!call.requireManageUsers() && !call.requireManageGroups() && !call.requireManagePages()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("admin/index.hbs", call.dashboardModel()))
        }

        // --- Sites: multiple sites on one instance, each reached by hostname (one catch-all). Managing
        //     which site's content/settings you edit is the session-backed "site switcher". ---
        get("/sites") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@get }
            call.respond(MustacheContent("admin/sites.hbs", call.sitesModel(saved = call.request.queryParameters["saved"] != null)))
        }

        get("/sites/new") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@get }
            call.respond(MustacheContent("admin/site-edit.hbs", call.siteEditModel(editId = null)))
        }

        get("/sites/{id}/edit") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@get }
            val id = call.parameters["id"]?.let(::parseId) ?: return@get call.respond(HttpStatusCode.BadRequest)
            if (call.appContext.sites.byId(id) == null) return@get call.respond(HttpStatusCode.NotFound)
            call.respond(MustacheContent("admin/site-edit.hbs", call.siteEditModel(editId = id)))
        }

        post("/sites") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@post }
            call.handleSiteSave(editId = null)
        }

        post("/sites/{id}") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@post }
            val id = call.parameters["id"]?.let(::parseId) ?: return@post call.respond(HttpStatusCode.BadRequest)
            call.handleSiteSave(editId = id)
        }

        post("/sites/{id}/delete") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@post }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: return@post call.respond(HttpStatusCode.BadRequest)
            val result = call.appContext.sites.delete(id)
            call.respond(MustacheContent("admin/sites.hbs", call.sitesModel(deleteResult = result)))
        }

        // Site switcher: store which site the admin console is managing (redirect back to the referrer).
        post("/sites/select") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@post }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            params["siteId"]?.let(::parseId)?.let { id ->
                if (call.appContext.sites.byId(id) != null) call.sessions.set(AdminSiteSession(id))
            }
            call.respondRedirect(params["return"]?.takeIf { it.startsWith("/a") } ?: "/a")
        }

        // --- Site settings, split across sidebar pages: General / Appearance / Locale. Each page
        //     renders from the shared settingsModel() and its POST updates only its own fields. ---
        get("/settings") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("admin/settings-general.hbs", call.settingsModel()))
        }

        post("/settings") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val siteId = call.adminSiteId()
            val ctx = call.appContext
            val s = com.wikikt.service.SettingsService
            // Checkbox: present (any value) = on, absent = off.
            ctx.settings.setBool(siteId, s.EDITOR_PLAIN_VIEW, params["editorPlainView"] != null)
            // Branding: stored trimmed; empty clears (falls back to the default site name).
            ctx.settings.set(siteId, s.SITE_NAME, params["siteName"].orEmpty().trim())
            // Footer: org + license build the default footer; the override (Markdown) replaces it. The
            // override has no dedicated form field, so only update it when the param is actually present
            // (else a normal save would silently clear it).
            ctx.settings.set(siteId, s.SITE_ORG_NAME, params["siteOrgName"].orEmpty().trim())
            ctx.settings.set(siteId, s.SITE_CONTENT_LICENSE, params["siteContentLicense"].orEmpty().trim())
            params["siteFooterOverride"]?.let { ctx.settings.set(siteId, s.SITE_FOOTER_OVERRIDE, it.trim()) }
            // SEO defaults: description used when a page sets none; robots must be a known directive.
            ctx.settings.set(siteId, s.SITE_DESCRIPTION, params["siteDescription"].orEmpty().trim())
            val robots = params["siteMetaRobots"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_META_ROBOTS, if (robots in s.META_ROBOTS_OPTIONS) robots else "")
            call.respond(MustacheContent("admin/settings-general.hbs", call.settingsModel(saved = true)))
        }

        post("/settings/reindex") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            call.appContext.searchIndex.reindexAll(call.adminSiteId())
            call.respond(MustacheContent("admin/settings-general.hbs", call.settingsModel(reindexed = true)))
        }

        // --- Self-service registration (Authentication section). Per-site, so it keeps the site-settings
        //     (manage:groups) gate the switcher relies on; distinct page from General for discoverability. ---
        get("/registration") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("admin/registration.hbs", call.registrationModel()))
        }

        post("/registration") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val siteId = call.adminSiteId()
            val ctx = call.appContext
            val s = com.wikikt.service.SettingsService
            // The default group is stored only when it names a real group; blank clears it (new accounts
            // then get just the implicit read-only "User" group).
            ctx.settings.setBool(siteId, s.REGISTRATION_ENABLED, params["registrationEnabled"] != null)
            ctx.settings.setBool(siteId, s.REGISTRATION_REQUIRE_APPROVAL, params["registrationRequireApproval"] != null)
            ctx.settings.set(siteId, s.REGISTRATION_ALLOWED_DOMAINS, params["registrationAllowedDomains"].orEmpty().trim())
            // Unless the actor is root, the default must NOT be a system (root-bearing) group — otherwise a
            // delegated manage:groups admin could point registration at the Admin group and mint root for
            // every registrant. Mirrors the create()/update() system-group guard (and UserService.register
            // enforces it again at the sink).
            val defaultGroup = params["registrationDefaultGroup"].orEmpty().trim()
            val actorIsRoot = ctx.permissions.isRoot(call.currentUserId())
            val systemGroups = ctx.groups.systemGroupIds()
            val validGroup = defaultGroup.toUIntOrNull()
                ?.takeIf { gid -> ctx.groups.findById(gid) != null }
                ?.takeIf { gid -> actorIsRoot || gid !in systemGroups }
            ctx.settings.set(siteId, s.REGISTRATION_DEFAULT_GROUP, validGroup?.toString().orEmpty())
            call.respond(MustacheContent("admin/registration.hbs", call.registrationModel(saved = true)))
        }

        get("/settings/appearance") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("admin/settings-appearance.hbs", call.settingsModel()))
        }

        post("/settings/appearance") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val siteId = call.adminSiteId()
            val ctx = call.appContext
            val s = com.wikikt.service.SettingsService
            // Brand colors are contrast-validated BEFORE anything is saved: a well-formed color that
            // fails WCAG AA against its body background (light on white, dark on the dark surface) re-
            // renders the form with an error and the submitted values, persisting nothing. Malformed hex
            // is treated as blank/cleared as elsewhere, so only a valid-but-low-contrast choice trips this.
            val brandLight = params["siteBrandColor"].orEmpty().trim().let { if (it.matches(HEX_COLOR)) it else "" }
            val brandDark = params["siteBrandColorDark"].orEmpty().trim().let { if (it.matches(HEX_COLOR)) it else "" }
            s.brandColorContrastError(brandLight, brandDark)?.let { err ->
                call.respond(
                    MustacheContent(
                        "admin/settings-appearance.hbs",
                        call.settingsModel() + mapOf(
                            "appearanceError" to err,
                            // Echo what the admin typed (not the cleared/stored value) so they can correct it.
                            "siteBrandColorValue" to params["siteBrandColor"].orEmpty().trim(),
                            "siteBrandColorDarkValue" to params["siteBrandColorDark"].orEmpty().trim(),
                        ),
                    ),
                )
                return@post
            }
            // Default color mode: one of the known modes, else fall back to the default.
            val theme = params["siteTheme"].orEmpty().trim()
            ctx.settings.set(siteId, s.APPEARANCE_THEME, if (theme in s.THEME_OPTIONS) theme else s.DEFAULT_THEME)
            ctx.settings.setBool(siteId, s.APPEARANCE_SHOW_THEME_PICKER, params["showThemePicker"] != null)
            // Custom head/body HTML: stored verbatim (admin-only; injected raw into the page).
            ctx.settings.set(siteId, s.APPEARANCE_HEAD_HTML, params["headHtml"].orEmpty().take(s.MAX_INJECT_HTML_LENGTH))
            ctx.settings.set(siteId, s.APPEARANCE_BODY_HTML, params["bodyHtml"].orEmpty().take(s.MAX_INJECT_HTML_LENGTH))
            // Logo must be one of the uploaded image assets — enforce selection rather than a free URL.
            val logo = params["siteLogoUrl"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_LOGO_URL, if (logo.isNotEmpty() && call.imageAssetUrls().any { it.first == logo }) logo else "")
            // Favicon: an uploaded image asset, or empty to use the bundled default (/favicon.svg).
            val favicon = params["siteFaviconUrl"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_FAVICON_URL, if (favicon.isNotEmpty() && call.imageAssetUrls().any { it.first == favicon }) favicon else "")
            // Brand colors were hex-validated and contrast-checked above; persist those normalized values.
            ctx.settings.set(siteId, s.SITE_BRAND_COLOR, brandLight)
            ctx.settings.set(siteId, s.SITE_BRAND_COLOR_DARK, brandDark)
            val headerColor = params["siteHeaderColor"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_HEADER_COLOR, if (headerColor.matches(HEX_COLOR)) headerColor else "")
            val headerColorDark = params["siteHeaderColorDark"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_HEADER_COLOR_DARK, if (headerColorDark.matches(HEX_COLOR)) headerColorDark else "")
            val sidebarColor = params["siteSidebarColor"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_SIDEBAR_COLOR, if (sidebarColor.matches(HEX_COLOR)) sidebarColor else "")
            val sidebarColorDark = params["siteSidebarColorDark"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_SIDEBAR_COLOR_DARK, if (sidebarColorDark.matches(HEX_COLOR)) sidebarColorDark else "")
            val sidebarLineColor = params["siteSidebarHeaderLineColor"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_SIDEBAR_HEADER_LINE_COLOR, if (sidebarLineColor.matches(HEX_COLOR)) sidebarLineColor else "")
            val sidebarLineColorDark = params["siteSidebarHeaderLineColorDark"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_SIDEBAR_HEADER_LINE_COLOR_DARK, if (sidebarLineColorDark.matches(HEX_COLOR)) sidebarLineColorDark else "")
            val navHeadingColor = params["siteNavHeadingColor"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_NAV_HEADING_COLOR, if (navHeadingColor.matches(HEX_COLOR)) navHeadingColor else "")
            val navHeadingColorDark = params["siteNavHeadingColorDark"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_NAV_HEADING_COLOR_DARK, if (navHeadingColorDark.matches(HEX_COLOR)) navHeadingColorDark else "")
            val searchBoxTheme = params["searchBoxTheme"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_SEARCH_BOX_THEME, if (searchBoxTheme in s.SEARCH_BOX_THEME_OPTIONS) searchBoxTheme else "")
            val headingLineColor = params["siteHeadingLineColor"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_HEADING_LINE_COLOR, if (headingLineColor.matches(HEX_COLOR)) headingLineColor else "")
            // Table of contents: must be a known value (else clear/fall back to the default).
            val tocMode = params["tocMode"].orEmpty().trim()
            ctx.settings.set(siteId, s.SITE_TOC_MODE, if (tocMode in s.TOC_MODE_OPTIONS) tocMode else "")
            // Typography: font is a known preset key (else fall back to the default); the custom family
            // and Custom CSS are sanitized on output (SettingsService), so store the trimmed input here.
            val fontKeys = s.FONT_PRESETS.map { it.key }.toSet()
            val bodyFont = params["bodyFont"].orEmpty().trim()
            ctx.settings.set(siteId, s.APPEARANCE_BODY_FONT, if (bodyFont in fontKeys) bodyFont else s.DEFAULT_BODY_FONT)
            val headingFont = params["headingFont"].orEmpty().trim()
            ctx.settings.set(siteId, s.APPEARANCE_HEADING_FONT, if (headingFont in fontKeys) headingFont else s.DEFAULT_HEADING_FONT)
            ctx.settings.set(siteId, s.APPEARANCE_BODY_FONT_CUSTOM, params["bodyFontCustom"].orEmpty().trim().take(200))
            ctx.settings.set(siteId, s.APPEARANCE_HEADING_FONT_CUSTOM, params["headingFontCustom"].orEmpty().trim().take(200))
            val baseSize = params["baseFontSize"]?.toIntOrNull()?.coerceIn(12, 24) ?: s.DEFAULT_BASE_FONT_SIZE
            ctx.settings.set(siteId, s.APPEARANCE_BASE_FONT_SIZE, baseSize.toString())
            // Emoji webfont: affects only the font stacks in <head>, not the stored HTML, so no render bump.
            ctx.settings.setBool(siteId, s.APPEARANCE_EMOJI_FONT, params["emojiFont"] != null)
            ctx.settings.set(siteId, s.APPEARANCE_CUSTOM_CSS, params["customCss"].orEmpty().take(s.MAX_CUSTOM_CSS_LENGTH))
            call.respond(MustacheContent("admin/settings-appearance.hbs", call.settingsModel(saved = true)))
        }

        get("/settings/locale") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("admin/settings-locale.hbs", call.settingsModel()))
        }

        post("/settings/locale") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val siteId = call.adminSiteId()
            val ctx = call.appContext
            val s = com.wikikt.service.SettingsService
            // Additional locales: the checklist submits one `siteLocales` value per ticked box. Keep only
            // valid, canonicalized codes; drop the default (always implied) and de-dupe.
            val default = com.wikikt.model.normalizeLocale(ctx.config.defaultLocale) ?: ctx.config.defaultLocale
            val locales = params.getAll("siteLocales").orEmpty()
                .mapNotNull { com.wikikt.model.normalizeLocale(it) }
                .filter { it != default }
                .distinct()
            ctx.settings.set(siteId, s.SITE_LOCALES, locales.joinToString(", "))
            // When on, unprefixed paths (/home) redirect to the primary locale (/en/home); off = serve as-is.
            ctx.settings.setBool(siteId, s.LOCALE_FORCE_PREFIX, params["localeForcePrefix"] != null)
            call.respond(MustacheContent("admin/settings-locale.hbs", call.settingsModel(saved = true)))
        }

        get("/settings/rendering") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("admin/settings-rendering.hbs", call.settingsModel()))
        }

        post("/settings/rendering") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val siteId = call.adminSiteId()
            val ctx = call.appContext
            val s = com.wikikt.service.SettingsService
            // Checkbox: present (any value) = on, absent = off. Takes effect on the next page render.
            ctx.settings.setBool(siteId, s.RENDER_ALLOW_IFRAMES, params["renderAllowIframes"] != null)
            ctx.settings.setBool(siteId, s.RENDER_ALLOW_STYLE, params["renderAllowStyle"] != null)
            ctx.settings.setBool(siteId, s.RENDER_AUTOLINK, params["renderAutolink"] != null)
            ctx.settings.setBool(siteId, s.RENDER_LINE_BREAKS, params["renderLineBreaks"] != null)
            // External-link icon mode (off | site | instance); unknown values fall back to off on read.
            val externalMode = params["renderExternalLinkIcon"]?.takeIf { it in s.EXTERNAL_LINK_MODE_OPTIONS } ?: s.DEFAULT_EXTERNAL_LINK_ICON
            ctx.settings.set(siteId, s.RENDER_EXTERNAL_LINK_ICON, externalMode)
            // Invalidate every cached page render so the new options take effect on the next view.
            ctx.settings.bumpRenderEpoch(siteId)
            call.respond(MustacheContent("admin/settings-rendering.hbs", call.settingsModel(saved = true)))
        }

        // "Re-render all pages": eagerly rebuild every page's cached render now (rather than letting each
        // refresh lazily on next view). Useful after bulk external edits, or just to pay the cost upfront.
        post("/settings/rendering/rerender") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val count = call.appContext.renderCache.rebuildAll(call.adminSiteId())
            call.respond(MustacheContent("admin/settings-rendering.hbs", call.settingsModel(rerendered = count)))
        }

        // Old single Settings / Git Sync URLs now live under the split pages / the Storage page.
        get("/git-sync") {
            call.respondRedirect("/a/storage", permanent = true)
        }

        // Storage and backup: Git Sync + Backup/restore merged onto one page.
        get("/storage") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("admin/storage.hbs", call.storageModel()))
        }

        // --- Updates (root only): instance-wide, so gated on root permissions. ---
        // The GET itself performs the (cached, lazy) release check when checks are enabled, by
        // design: no background poller and nothing scheduled, so a request only ever happens while a
        // root admin is looking at the console. This page waits for the result inline; the dashboard's
        // "update available" badge instead refreshes weekly in the background (UpdateService kdoc).
        get("/updates") {
            if (!call.requireRoot()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("admin/updates.hbs", call.updatesModel()))
        }

        // Consent toggle for auto update check: "enable"/"disable" from the two buttons (or the settings form).
        post("/updates/settings") {
            if (!call.requireRoot()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            when (params["updateChecks"]) {
                "enable" -> call.appContext.update.setOptIn(true)
                "disable" -> call.appContext.update.setOptIn(false)
            }
            // PRG: enabling means the next GET performs the first check; a refresh must not re-post.
            call.respondRedirect("/a/updates")
        }

        // "Check now": bypasses the cache TTL (itself rate-limited inside UpdateService).
        post("/updates/check") {
            if (!call.requireRoot()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            call.appContext.update.check(force = true)
            call.respondRedirect("/a/updates")
        }

        // One-click install via the wikikt-updater sidecar. Server-side re-checks every gate the UI
        // renders (updater fresh, update genuinely available, manifest allows self-update) — the form
        // is not the authority; and the updater independently re-derives its own guardrails from
        // image labels, so even this route being wrong cannot force an unsafe update.
        post("/updates/install") {
            if (!call.requireRoot()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val ctx = call.appContext
            if (params["confirmInstall"] == null) { // unchecked confirm box: bounce back, no action
                call.respondRedirect("/a/updates")
                return@post
            }
            val check = ctx.update.check()
            val manifest = (check as? UpdateCheck.Available)?.release?.manifest
            if (check is UpdateCheck.Available && manifest != null && manifest.selfUpdatable) {
                val username = call.currentUserId()?.let { ctx.users.findById(it)?.username } ?: "unknown"
                ctx.selfUpdate.requestInstall(
                    requestedBy = username,
                    currentVersion = com.wikikt.BuildInfo.version,
                    expectVersion = check.release.version.toString(),
                )
            }
            call.respondRedirect("/a/updates")
        }

        post("/git-sync") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val siteId = call.adminSiteId()
            val ctx = call.appContext
            val s = com.wikikt.service.SettingsService
            val mode = params["gitSyncMode"].orEmpty().trim()
            ctx.settings.set(siteId, s.GIT_SYNC_MODE, if (mode in s.GIT_SYNC_MODE_OPTIONS) mode else "off")
            // Only store a repo URL of an allowed transport shape; an unsafe/garbled value (e.g. a
            // command-executing `ext::` pseudo-URL) is dropped rather than persisted and handed to git.
            val repoUrl = params["gitSyncRepoUrl"].orEmpty().trim()
            val repoUrlOk = com.wikikt.service.GitSyncService.isAllowedRepoUrl(repoUrl)
            if (repoUrlOk) ctx.settings.set(siteId, s.GIT_SYNC_REPO_URL, repoUrl)
            // Branch: restrict to safe ref characters; anything else falls back to the default.
            val branch = params["gitSyncBranch"].orEmpty().trim()
            ctx.settings.set(siteId, s.GIT_SYNC_BRANCH, if (branch.matches(GIT_BRANCH_NAME)) branch else "")
            ctx.settings.set(siteId, s.GIT_SYNC_USERNAME, params["gitSyncUsername"].orEmpty().trim())
            // Token: a blank field keeps the stored token (it's never echoed back); the checkbox clears it.
            val token = params["gitSyncToken"].orEmpty()
            when {
                params["gitSyncClearToken"] != null -> ctx.settings.set(siteId, s.GIT_SYNC_TOKEN, "")
                token.isNotBlank() -> ctx.settings.set(siteId, s.GIT_SYNC_TOKEN, token.trim())
            }
            ctx.settings.set(siteId, s.GIT_SYNC_AUTHOR_NAME, params["gitSyncAuthorName"].orEmpty().trim())
            ctx.settings.set(siteId, s.GIT_SYNC_AUTHOR_EMAIL, params["gitSyncAuthorEmail"].orEmpty().trim())
            val interval = params["gitSyncInterval"]?.toIntOrNull()
            ctx.settings.set(
                siteId,
                s.GIT_SYNC_INTERVAL_MINUTES,
                (if (interval in s.GIT_SYNC_INTERVAL_OPTIONS) interval else s.DEFAULT_GIT_SYNC_INTERVAL_MINUTES).toString(),
            )
            val error = if (repoUrlOk) null else "That repository URL isn't a supported git remote (use an https, ssh, or git URL). It was not saved."
            call.respond(MustacheContent("admin/storage.hbs", call.storageModel(saved = true, error = error)))
        }

        // Sync actions run in the background (a git fetch/push can outlive the proxy's response
        // timeout). Each kicks off the run and redirects (POST-redirect-GET) to the Storage page,
        // which polls while it's in progress and shows the recorded outcome — success or a friendly,
        // redacted git error — when it finishes. No long-held request means no bare 504.
        post("/git-sync/run") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            call.appContext.gitSync.triggerManual(call.adminSiteId(), com.wikikt.service.GitSyncService.ManualAction.SYNC)
            call.respondRedirect("/a/storage")
        }

        // Force actions: full export/import regardless of the configured mode.
        post("/git-sync/export") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            call.appContext.gitSync.triggerManual(call.adminSiteId(), com.wikikt.service.GitSyncService.ManualAction.EXPORT)
            call.respondRedirect("/a/storage")
        }

        post("/git-sync/import") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            call.appContext.gitSync.triggerManual(call.adminSiteId(), com.wikikt.service.GitSyncService.ManualAction.IMPORT)
            call.respondRedirect("/a/storage")
        }

        // Per-site history-retention limits (how many prior page / asset versions to keep).
        post("/storage/retention") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val siteId = call.adminSiteId()
            val ctx = call.appContext
            val s = com.wikikt.service.SettingsService
            fun clamp(v: String?, fallback: Int) =
                (v?.trim()?.toIntOrNull() ?: fallback).coerceIn(1, s.MAX_HISTORY_LIMIT)
            val pageLimit = clamp(params["maxPageRevisions"], s.DEFAULT_MAX_PAGE_REVISIONS)
            val assetLimit = clamp(params["maxAssetRevisions"], ctx.config.assets.maxAssetVersions)
            ctx.settings.set(siteId, s.HISTORY_MAX_PAGE_REVISIONS, pageLimit.toString())
            ctx.settings.set(siteId, s.HISTORY_MAX_ASSET_REVISIONS, assetLimit.toString())
            call.respond(MustacheContent("admin/storage.hbs", call.storageModel(historySaved = true)))
        }

        // Per-site max files per upload (governs the /f form and the editor's picker upload).
        post("/storage/uploads") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val siteId = call.adminSiteId()
            val ctx = call.appContext
            val s = com.wikikt.service.SettingsService
            val limit = (params["maxUploadFiles"]?.trim()?.toIntOrNull() ?: ctx.config.assets.maxFilesPerUpload)
                .coerceIn(1, s.MAX_UPLOAD_FILE_LIMIT)
            ctx.settings.set(siteId, s.ASSETS_MAX_FILES_PER_UPLOAD, limit.toString())
            // Unchecked checkboxes don't submit, so absence means "off".
            ctx.settings.setBool(siteId, s.ASSETS_STRIP_METADATA, params["stripMetadata"] != null)
            call.respond(MustacheContent("admin/storage.hbs", call.storageModel(uploadsSaved = true)))
        }

        // One-time purge of content history older than a chosen age (or all of it). Live pages/assets
        // are untouched — only revision history (and archived asset bytes) are removed.
        post("/storage/purge-history") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val siteId = call.adminSiteId()
            val ctx = call.appContext
            val now = System.currentTimeMillis()
            val day = 86_400_000L
            val amount = params["amount"]?.trim()?.toLongOrNull()?.coerceIn(1, 10_000) ?: 6
            val cutoff = when (params["unit"]?.trim()) {
                "all" -> Long.MAX_VALUE
                "days" -> now - amount * day
                "weeks" -> now - amount * 7 * day
                "months" -> now - amount * 30 * day
                else -> now - amount * 30 * day
            }
            val purgedPages = ctx.pages.purgeRevisionsOlderThan(siteId, cutoff)
            val purgedAssets = ctx.assets.purgeRevisionsOlderThan(siteId, cutoff)
            val msg = "Purged $purgedPages page revision${if (purgedPages == 1) "" else "s"} and " +
                "$purgedAssets asset revision${if (purgedAssets == 1) "" else "s"}."
            call.respond(MustacheContent("admin/storage.hbs", call.storageModel(purgeMessage = msg)))
        }

        // Security: per-site Content-Security-Policy — append trusted sources to the code baseline.
        get("/security") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@get }
            call.respond(MustacheContent("admin/security.hbs", call.securityModel()))
        }
        post("/security") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@post }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val siteId = call.adminSiteId()
            val ctx = call.appContext
            val s = com.wikikt.service.SettingsService
            // Store each loosenable directive's additions as validated, space-joined tokens (junk dropped),
            // so what's stored equals what's applied and can never inject a new directive.
            for (d in s.CSP_DIRECTIVES) {
                val key = d.settingKey ?: continue
                val field = "csp_" + d.name.replace("-", "_") // e.g. csp_script_src
                ctx.settings.set(siteId, key, s.sanitizeCspSources(params[field]?.take(s.MAX_CSP_FIELD_LENGTH)).joinToString(" "))
            }
            ctx.settings.setBool(siteId, s.SECURITY_CSP_REPORT_ONLY, params["cspReportOnly"] != null)
            call.respond(MustacheContent("admin/security.hbs", call.securityModel(saved = true)))
        }

        // --- Mail: per-site SMTP settings, editable email templates, and the send-queue monitor. ---
        get("/mail") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@get }
            call.respond(MustacheContent("admin/mail.hbs", call.mailModel()))
        }
        post("/mail") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@post }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val siteId = call.adminSiteId()
            val ctx = call.appContext
            val s = com.wikikt.service.SettingsService
            ctx.settings.setBool(siteId, s.MAIL_ENABLED, params["mailEnabled"] != null)
            ctx.settings.set(siteId, s.MAIL_SMTP_HOST, params["smtpHost"].orEmpty().trim())
            val port = params["smtpPort"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: s.DEFAULT_MAIL_SMTP_PORT
            ctx.settings.set(siteId, s.MAIL_SMTP_PORT, port.toString())
            val security = params["smtpSecurity"].orEmpty().trim()
            ctx.settings.set(siteId, s.MAIL_SMTP_SECURITY, if (security in s.MAIL_SECURITY_OPTIONS) security else s.DEFAULT_MAIL_SECURITY)
            ctx.settings.set(siteId, s.MAIL_SMTP_USERNAME, params["smtpUsername"].orEmpty().trim())
            // Password field mirrors the git-sync token: blank leaves the stored value untouched; a
            // "clear" checkbox wipes it; any other value replaces it.
            when {
                params["clearSmtpPassword"] != null -> ctx.settings.set(siteId, s.MAIL_SMTP_PASSWORD, "")
                params["smtpPassword"].orEmpty().isNotEmpty() -> ctx.settings.set(siteId, s.MAIL_SMTP_PASSWORD, params["smtpPassword"]!!)
            }
            ctx.settings.set(siteId, s.MAIL_FROM_ADDRESS, params["fromAddress"].orEmpty().trim())
            ctx.settings.set(siteId, s.MAIL_FROM_NAME, params["fromName"].orEmpty().trim())
            ctx.settings.set(siteId, s.MAIL_ADMIN_RECIPIENTS, params["adminRecipients"].orEmpty().trim())
            call.respond(MustacheContent("admin/mail.hbs", call.mailModel(saved = true)))
        }
        post("/mail/test") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@post }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val to = params["testRecipient"].orEmpty().trim()
            val message = if (to.isBlank()) {
                "Enter a recipient address for the test email."
            } else {
                call.appContext.email.sendTest(call.adminSiteId(), to)
            }
            call.respond(MustacheContent("admin/mail.hbs", call.mailModel(testMessage = message)))
        }
        get("/mail/templates/{key}") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@get }
            val key = call.parameters["key"].orEmpty()
            if (call.appContext.emailTemplates.default(key) == null) return@get call.respond(HttpStatusCode.NotFound)
            call.respond(MustacheContent("admin/mail-template.hbs", call.mailTemplateModel(key)))
        }
        post("/mail/templates/{key}") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@post }
            val key = call.parameters["key"].orEmpty()
            val ctx = call.appContext
            if (ctx.emailTemplates.default(key) == null) return@post call.respond(HttpStatusCode.NotFound)
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            ctx.emailTemplates.saveOverride(
                siteId = call.adminSiteId(),
                key = key,
                subject = params["subject"].orEmpty().trim(),
                text = params["textBody"].orEmpty(),
                html = params["htmlBody"],
                userId = call.currentUserId(),
            )
            call.respond(MustacheContent("admin/mail-template.hbs", call.mailTemplateModel(key, saved = true)))
        }
        post("/mail/templates/{key}/reset") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@post }
            val key = call.parameters["key"].orEmpty()
            val ctx = call.appContext
            if (ctx.emailTemplates.default(key) == null) return@post call.respond(HttpStatusCode.NotFound)
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            ctx.emailTemplates.resetToDefault(call.adminSiteId(), key)
            call.respond(MustacheContent("admin/mail-template.hbs", call.mailTemplateModel(key, reset = true)))
        }
        post("/mail/queue/{id}/retry") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@post }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: return@post call.respond(HttpStatusCode.BadRequest)
            call.appContext.email.retry(call.adminSiteId(), id)
            call.respond(MustacheContent("admin/mail.hbs", call.mailModel()))
        }
        post("/mail/queue/{id}/delete") {
            if (!call.requireManageGroups()) { call.respondForbidden(); return@post }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: return@post call.respond(HttpStatusCode.BadRequest)
            call.appContext.email.deleteEntry(call.adminSiteId(), id)
            call.respond(MustacheContent("admin/mail.hbs", call.mailModel()))
        }

        get("/users") {
            if (!call.requireManageUsers()) {
                call.respondForbidden()
                return@get
            }
            val ctx = call.appContext
            val formats = call.displayFormats()
            // Surface accounts needing attention first: pending approval, then pending email, then active.
            val users = ctx.users.list()
                .sortedBy {
                    when (it.status) {
                        com.wikikt.db.UserStatus.PENDING_APPROVAL -> 0
                        com.wikikt.db.UserStatus.PENDING_EMAIL -> 1
                        else -> 2
                    }
                }
                .map { user -> user to ctx.users.toDto(user) }
            val groups = ctx.groups.list().map { it.toDto() }
            call.respond(
                MustacheContent(
                    "admin/users.hbs",
                    call.adminBaseModel() + mapOf(
                        "users" to users.map { (user, dto) ->
                            mapOf(
                                "id" to dto.id,
                                "username" to dto.username,
                                "email" to dto.email,
                                "createdAt" to DateDisplay.format(user.createdAt, formats),
                                "groupNames" to dto.groupIds.mapNotNull { gid ->
                                    groups.find { g -> g.id == gid }?.name
                                }.joinToString(", "),
                                "active" to (user.status == com.wikikt.db.UserStatus.ACTIVE),
                                "pendingApproval" to (user.status == com.wikikt.db.UserStatus.PENDING_APPROVAL),
                                "statusLabel" to when (user.status) {
                                    com.wikikt.db.UserStatus.ACTIVE -> "Active"
                                    com.wikikt.db.UserStatus.PENDING_EMAIL -> "Pending email"
                                    com.wikikt.db.UserStatus.PENDING_APPROVAL -> "Pending approval"
                                },
                            )
                        },
                    ),
                ),
            )
        }

        get("/users/new") {
            if (!call.requireManageUsers()) {
                call.respondForbidden()
                return@get
            }
            val groups = call.appContext.groups.list().map { it.toDto() }
            call.respond(
                MustacheContent(
                    "admin/user-form.hbs",
                    call.adminBaseModel() + mapOf(
                        "isNew" to true,
                        "user" to mapOf("username" to "", "email" to ""),
                        "groups" to groups.map { mapOf("id" to it.id, "name" to it.name, "selected" to false) },
                        "error" to null,
                    ),
                ),
            )
        }

        post("/users") {
            if (!call.requireManageUsers()) {
                call.respondForbidden()
                return@post
            }
            val ctx = call.appContext
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val username = params["username"]?.trim().orEmpty()
            val password = params["password"] ?: ""
            val email = params["email"]?.trim()?.ifBlank { null }
            val groupIds = params.getAll("groupIds") ?: emptyList()

            val validationError = when {
                username.isBlank() || password.isBlank() -> "Username and password are required"
                !REGISTER_USERNAME_PATTERN.matches(username) ->
                    "Choose a username of 3-100 characters: letters, numbers, and . _ - (starting with a letter or number)."
                else -> PasswordPolicy.validate(password, ctx.config.minPasswordLength)
            }
            if (validationError != null) {
                val groups = ctx.groups.list().map { it.toDto() }
                call.respond(
                    MustacheContent(
                        "admin/user-form.hbs",
                        call.adminBaseModel() + mapOf(
                            "isNew" to true,
                            "user" to mapOf("username" to username, "email" to email),
                            "groups" to groups.map { mapOf("id" to it.id, "name" to it.name, "selected" to (it.id in groupIds)) },
                            "error" to validationError,
                        ),
                    ),
                )
                return@post
            }

            try {
                ctx.users.create(
                    CreateUserRequest(username, password, email, groupIds),
                    actorIsRoot = ctx.permissions.isRoot(call.currentUserId()),
                )
            } catch (e: IllegalArgumentException) {
                call.respondForbidden()
                return@post
            }
            // Welcome email — best-effort: enqueue (never send inline) and never let a mail hiccup break
            // user creation. Only if the account has an address and the site has mail switched on.
            if (email != null) {
                runCatching {
                    val siteId = call.adminSiteId()
                    if (ctx.settings.getBool(siteId, com.wikikt.service.SettingsService.MAIL_ENABLED)) {
                        ctx.email.enqueue(
                            siteId = siteId,
                            recipient = email,
                            templateKey = com.wikikt.service.EmailTemplateService.WELCOME,
                            context = mapOf(
                                "siteName" to (ctx.settings.get(siteId, com.wikikt.service.SettingsService.SITE_NAME)?.ifBlank { null } ?: com.wikikt.service.SettingsService.DEFAULT_SITE_NAME),
                                "username" to username,
                                "displayName" to username,
                                "loginUrl" to call.outboundUrl("/login"),
                            ),
                        )
                    }
                }.onFailure { call.application.environment.log.warn("Failed to enqueue welcome email", it) }
            }
            call.respondRedirect("/a/users")
        }

        get("/users/{id}/edit") {
            if (!call.requireManageUsers()) {
                call.respondForbidden()
                return@get
            }
            val ctx = call.appContext
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val user = ctx.users.findById(id) ?: run {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val userDto = ctx.users.toDto(user)
            val groups = ctx.groups.list().map { it.toDto() }
            // MFA admin-reset: shown when the target has MFA. Resetting a ROOT account's MFA is root-only
            // (same boundary as editing a system-group member), so a delegated admin can't touch it.
            val actorIsRoot = ctx.permissions.isRoot(call.currentUserId())
            val mfaEnabled = ctx.mfa.hasMfa(id)
            call.respond(
                MustacheContent(
                    "admin/user-form.hbs",
                    call.adminBaseModel() + mapOf(
                        "isNew" to false,
                        "user" to mapOf("id" to userDto.id, "username" to userDto.username, "email" to userDto.email, "displayName" to user.displayName),
                        "groups" to groups.map {
                            mapOf("id" to it.id, "name" to it.name, "selected" to (it.id in userDto.groupIds))
                        },
                        "mfaEnabled" to mfaEnabled,
                        "canResetMfa" to (mfaEnabled && (actorIsRoot || !ctx.permissions.isRoot(id))),
                        "mfaReset" to (call.request.queryParameters["mfaReset"] != null),
                        "error" to null,
                    ),
                ),
            )
        }

        post("/users/{id}") {
            if (!call.requireManageUsers()) {
                call.respondForbidden()
                return@post
            }
            val ctx = call.appContext
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val username = params["username"]?.trim()
            val password = params["password"]?.takeIf { it.isNotBlank() }
            val email = params["email"]?.trim()?.ifBlank { null }
            // The edit form always posts this; blank clears the display name. Admins may set any value,
            // including one another user already has (no uniqueness check on this path).
            val displayName = params["displayName"]?.trim()?.take(100) ?: ""
            val groupIds = params.getAll("groupIds") ?: emptyList()

            val passwordError = password?.let { PasswordPolicy.validate(it, ctx.config.minPasswordLength) }
            if (passwordError != null) {
                val existing = ctx.users.findById(id)
                val groups = ctx.groups.list().map { it.toDto() }
                call.respond(
                    MustacheContent(
                        "admin/user-form.hbs",
                        call.adminBaseModel() + mapOf(
                            "isNew" to false,
                            "user" to mapOf(
                                "id" to id.toString(),
                                "username" to (username ?: existing?.username),
                                "email" to (email ?: existing?.email),
                                "displayName" to displayName,
                            ),
                            "groups" to groups.map { mapOf("id" to it.id, "name" to it.name, "selected" to (it.id in groupIds)) },
                            "error" to passwordError,
                        ),
                    ),
                )
                return@post
            }

            try {
                ctx.users.update(
                    id,
                    UpdateUserRequest(username = username, password = password, email = email, displayName = displayName, groupIds = groupIds),
                    actorIsRoot = ctx.permissions.isRoot(call.currentUserId()),
                )
            } catch (e: IllegalArgumentException) {
                call.respondForbidden()
                return@post
            }
            call.respondRedirect("/a/users")
        }

        // Recover a user locked out of MFA (lost authenticator AND recovery codes): disable their second
        // factor so they can sign in with just their password and re-enrol. Resetting a ROOT account's MFA
        // is root-only — the same delegated-admin→root boundary the group/membership guards enforce.
        post("/users/{id}/reset-mfa") {
            if (!call.requireManageUsers()) {
                call.respondForbidden()
                return@post
            }
            val ctx = call.appContext
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            if (ctx.users.findById(id) == null) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            val actorIsRoot = ctx.permissions.isRoot(call.currentUserId())
            if (!actorIsRoot && ctx.permissions.isRoot(id)) {
                call.respondForbidden()
                return@post
            }
            ctx.mfa.disableMfa(id)
            call.respondRedirect("/a/users/$id/edit?mfaReset=1")
        }

        post("/users/{id}/delete") {
            if (!call.requireManageUsers()) {
                call.respondForbidden()
                return@post
            }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val actorId = call.currentUserId()
            try {
                call.appContext.users.delete(id, actorIsRoot = call.appContext.permissions.isRoot(actorId), actorId = actorId)
            } catch (e: IllegalArgumentException) {
                call.respondForbidden()
                return@post
            }
            call.respondRedirect("/a/users")
        }

        // Activate a self-registered account that has confirmed its email and is waiting for approval
        // (only reachable when the site requires approval). Best-effort emails the user that they're in.
        post("/users/{id}/approve") {
            if (!call.requireManageUsers()) {
                call.respondForbidden()
                return@post
            }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val ctx = call.appContext
            if (ctx.users.approve(id)) {
                runCatching {
                    val user = ctx.users.findById(id)
                    val siteId = call.adminSiteId()
                    if (user?.email != null && ctx.settings.getBool(siteId, com.wikikt.service.SettingsService.MAIL_ENABLED)) {
                        ctx.email.enqueue(
                            siteId = siteId,
                            recipient = user.email,
                            templateKey = com.wikikt.service.EmailTemplateService.REGISTRATION_APPROVED,
                            context = mapOf(
                                "siteName" to (ctx.settings.get(siteId, com.wikikt.service.SettingsService.SITE_NAME)?.ifBlank { null } ?: com.wikikt.service.SettingsService.DEFAULT_SITE_NAME),
                                "username" to user.username,
                                "loginUrl" to call.outboundUrl("/login"),
                            ),
                        )
                    }
                }.onFailure { call.application.environment.log.warn("Failed to enqueue registration-approved email", it) }
            }
            call.respondRedirect("/a/users")
        }

        get("/groups") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            val groups = call.appContext.groups.list().map { it.toDto() }
            call.respond(
                MustacheContent(
                    "admin/groups.hbs",
                    call.adminBaseModel() + mapOf("groups" to groups),
                ),
            )
        }

        get("/groups/new") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            call.respond(
                MustacheContent(
                    "admin/group-form.hbs",
                    call.adminBaseModel() + mapOf(
                        "isNew" to true,
                        "group" to mapOf(
                            "name" to "",
                            "canViewPages" to true,
                            "canEditPages" to false,
                            "canViewHistory" to true,
                            "canManageUsers" to false,
                            "canManageGroups" to false,
                            "isSystem" to false,
                        ),
                        "error" to null,
                    ),
                ),
            )
        }

        post("/groups") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val name = params["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                call.respond(
                    MustacheContent(
                        "admin/group-form.hbs",
                        call.adminBaseModel() + mapOf("isNew" to true, "group" to mapOf("name" to name), "error" to "Name is required"),
                    ),
                )
                return@post
            }

            val created = call.appContext.groups.create(
                CreateGroupRequest(
                    name = name,
                    permissions = com.wikikt.service.AccessResolver.ASSIGNABLE_ADMIN_VERBS.filter { params[it] != null }.toSet(),
                ),
            )
            call.respondRedirect("/a/groups/${created.id}/settings")
        }

        get("/groups/{id}") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respondRedirect("/a/groups/$id/settings")
        }

        // --- Settings tab: group name (+ delete) ---
        get("/groups/{id}/settings") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            val group = call.groupParam() ?: return@get
            call.respond(MustacheContent("admin/group-edit.hbs", call.groupEditModel(group, "settings")))
        }

        post("/groups/{id}/settings") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val group = call.groupParam() ?: return@post
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            try {
                call.appContext.groups.update(group.id, UpdateGroupRequest(name = params["name"]?.trim()))
            } catch (e: IllegalArgumentException) {
                call.respond(MustacheContent("admin/group-edit.hbs", call.groupEditModel(group, "settings", settingsError = e.message)))
                return@post
            }
            call.respondRedirect("/a/groups/${group.id}/settings")
        }

        // --- Permissions tab: global capabilities ---
        get("/groups/{id}/permissions") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            val group = call.groupParam() ?: return@get
            call.respond(MustacheContent("admin/group-edit.hbs", call.groupEditModel(group, "permissions")))
        }

        post("/groups/{id}/permissions") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val group = call.groupParam() ?: return@post
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            // Preserve root: manage:system isn't editable here, so keep it if the group already has it.
            val keepRoot = if (com.wikikt.service.AccessResolver.Perm.MANAGE_SYSTEM in group.permissions) {
                setOf(com.wikikt.service.AccessResolver.Perm.MANAGE_SYSTEM)
            } else {
                emptySet()
            }
            val chosen = com.wikikt.service.AccessResolver.ASSIGNABLE_ADMIN_VERBS.filter { params.contains(it) }.toSet()
            call.appContext.groups.update(group.id, UpdateGroupRequest(permissions = chosen + keepRoot))
            call.respondRedirect("/a/groups/${group.id}/permissions")
        }

        // --- Page Rules tab: per-group ALLOW/DENY view rules ---
        get("/groups/{id}/rules") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            val group = call.groupParam() ?: return@get
            call.respond(MustacheContent("admin/group-edit.hbs", call.groupEditModel(group, "rules")))
        }

        post("/groups/{id}/rules") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val group = call.groupParam() ?: return@post
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val effect = runCatching { RuleEffect.valueOf(params["effect"].orEmpty()) }.getOrDefault(RuleEffect.DENY)
            val matchType = runCatching { RuleMatchType.valueOf(params["matchType"].orEmpty()) }.getOrDefault(RuleMatchType.PREFIX)
            val pattern = if (matchType == RuleMatchType.TAG) params["pattern"].orEmpty().trim() else normalizeRulePattern(matchType, params["pattern"].orEmpty())
            // Roles = the content verbs this rule grants/denies; default to read:pages (a view rule).
            val roles = com.wikikt.service.AccessResolver.CONTENT_VERBS.filter { params[it] != null }.toSet()
                .ifEmpty { setOf(com.wikikt.service.AccessResolver.Perm.READ_PAGES) }
            // Empty site/locale selections mean "all sites" / "all locales".
            val sites = params.getAll("sites").orEmpty().mapNotNull { it.toUIntOrNull() }.toSet()
            val locales = params["locale"]?.trim()?.ifBlank { null }?.let { setOf(it) } ?: emptySet()
            val error = when {
                pattern.isBlank() -> "A pattern is required."
                matchType == RuleMatchType.REGEX -> SafeRegex.validate(pattern)
                else -> null
            }
            if (error != null) {
                call.respond(MustacheContent("admin/group-edit.hbs", call.groupEditModel(group, "rules", rulesError = error)))
                return@post
            }
            call.appContext.groupPageRules.create(group.id, effect, matchType, pattern, roles, sites, locales)
            call.respondRedirect("/a/groups/${group.id}/rules")
        }

        post("/groups/{id}/rules/{ruleId}/delete") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val group = call.groupParam() ?: return@post
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val ruleId = call.parameters["ruleId"]?.toUIntOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            call.appContext.groupPageRules.delete(ruleId)
            call.respondRedirect("/a/groups/${group.id}/rules")
        }

        // --- Users tab: membership from the group side ---
        get("/groups/{id}/users") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            val group = call.groupParam() ?: return@get
            call.respond(MustacheContent("admin/group-edit.hbs", call.groupEditModel(group, "users")))
        }

        post("/groups/{id}/users") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val group = call.groupParam() ?: return@post
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val userIds = (params.getAll("userIds") ?: emptyList()).mapNotNull { it.toUIntOrNull() }.toSet()
            try {
                call.appContext.groups.setGroupMembers(
                    group.id,
                    userIds,
                    actorIsRoot = call.appContext.permissions.isRoot(call.currentUserId()),
                )
            } catch (e: IllegalArgumentException) {
                call.respondForbidden()
                return@post
            }
            call.respondRedirect("/a/groups/${group.id}/users")
        }

        post("/groups/{id}/delete") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            try {
                call.appContext.groups.delete(id)
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "System groups cannot be deleted")
                return@post
            }
            call.respondRedirect("/a/groups")
        }

        get("/pages") {
            if (!call.requireManagePages()) {
                call.respondForbidden()
                return@get
            }
            val siteId = call.adminSiteId()
            val ctx = call.appContext
            val formats = call.displayFormats()
            val pages = ctx.pages.list(siteId).map { page ->
                val dto = ctx.pages.toDto(page)
                mapOf(
                    "id" to dto.id,
                    "title" to dto.title,
                    "description" to dto.description,
                    "locale" to dto.locale,
                    "path" to dto.path,
                    "tags" to dto.tags.joinToString(", "),
                    "updatedAt" to DateDisplay.format(page.updatedAt, formats),
                    "viewUrl" to wikiViewUrl(dto.locale, dto.path),
                    "editUrl" to wikiEditUrl(dto.locale, dto.path),
                )
            }
            call.respond(
                MustacheContent(
                    "admin/pages.hbs",
                    call.adminBaseModel() + mapOf("pages" to pages),
                ),
            )
        }

        get("/navigation") {
            if (!call.requireManageNavigation()) {
                call.respondForbidden()
                return@get
            }
            val siteId = call.adminSiteId()
            val nav = call.appContext.nav
            val menus = nav.listMenus(siteId).map { menu ->
                mapOf(
                    "id" to menu.id.toString(),
                    "scope" to menu.scope,
                    "isDefault" to menu.scope.isEmpty(),
                    "itemCount" to nav.items(menu.id).size,
                )
            }
            val mode = call.appContext.settings.navMode(siteId)
            call.respond(
                MustacheContent(
                    "admin/navigation.hbs",
                    call.adminBaseModel() + mapOf(
                        "menus" to menus,
                        "otherSite" to (call.request.queryParameters["otherSite"] != null),
                        // Navigation mode selector: which sidebar(s) the site offers (static / tree / both / none).
                        "modeStatic" to (mode == "static"),
                        "modeTree" to (mode == "tree"),
                        "modeBoth" to (mode == "both"),
                        "modeNone" to (mode == "none"),
                        // The static menu list is only relevant when a static menu is actually shown.
                        "staticEnabled" to (mode == "static" || mode == "both"),
                        "showEditMenuLink" to call.appContext.settings.getBool(siteId, com.wikikt.service.SettingsService.NAV_SHOW_EDIT_MENU_LINK, true),
                        "showHome" to call.appContext.settings.getBool(siteId, com.wikikt.service.SettingsService.NAV_SHOW_HOME, true),
                        "modeSaved" to (call.request.queryParameters["saved"] != null),
                    ),
                ),
            )
        }

        post("/navigation/mode") {
            if (!call.requireManageNavigation()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val mode = params["mode"]?.takeIf { it in com.wikikt.service.SettingsService.NAV_MODE_OPTIONS }
                ?: com.wikikt.service.SettingsService.DEFAULT_NAV_MODE
            val siteId = call.adminSiteId()
            call.appContext.settings.set(siteId, com.wikikt.service.SettingsService.NAV_MODE, mode)
            call.appContext.settings.setBool(siteId, com.wikikt.service.SettingsService.NAV_SHOW_EDIT_MENU_LINK, params["showEditMenuLink"] != null)
            call.appContext.settings.setBool(siteId, com.wikikt.service.SettingsService.NAV_SHOW_HOME, params["showHome"] != null)
            call.respondRedirect("/a/navigation?saved=1")
        }

        // On-page "Edit menu": jump to the editor for the menu that governs a page path, creating the
        // (default) menu if none exists yet. Powers decentralized editing from the wiki sidebar.
        get("/navigation/for/{path...}") {
            if (!call.requireManageNavigation()) {
                call.respondForbidden()
                return@get
            }
            val path = call.parameters.getAll("path")?.joinToString("/")?.trim('/').orEmpty()
            val menu = call.appContext.nav.menuForPath(call.adminSiteId(), path)
            call.respondRedirect(if (menu != null) "/a/navigation/${menu.id}/edit" else "/a/navigation/new")
        }

        get("/navigation/new") {
            if (!call.requireManageNavigation()) {
                call.respondForbidden()
                return@get
            }
            // Optional ?scope= prefill (e.g. arriving from a page to create a section-specific menu).
            val scope = call.request.queryParameters["scope"]?.trim()?.trim('/').orEmpty()
            call.respond(
                MustacheContent(
                    "admin/navigation-form.hbs",
                    call.adminBaseModel() + mapOf("isNew" to true, "scope" to scope, "definition" to "", "error" to null),
                ),
            )
        }

        post("/navigation") {
            if (!call.requireManageNavigation()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val siteId = call.adminSiteId()
            val scope = params["scope"]?.trim()?.trim('/').orEmpty()
            val definition = params["definition"].orEmpty()
            val nav = call.appContext.nav
            if (nav.listMenus(siteId).any { it.scope == scope }) {
                call.respond(
                    MustacheContent(
                        "admin/navigation-form.hbs",
                        call.adminBaseModel() + mapOf(
                            "isNew" to true, "scope" to scope, "definition" to definition,
                            "error" to "A menu for that scope already exists.",
                        ),
                    ),
                )
                return@post
            }
            nav.createMenu(siteId, scope, com.wikikt.service.NavService.parseDefinition(definition))
            call.respondRedirect("/a/navigation")
        }

        get("/navigation/{id}/edit") {
            if (!call.requireManageNavigation()) {
                call.respondForbidden()
                return@get
            }
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val nav = call.appContext.nav
            // Guard against stale cross-site URLs (e.g. after switching sites): the menu must belong to
            // the site the console is managing, else bounce to this site's list with a notice.
            val menu = nav.findMenu(id)
            if (menu == null || menu.siteId != call.adminSiteId()) {
                call.respondRedirect("/a/navigation?otherSite=1")
                return@get
            }
            call.respond(
                MustacheContent(
                    "admin/navigation-form.hbs",
                    call.adminBaseModel() + mapOf(
                        "isNew" to false,
                        "id" to id.toString(),
                        "scope" to menu.scope,
                        "definition" to com.wikikt.service.NavService.toDefinition(nav.items(id)),
                        "error" to null,
                    ),
                ),
            )
        }

        post("/navigation/{id}") {
            if (!call.requireManageNavigation()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val scope = params["scope"]?.trim()?.trim('/').orEmpty()
            val definition = params["definition"].orEmpty()
            val nav = call.appContext.nav
            val target = nav.findMenu(id)
            if (target == null || target.siteId != call.adminSiteId()) {
                call.respondRedirect("/a/navigation?otherSite=1")
                return@post
            }
            if (nav.listMenus(call.adminSiteId()).any { it.scope == scope && it.id != id }) {
                call.respond(
                    MustacheContent(
                        "admin/navigation-form.hbs",
                        call.adminBaseModel() + mapOf(
                            "isNew" to false, "id" to id.toString(), "scope" to scope, "definition" to definition,
                            "error" to "A menu for that scope already exists.",
                        ),
                    ),
                )
                return@post
            }
            nav.updateMenu(id, scope, com.wikikt.service.NavService.parseDefinition(definition))
            call.respondRedirect("/a/navigation")
        }

        post("/navigation/{id}/delete") {
            if (!call.requireManageNavigation()) {
                call.respondForbidden()
                return@post
            }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val menu = call.appContext.nav.findMenu(id)
            if (menu == null || menu.siteId != call.adminSiteId()) {
                call.respondRedirect("/a/navigation?otherSite=1")
                return@post
            }
            call.appContext.nav.deleteMenu(id)
            call.respondRedirect("/a/navigation")
        }

        get("/fragments") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            val counts = call.fragmentUsageCounts()
            val fragments = call.appContext.fragments.list(call.adminSiteId()).map {
                mapOf(
                    "id" to it.id.toString(), "locale" to it.locale, "key" to it.key, "title" to it.title,
                    "usedBy" to (counts[it.id] ?: 0),
                )
            }
            call.respond(
                MustacheContent(
                    "admin/fragments.hbs",
                    call.adminBaseModel() + mapOf(
                        "fragments" to fragments,
                        "otherSite" to (call.request.queryParameters["otherSite"] != null),
                    ),
                ),
            )
        }

        get("/fragments/new") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("admin/fragment-form.hbs", call.fragmentFormModel(isNew = true)))
        }

        post("/fragments") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val fields = call.fragmentFields(params)
            val error = call.fragmentError(fields, excludeId = null)
            if (error != null) {
                call.respond(MustacheContent("admin/fragment-form.hbs", call.fragmentFormModel(isNew = true, fields = fields, error = error)))
                return@post
            }
            call.appContext.fragments.create(call.adminSiteId(), fields.locale, fields.key, fields.title, fields.content, call.currentUserId())
            call.respondRedirect("/a/fragments")
        }

        get("/fragments/{id}/edit") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            // Guard against stale cross-site URLs (e.g. after switching sites): the fragment must belong
            // to the managed site, else bounce to this site's list with a notice.
            val fragment = call.appContext.fragments.findById(id)
            if (fragment == null || fragment.siteId != call.adminSiteId()) {
                call.respondRedirect("/a/fragments?otherSite=1")
                return@get
            }
            call.respond(
                MustacheContent(
                    "admin/fragment-form.hbs",
                    call.fragmentFormModel(
                        isNew = false,
                        id = id.toString(),
                        fields = FragmentFields(fragment.locale, fragment.key, fragment.title, fragment.content),
                        usages = call.fragmentUsages(fragment),
                    ),
                ),
            )
        }

        post("/fragments/{id}") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val existing = call.appContext.fragments.findById(id)
            if (existing == null || existing.siteId != call.adminSiteId()) {
                call.respondRedirect("/a/fragments?otherSite=1")
                return@post
            }
            val fields = call.fragmentFields(params)
            val error = call.fragmentError(fields, excludeId = id)
            if (error != null) {
                call.respond(MustacheContent("admin/fragment-form.hbs", call.fragmentFormModel(isNew = false, id = id.toString(), fields = fields, error = error)))
                return@post
            }
            call.appContext.fragments.update(id, fields.locale, fields.key, fields.title, fields.content, call.currentUserId())
            call.respondRedirect("/a/fragments")
        }

        post("/fragments/{id}/delete") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val fragment = call.appContext.fragments.findById(id)
            if (fragment == null || fragment.siteId != call.adminSiteId()) {
                call.respondRedirect("/a/fragments?otherSite=1")
                return@post
            }
            call.appContext.fragments.delete(id)
            call.respondRedirect("/a/fragments")
        }

        // --- Infoboxes: admin-defined field templates bound to page paths by rules; editors fill in the
        //     values per page (see InfoboxService). ---
        get("/infoboxes") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("admin/infoboxes.hbs", call.infoboxListModel()))
        }

        get("/infoboxes/new") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("admin/infobox-form.hbs", call.infoboxFormModel(isNew = true)))
        }

        post("/infoboxes") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val fields = call.infoboxTemplateFields(params)
            val error = call.infoboxTemplateError(fields, excludeId = null)
            if (error != null) {
                call.respond(MustacheContent("admin/infobox-form.hbs", call.infoboxFormModel(isNew = true, fields = fields, error = error)))
                return@post
            }
            call.appContext.infobox.createTemplate(call.adminSiteId(), fields.slug, fields.name, fields.description.ifBlank { null }, fields.fieldDefs)
            call.respondRedirect("/a/infoboxes")
        }

        get("/infoboxes/{id}/edit") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@get
            }
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val template = call.appContext.infobox.templateById(id)
            if (template == null || template.siteId != call.adminSiteId()) {
                call.respondRedirect("/a/infoboxes?otherSite=1")
                return@get
            }
            call.respond(
                MustacheContent(
                    "admin/infobox-form.hbs",
                    call.infoboxFormModel(
                        isNew = false,
                        id = id.toString(),
                        fields = InfoboxTemplateFields(template.slug, template.name, template.description ?: "", fieldsToText(template.fields), template.fields),
                    ),
                ),
            )
        }

        post("/infoboxes/{id}") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val existing = call.appContext.infobox.templateById(id)
            if (existing == null || existing.siteId != call.adminSiteId()) {
                call.respondRedirect("/a/infoboxes?otherSite=1")
                return@post
            }
            val fields = call.infoboxTemplateFields(params)
            val error = call.infoboxTemplateError(fields, excludeId = id)
            if (error != null) {
                call.respond(MustacheContent("admin/infobox-form.hbs", call.infoboxFormModel(isNew = false, id = id.toString(), fields = fields, error = error)))
                return@post
            }
            call.appContext.infobox.updateTemplate(id, fields.slug, fields.name, fields.description.ifBlank { null }, fields.fieldDefs)
            call.respondRedirect("/a/infoboxes")
        }

        post("/infoboxes/{id}/delete") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            val template = call.appContext.infobox.templateById(id)
            if (template == null || template.siteId != call.adminSiteId()) {
                call.respondRedirect("/a/infoboxes?otherSite=1")
                return@post
            }
            call.appContext.infobox.deleteTemplate(id)
            call.respondRedirect("/a/infoboxes")
        }

        post("/infoboxes/rules") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val pattern = params["pattern"]?.trim().orEmpty()
            val templateId = params["templateId"]?.toUIntOrNull()
            val matchType = if (params["matchType"] == InfoboxService.MATCH_TAG) InfoboxService.MATCH_TAG else InfoboxService.MATCH_PATH
            if (pattern.isNotEmpty() && templateId != null) {
                val template = call.appContext.infobox.templateById(templateId)
                if (template != null && template.siteId == call.adminSiteId()) {
                    call.appContext.infobox.createPathRule(call.adminSiteId(), pattern, templateId, matchType)
                }
            }
            call.respondRedirect("/a/infoboxes")
        }

        post("/infoboxes/rules/{id}/delete") {
            if (!call.requireManageGroups()) {
                call.respondForbidden()
                return@post
            }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            call.appContext.infobox.deletePathRule(id)
            call.respondRedirect("/a/infoboxes")
        }

        // --- API keys: long-lived bearer tokens. A key authenticates as its owning user, so it
        // inherits that user's permissions; scope a key by owning it with a purpose-built user. ---
        get("/api-keys") {
            if (!call.requireManageUsers()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("admin/api-keys.hbs", call.apiKeysModel()))
        }

        get("/api-keys/new") {
            if (!call.requireManageUsers()) {
                call.respondForbidden()
                return@get
            }
            call.respond(MustacheContent("admin/api-key-form.hbs", call.apiKeyFormModel()))
        }

        post("/api-keys") {
            if (!call.requireManageUsers()) {
                call.respondForbidden()
                return@post
            }
            val ctx = call.appContext
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val name = params["name"]?.trim().orEmpty()
            val userId = params["userId"]?.toUIntOrNull()
            val owner = userId?.let { ctx.users.findById(it) }
            val expiresIn = params["expiresIn"].orEmpty()
            val error = when {
                name.isBlank() -> "A name is required."
                owner == null -> "Choose an owner for the key."
                expiresIn.isNotEmpty() && expiresIn.toLongOrNull() == null -> "Invalid expiry."
                else -> null
            }
            if (error != null) {
                call.respond(
                    MustacheContent(
                        "admin/api-key-form.hbs",
                        call.apiKeyFormModel(name = name, selectedUserId = userId, expiresIn = expiresIn, error = error),
                    ),
                )
                return@post
            }
            // A key authenticates AS its owner, so minting one for a root account (a system-group
            // member) is the API-key equivalent of resetting root's password. Only root may do it.
            if (!ctx.permissions.isRoot(call.currentUserId()) && ctx.permissions.isSystemUser(owner!!.id)) {
                call.respondForbidden()
                return@post
            }
            val ttlMillis = expiresIn.toLongOrNull()?.let { it * 86_400_000L }
            val created = ctx.apiKeys.create(owner!!.id, name, ttlMillis)
            // Re-render the list with the one-time plaintext banner. The token is never recoverable after this.
            call.respond(MustacheContent("admin/api-keys.hbs", call.apiKeysModel(newKey = created.plaintext)))
        }

        post("/api-keys/{id}/revoke") {
            if (!call.requireManageUsers()) {
                call.respondForbidden()
                return@post
            }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            call.appContext.apiKeys.revoke(id)
            call.respondRedirect("/a/api-keys")
        }

        post("/api-keys/{id}/delete") {
            if (!call.requireManageUsers()) {
                call.respondForbidden()
                return@post
            }
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val id = call.parameters["id"]?.let(::parseId) ?: run {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            call.appContext.apiKeys.delete(id)
            call.respondRedirect("/a/api-keys")
        }

    }
}

/** Expiry choices offered in the create-key form: value is a day count ("" = never). */
private val API_KEY_EXPIRY_OPTIONS = listOf(
    "30" to "30 days",
    "90" to "90 days",
    "365" to "1 year",
    "" to "Never",
)

/** The expiry <select> option models, marking [selected] current. Shared with self-service keys. */
internal fun apiKeyExpiryOptions(selected: String): List<Map<String, Any?>> =
    API_KEY_EXPIRY_OPTIONS.map { mapOf("value" to it.first, "label" to it.second, "selected" to (it.first == selected)) }

/** One API-key list-row model: owner label + derived status, dates in the viewer's [zone]. Shared by
 *  admin and self-service views. */
internal fun apiKeyRowModel(
    key: com.wikikt.service.ApiKeyRecord,
    ownerName: String,
    now: Long,
    formats: DateDisplay.DisplayFormats,
): Map<String, Any?> {
    val expired = key.expiresAt != null && key.expiresAt < now
    val status = when {
        key.revokedAt != null -> "Revoked"
        expired -> "Expired"
        else -> "Active"
    }
    return mapOf(
        "id" to key.id.toString(),
        "name" to key.name,
        "owner" to ownerName,
        "partialKey" to key.partialKey,
        "created" to DateDisplay.format(key.createdAt, formats),
        "lastUsed" to (key.lastUsedAt?.let { DateDisplay.format(it, formats) } ?: "Never"),
        "expires" to (key.expiresAt?.let { DateDisplay.format(it, formats) } ?: "Never"),
        "status" to status,
        "isActive" to (status == "Active"),
    )
}

/** Admin API keys list — every key, with owner column and a picker on the create form. [newKey],
 *  when set, is the just-created plaintext shown once. */
private suspend fun io.ktor.server.application.ApplicationCall.apiKeysModel(newKey: String? = null): Map<String, Any?> {
    val ctx = appContext
    val now = com.wikikt.model.nowMillis()
    val formats = displayFormats()
    val usernames = ctx.users.list().associate { it.id to it.username }
    val keys = ctx.apiKeys.list().map { apiKeyRowModel(it, usernames[it.userId] ?: "(deleted user)", now, formats) }
    return adminBaseModel() + mapOf(
        "keys" to keys,
        "hasKeys" to keys.isNotEmpty(),
        "newKey" to newKey,
        "baseUrl" to "/a/api-keys",
        "showOwner" to true,
        "canCreate" to true,
    )
}

/** Admin create-key form: name, owner (user picker), and expiry options. */
private suspend fun io.ktor.server.application.ApplicationCall.apiKeyFormModel(
    name: String = "",
    selectedUserId: UInt? = null,
    expiresIn: String = "",
    error: String? = null,
): Map<String, Any?> {
    val users = appContext.users.list().map {
        mapOf("id" to it.id.toString(), "username" to it.username, "selected" to (it.id == selectedUserId))
    }
    return adminBaseModel() + mapOf(
        "name" to name,
        "users" to users,
        "showOwnerPicker" to true,
        "expiryOptions" to apiKeyExpiryOptions(expiresIn),
        "error" to error,
        "baseUrl" to "/a/api-keys",
    )
}

/** Resolves the `{id}` path param to a group, responding 400/404 (and returning null) if invalid. */
private suspend fun io.ktor.server.application.ApplicationCall.groupParam(): com.wikikt.model.GroupRecord? {
    val id = parameters["id"]?.toUIntOrNull() ?: run {
        respond(HttpStatusCode.BadRequest)
        return null
    }
    return appContext.groups.findById(id) ?: run {
        respond(HttpStatusCode.NotFound)
        return null
    }
}

/**
 * Single model for the whole group editor. All four tabs — Settings, Permissions, Page Rules, Users —
 * render together so switching between them is client-side; [activeTab] only decides which pane opens.
 * Per-pane errors are namespaced ([settingsError] / [rulesError]) so an alert on one tab doesn't bleed
 * into another. (The Permissions and Users forms redirect on success and never re-render an error here.)
 */
private suspend fun io.ktor.server.application.ApplicationCall.groupEditModel(
    group: com.wikikt.model.GroupRecord,
    activeTab: String,
    settingsError: String? = null,
    rulesError: String? = null,
): Map<String, Any?> {
    val ctx = appContext

    // Permissions pane: assignable global (admin) verbs. Root (manage:system) is shown but not editable.
    val isRoot = com.wikikt.service.AccessResolver.Perm.MANAGE_SYSTEM in group.permissions
    val permissions = com.wikikt.service.AccessResolver.ASSIGNABLE_ADMIN_VERBS.map { verb ->
        mapOf("verb" to verb, "label" to adminVerbLabel(verb), "granted" to (verb in group.permissions))
    }

    // Page Rules pane: existing rules + the add-form's option lists.
    val sites = ctx.sites.all()
    val siteNames = sites.associate { it.id to it.name }
    val rules = ctx.groupPageRules.rulesForGroup(group.id).map {
        mapOf(
            "id" to it.id.toString(),
            "effect" to it.effect.name,
            "isDeny" to (it.effect == RuleEffect.DENY),
            "matchLabel" to ruleMatchLabel(it.matchType),
            "pattern" to it.pattern,
            "roles" to it.roles.sorted().joinToString(", "),
            "sites" to (if (it.sites.isEmpty()) "All sites" else it.sites.map { id -> siteNames[id] ?: "#$id" }.sorted().joinToString(", ")),
            "locale" to (if (it.locales.isEmpty()) "—" else it.locales.sorted().joinToString(", ")),
        )
    }
    val siteOptions = sites.map { mapOf("id" to it.id.toString(), "name" to it.name) }
    val verbOptions = com.wikikt.service.AccessResolver.CONTENT_VERBS.sorted().map {
        mapOf("verb" to it, "label" to contentVerbLabel(it), "isRead" to (it == com.wikikt.service.AccessResolver.Perm.READ_PAGES))
    }

    // Users pane: every user with a flag for whether they're a member.
    val members = ctx.groups.userIdsInGroup(group.id)
    val users = ctx.users.list().map {
        mapOf("id" to it.id.toString(), "username" to it.username, "selected" to (it.id in members))
    }

    return adminBaseModel() + mapOf(
        "group" to mapOf("id" to group.id.toString(), "name" to group.name, "isSystem" to group.isSystem),
        "tab" to mapOf(
            "settings" to (activeTab == "settings"),
            "permissions" to (activeTab == "permissions"),
            "rules" to (activeTab == "rules"),
            "users" to (activeTab == "users"),
        ),
        "settingsError" to settingsError,
        "isRoot" to isRoot,
        "permissions" to permissions,
        "rules" to rules,
        "rulesError" to rulesError,
        "matchTypes" to RuleMatchType.entries.map { mapOf("value" to it.name, "label" to ruleMatchLabel(it)) },
        "verbOptions" to verbOptions,
        "siteOptions" to siteOptions,
        "users" to users,
    )
}

/** Friendly label for a content permission verb, shown in the page-rule editor. */
private fun contentVerbLabel(verb: String): String = when (verb) {
    com.wikikt.service.AccessResolver.Perm.READ_PAGES -> "Read pages"
    com.wikikt.service.AccessResolver.Perm.WRITE_PAGES -> "Write pages"
    com.wikikt.service.AccessResolver.Perm.MANAGE_PAGES -> "Move pages"
    com.wikikt.service.AccessResolver.Perm.DELETE_PAGES -> "Delete pages"
    com.wikikt.service.AccessResolver.Perm.READ_SOURCE -> "View page source"
    com.wikikt.service.AccessResolver.Perm.READ_HISTORY -> "View page history"
    com.wikikt.service.AccessResolver.Perm.READ_ASSETS -> "Read assets"
    com.wikikt.service.AccessResolver.Perm.WRITE_ASSETS -> "Upload assets"
    com.wikikt.service.AccessResolver.Perm.MANAGE_ASSETS -> "Manage assets"
    else -> verb
}

/** Friendly label for an admin permission verb, shown on the group Permissions tab. */
private fun adminVerbLabel(verb: String): String = when (verb) {
    com.wikikt.service.AccessResolver.Perm.ACCESS_ADMIN -> "Access the admin area"
    com.wikikt.service.AccessResolver.Perm.MANAGE_USERS -> "Manage users"
    com.wikikt.service.AccessResolver.Perm.MANAGE_GROUPS -> "Manage groups & access"
    com.wikikt.service.AccessResolver.Perm.MANAGE_NAVIGATION -> "Manage navigation"
    com.wikikt.service.AccessResolver.Perm.MANAGE_SITES -> "Manage sites"
    com.wikikt.service.AccessResolver.Perm.MANAGE_THEME -> "Add per-page custom code (CSS/JS)"
    com.wikikt.service.AccessResolver.Perm.CREATE_APIKEYS -> "Create API keys"
    else -> verb
}

private fun ruleMatchLabel(type: RuleMatchType): String = when (type) {
    RuleMatchType.PREFIX -> "Path starts with"
    RuleMatchType.EXACT -> "Path is exactly"
    RuleMatchType.SUFFIX -> "Path ends with"
    RuleMatchType.REGEX -> "Path matches regex"
    RuleMatchType.TAG -> "Page has tag"
}

/** Canonicalizes a typed pattern: stored paths have no leading slash, and `^/` in a regex never matches. */
private fun normalizeRulePattern(matchType: RuleMatchType, raw: String): String {
    val t = raw.trim()
    return when (matchType) {
        RuleMatchType.PREFIX, RuleMatchType.EXACT -> t.removePrefix("/")
        RuleMatchType.SUFFIX, RuleMatchType.TAG -> t
        RuleMatchType.REGEX -> if (t.startsWith("^/")) "^" + t.substring(2) else t
    }
}

private data class FragmentFields(val locale: String, val key: String, val title: String, val content: String)

private val FRAGMENT_KEY = Regex("[a-zA-Z0-9._/-]+")

private fun io.ktor.server.application.ApplicationCall.fragmentFields(params: io.ktor.http.Parameters): FragmentFields {
    val ctx = appContext
    return FragmentFields(
        locale = params["locale"]?.trim()?.ifBlank { null } ?: ctx.config.defaultLocale,
        key = params["key"]?.trim().orEmpty(),
        title = params["title"]?.trim().orEmpty(),
        content = params["content"].orEmpty(),
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.fragmentError(fields: FragmentFields, excludeId: UInt?): String? {
    if (!FRAGMENT_KEY.matches(fields.key)) return "Key is required and may only contain letters, numbers, hyphens and underscores."
    if (fields.title.isBlank()) return "Title is required."
    val clash = appContext.fragments.list(adminSiteId()).any { it.locale == fields.locale && it.key == fields.key && it.id != excludeId }
    if (clash) return "A fragment with that key already exists for locale '${fields.locale}'."
    return null
}

private suspend fun io.ktor.server.application.ApplicationCall.fragmentFormModel(
    isNew: Boolean,
    id: String? = null,
    fields: FragmentFields? = null,
    error: String? = null,
    usages: List<Map<String, Any?>> = emptyList(),
): Map<String, Any?> {
    val f = fields ?: FragmentFields(appContext.config.defaultLocale, "", "", "")
    return adminBaseModel() + mapOf(
        "isNew" to isNew,
        "id" to id,
        "locale" to f.locale,
        "key" to f.key,
        "title" to f.title,
        "content" to f.content,
        "error" to error,
        "usages" to usages,
        "hasUsages" to usages.isNotEmpty(),
        "usageCount" to usages.size,
    )
}

/** Pages that render [fragment] (markdown pages whose reference resolves to this exact fragment). */
private suspend fun io.ktor.server.application.ApplicationCall.fragmentUsages(
    fragment: com.wikikt.model.FragmentRecord,
): List<Map<String, Any?>> {
    val ctx = appContext
    val siteId = adminSiteId()
    val byKey = ctx.fragments.list(siteId).associateBy { "${it.locale} ${it.key}" }
    return ctx.pages.list(siteId)
        .filter { it.contentFormat == com.wikikt.db.ContentFormat.MARKDOWN }
        .filter { fragment.key in ctx.fragments.referencedKeys(it.content) }
        .filter { resolvesTo(byKey, it.locale, fragment.key, ctx.config.defaultLocale) == fragment.id }
        .sortedBy { it.path }
        .map {
            mapOf(
                "title" to it.title,
                "path" to it.path,
                "locale" to it.locale,
                "viewUrl" to wikiViewUrl(it.locale, it.path),
                "editUrl" to wikiEditUrl(it.locale, it.path),
            )
        }
}

/** How many pages use each fragment (keyed by fragment id), for the list view. One pass over pages. */
private suspend fun io.ktor.server.application.ApplicationCall.fragmentUsageCounts(): Map<UInt, Int> {
    val ctx = appContext
    val siteId = adminSiteId()
    val byKey = ctx.fragments.list(siteId).associateBy { "${it.locale} ${it.key}" }
    val counts = mutableMapOf<UInt, Int>()
    for (page in ctx.pages.list(siteId)) {
        if (page.contentFormat != com.wikikt.db.ContentFormat.MARKDOWN) continue
        for (key in ctx.fragments.referencedKeys(page.content)) {
            val fragmentId = resolvesTo(byKey, page.locale, key, ctx.config.defaultLocale) ?: continue
            counts[fragmentId] = (counts[fragmentId] ?: 0) + 1
        }
    }
    return counts
}

/** Resolves which fragment id a `{{fragment:key}}` in [pageLocale] renders: page locale, then default. */
private fun resolvesTo(
    byKey: Map<String, com.wikikt.model.FragmentRecord>,
    pageLocale: String,
    key: String,
    defaultLocale: String,
): UInt? = (byKey["$pageLocale $key"] ?: byKey["$defaultLocale $key"])?.id

/** Model for the Administration dashboard: counts, recent activity, and version info. */
internal suspend fun io.ktor.server.application.ApplicationCall.dashboardModel(): Map<String, Any?> {
    val ctx = appContext
    val formats = displayFormats()
    val pages = ctx.pages.list(adminSiteId())
    val recentPages = pages.sortedByDescending { it.updatedAt }.take(6).map {
        mapOf(
            "title" to it.title,
            "path" to it.path,
            "locale" to it.locale,
            "url" to wikiViewUrl(it.locale, it.path),
            "updatedAt" to DateDisplay.format(it.updatedAt, formats),
        )
    }
    val recentLogins = ctx.sessions.recentLoginsByUser(6).mapNotNull { (userId, at) ->
        ctx.users.findById(userId)?.let { user ->
            mapOf("username" to user.username, "at" to DateDisplay.format(at, formats))
        }
    }
    val base = adminBaseModel()
    // "Update available" link in the version card. Root only: it points at /a/updates, which is
    // root-gated, so showing it to a delegated admin would just hand them a 403. Cache-only — see
    // UpdateService.availableIfKnown, which refreshes weekly in the background rather than making the
    // dashboard wait on github.com (and stays silent entirely until an admin opts in).
    val update = if (base["navSecRoot"] == true) ctx.update.availableIfKnown() else null
    return base + mapOf(
        "pageCount" to pages.size,
        "userCount" to ctx.users.list().size,
        "groupCount" to ctx.groups.list().size,
        "recentPages" to recentPages,
        "hasRecentPages" to recentPages.isNotEmpty(),
        "recentLogins" to recentLogins,
        "hasRecentLogins" to recentLogins.isNotEmpty(),
        // Version card shows the essentials only; commit sha and build time live on the Updates page.
        "appVersion" to com.wikikt.BuildInfo.version,
        "jvmVersion" to System.getProperty("java.version"),
        "dbEngine" to when (ctx.config.database.type) {
            com.wikikt.config.DatabaseType.POSTGRES -> "PostgreSQL"
            com.wikikt.config.DatabaseType.H2 -> "H2"
        },
        "updateAvailable" to (update != null),
        "updateVersion" to update?.release?.version?.toString(),
    )
}

/**
 * Model for Administration > Updates (root only). Exactly one `state*` flag is true; the template
 * renders one card per state. When checks are enabled and the cache is stale, building this model
 * performs the actual (network) check — see the route comment.
 */
internal suspend fun io.ktor.server.application.ApplicationCall.updatesModel(): Map<String, Any?> {
    val ctx = appContext
    val formats = displayFormats()
    val check = ctx.update.check()
    val optIn = ctx.update.optIn()
    fun checkedAtOf(at: Long) = DateDisplay.format(at, formats)
    val state = when (check) {
        is UpdateCheck.NotApplicable -> mapOf("stateDevBuild" to true)
        is UpdateCheck.NotEnabled -> mapOf(
            // Tri-state consent: never asked -> the consent card; explicitly declined -> the re-enable card.
            "stateConsent" to (optIn == null),
            "stateDeclined" to (optIn == false),
        )
        is UpdateCheck.UpToDate -> mapOf("stateUpToDate" to true, "checkedAt" to checkedAtOf(check.checkedAt))
        is UpdateCheck.Failed -> mapOf(
            "stateFailed" to true,
            "failMessage" to check.message,
            "checkedAt" to checkedAtOf(check.checkedAt),
        )
        is UpdateCheck.Available -> mapOf(
            "stateAvailable" to true,
            "latestVersion" to check.release.version.toString(),
            "latestTag" to check.release.tag,
            "latestUrl" to check.release.htmlUrl,
            "checkedAt" to checkedAtOf(check.checkedAt),
        )
    }
    return adminBaseModel() + mapOf(
        "appVersion" to com.wikikt.BuildInfo.version,
        "appGitSha" to com.wikikt.BuildInfo.gitSha.takeIf { it != "unknown" },
        // Build stamp is fixed UTC (not the viewer's locale format): it identifies a build artifact,
        // so it should read identically for every admin comparing against release metadata.
        "appBuiltAt" to com.wikikt.BuildInfo.builtAt?.let { DateDisplay.formatUtc(it * 1000) },
        "releasesUrl" to UpdateService.RELEASES_PAGE_URL,
        // The Check now button renders only in states where checks are actually running.
        "checksEnabled" to (optIn == true && check !is UpdateCheck.NotApplicable),
    ) + state + selfUpdateModel(check)
}

/**
 * The self-update (updater sidecar) portion of the Updates page model. Everything degrades: no
 * configured dirs -> all flags false (manual instructions only); stale heartbeat or missing
 * manifest -> Install hidden with a reason the admin can act on. The gates here are advisory UI —
 * the install route re-checks them, and the updater re-derives its own from image labels.
 */
private suspend fun io.ktor.server.application.ApplicationCall.selfUpdateModel(check: UpdateCheck): Map<String, Any?> {
    val ctx = appContext
    val su = ctx.selfUpdate
    if (!su.configured) return mapOf("updaterConfigured" to false)
    val formats = displayFormats()
    val presence = su.presence()
    val status = su.status()
    val running = su.isRunning(status)
    val pending = su.pending(status)
    val manifest = (check as? UpdateCheck.Available)?.release?.manifest

    // Pre-click advisories, comparing the manifest (advisory copy of the release's guardrails)
    // against this build / the running container's labels as reported by the updater's heartbeat.
    val heartbeat = (presence as? com.wikikt.service.UpdaterPresence.Available)?.heartbeat
    val minFrom = manifest?.minUpgradeFrom?.let { com.wikikt.service.SemVer.parse(it) }
    val current = com.wikikt.service.SemVer.parse(com.wikikt.BuildInfo.version)
    val minUpgradeBlocked = minFrom != null && current != null && current < minFrom
    val composeRevBlocked = run {
        val need = manifest?.composeRevision
        val have = heartbeat?.runningComposeRevision
        need != null && have != null && need > have
    }

    val canInstall = check is UpdateCheck.Available && presence is com.wikikt.service.UpdaterPresence.Available &&
        !running && pending !is com.wikikt.service.PendingInstall.Waiting &&
        manifest != null && manifest.selfUpdatable && !minUpgradeBlocked && !composeRevBlocked

    // Terminal outcome of the last run, with the DB breadcrumb ("requested by X at Y") when it
    // corroborates. Kept visible until the next run replaces it.
    val anchor = ctx.settings.instanceAnchorSiteId()
    val lastRequestId = ctx.settings.get(anchor, com.wikikt.service.SettingsService.UPDATE_LAST_REQUEST_ID)
    val requestedBy = ctx.settings.get(anchor, com.wikikt.service.SettingsService.UPDATE_LAST_REQUESTED_BY)
    val requestedAt = ctx.settings.get(anchor, com.wikikt.service.SettingsService.UPDATE_LAST_REQUESTED_AT)?.toLongOrNull()
    val terminal = status?.takeIf { it.terminal }

    return mapOf(
        "updaterConfigured" to true,
        // H2 keeps the database as a file in the app volume rather than as its own service, so the
        // updater's automatic pre-update backup (a pg_dump against that service) cannot run. The
        // install card says so and points at the full backup instead — see docker/README.md.
        "dbIsFile" to (ctx.config.database.type == com.wikikt.config.DatabaseType.H2),
        "updaterInstalled" to (presence is com.wikikt.service.UpdaterPresence.Available),
        "updaterStale" to (presence is com.wikikt.service.UpdaterPresence.Stale),
        "canInstall" to canInstall,
        // Why Install is hidden even though an update is available and the updater is running:
        "installNoManifest" to (check is UpdateCheck.Available && presence is com.wikikt.service.UpdaterPresence.Available && manifest == null),
        "installNotSelfUpdatable" to (manifest?.selfUpdatable == false),
        "installMinUpgradeBlocked" to minUpgradeBlocked,
        "installMinUpgradeFrom" to manifest?.minUpgradeFrom,
        "installComposeRevBlocked" to composeRevBlocked,
        // In-flight: drives the auto-refresh and the progress card.
        "updateRunning" to running,
        "runPhase" to status?.takeIf { !it.terminal }?.phase,
        "runMessage" to status?.takeIf { !it.terminal }?.message,
        "updateAbandoned" to su.isAbandoned(status),
        // Just-clicked: request written, updater hasn't picked it up yet (poll is ~10 s). Without
        // this the post-PRG page is indistinguishable from never having clicked Install.
        "updatePending" to (pending is com.wikikt.service.PendingInstall.Waiting),
        "pendingRequestedLine" to (pending as? com.wikikt.service.PendingInstall.Waiting)?.let {
            "Requested by ${it.requestedBy}, ${DateDisplay.format(it.requestedAt, formats)}."
        },
        "updateUnclaimed" to (pending is com.wikikt.service.PendingInstall.Unclaimed),
        // Keep the page following the action (JS poller, or meta refresh under noscript).
        "updateAutoRefresh" to (running || pending is com.wikikt.service.PendingInstall.Waiting),
        // Last terminal outcome.
        "lastOutcome" to (terminal != null),
        "outcomeSuccess" to (terminal?.phase == "success"),
        "outcomeRolledBack" to (terminal?.phase == "rolled-back"),
        "outcomeBlocked" to (terminal?.phase == "blocked"),
        "outcomeFailed" to (terminal?.phase == "failed"),
        "outcomeMessage" to terminal?.message,
        "outcomeBackupPath" to terminal?.backupPath?.ifBlank { null },
        "outcomeRequestedLine" to terminal?.let { t ->
            if (t.requestId.isNotEmpty() && t.requestId == lastRequestId && requestedBy != null && requestedAt != null) {
                "Requested by $requestedBy, ${DateDisplay.format(requestedAt, formats)}."
            } else {
                null
            }
        },
    )
}

/** Model for the Administration > Settings page. */
private val HEX_COLOR = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

/** (publicUrl, label) for every uploaded image asset — the candidates for the site logo. */
internal suspend fun io.ktor.server.application.ApplicationCall.imageAssetUrls(): List<Pair<String, String>> {
    val ctx = appContext
    val default = ctx.config.defaultLocale
    return ctx.assets.list(adminSiteId())
        .filter { it.mime.startsWith("image/") }
        .map { asset ->
            // Canonical (locale-prefixed) URL — matches what the asset picker returns, so the logo/favicon
            // value the picker submits validates against this list.
            val url = com.wikikt.routing.wikiViewUrl(asset.locale, asset.path)
            val label = if (asset.locale == default) asset.path else "${asset.path} (${asset.locale})"
            url to label
        }
}

internal suspend fun io.ktor.server.application.ApplicationCall.settingsModel(
    saved: Boolean = false,
    reindexed: Boolean = false,
    rerendered: Int? = null,
): Map<String, Any?> {
    val siteId = adminSiteId()
    val settings = appContext.settings
    val s = com.wikikt.service.SettingsService
    val renderDefaults = com.wikikt.markdown.RenderOptions.DEFAULT
    val currentLogo = settings.get(siteId, s.SITE_LOGO_URL).orEmpty()
    val logoUrls = imageAssetUrls()
    // Show the stored URL only if it still resolves to an uploaded image; otherwise treat as "default".
    val logoValue = currentLogo.takeIf { it.isNotBlank() && logoUrls.any { u -> u.first == it } }.orEmpty()
    val currentFavicon = settings.get(siteId, s.SITE_FAVICON_URL).orEmpty()
    val faviconValue = currentFavicon.takeIf { it.isNotBlank() && logoUrls.any { u -> u.first == it } }.orEmpty()
    val currentRobots = settings.get(siteId, s.SITE_META_ROBOTS)?.ifBlank { null } ?: s.DEFAULT_META_ROBOTS
    val siteRobotsOptions = s.META_ROBOTS_OPTIONS.map { opt -> mapOf("value" to opt, "label" to opt, "selected" to (opt == currentRobots)) }
    val searchBoxLabels = mapOf(
        "theme" to "Follow site theme",
        "light" to "Always light",
        "dark" to "Always dark",
    )
    val currentSearchBox = settings.get(siteId, s.SITE_SEARCH_BOX_THEME)?.ifBlank { null } ?: s.DEFAULT_SEARCH_BOX_THEME
    val searchBoxOptions = s.SEARCH_BOX_THEME_OPTIONS.map { mapOf("value" to it, "label" to searchBoxLabels[it], "selected" to (it == currentSearchBox)) }
    val tocModeLabels = mapOf("left" to "Left", "right" to "Right", "off" to "Disabled")
    val currentTocMode = settings.get(siteId, s.SITE_TOC_MODE)?.ifBlank { null } ?: s.DEFAULT_TOC_MODE
    val tocModeOptions = s.TOC_MODE_OPTIONS.map { mapOf("value" to it, "label" to tocModeLabels[it], "selected" to (it == currentTocMode)) }
    // Theme: site default color mode.
    val themeLabels = mapOf("light" to "Light", "dark" to "Dark", "auto" to "Auto (match user's device)")
    val currentTheme = settings.get(siteId, s.APPEARANCE_THEME)?.ifBlank { null } ?: s.DEFAULT_THEME
    val themeOptions = s.THEME_OPTIONS.map { mapOf("value" to it, "label" to themeLabels[it], "selected" to (it == currentTheme)) }
    // Asset delivery: instance-wide deployment config, shown read-only alongside the typography settings.
    val ui = appContext.config.ui
    // Typography: body/heading font preset selects (+ whether the current pick is "custom").
    val bodyFontKey = settings.get(siteId, s.APPEARANCE_BODY_FONT)?.ifBlank { null } ?: s.DEFAULT_BODY_FONT
    val headingFontKey = settings.get(siteId, s.APPEARANCE_HEADING_FONT)?.ifBlank { null } ?: s.DEFAULT_HEADING_FONT
    fun fontOptions(selected: String) = s.FONT_PRESETS.map { mapOf("value" to it.key, "label" to it.label, "selected" to (it.key == selected)) }
    // Locale checklist: curated common locales, plus the default and any configured-but-uncommon code
    // merged in (so nothing is lost). The default is always checked + disabled.
    val defaultLocale = appContext.config.defaultLocale
    val enabledLocales = settings.enabledLocales(siteId, defaultLocale).toSet()
    val knownCodes = s.COMMON_LOCALES.map { it.first }.toSet()
    val extraCodes = enabledLocales.filter { it !in knownCodes }.sorted()
    // Per-locale content counts, so the form can warn before an admin disables a locale that still has
    // pages/assets (they stay live but become hard to find).
    val pageCounts = appContext.pages.countsByLocale(siteId)
    val assetCounts = appContext.assets.countsByLocale(siteId)
    val localeChecklist = (s.COMMON_LOCALES + extraCodes.map { it to it }).map { (code, name) ->
        mapOf(
            "code" to code,
            "name" to name,
            "checked" to (code in enabledLocales),
            "isDefault" to (code == defaultLocale),
            "pageCount" to (pageCounts[code] ?: 0),
            "assetCount" to (assetCounts[code] ?: 0),
        )
    }
    return adminBaseModel() + mapOf(
        "saved" to saved,
        "reindexed" to reindexed,
        // Rendering page: outcome of a just-run "Re-render all pages" (null = not run).
        "rerendered" to (rerendered != null),
        "rerenderedCount" to rerendered,
        "editorPlainView" to settings.getBool(siteId, s.EDITOR_PLAIN_VIEW),
        // Raw stored values for the form fields (empty string when unset).
        "siteNameValue" to settings.get(siteId, s.SITE_NAME).orEmpty(),
        "siteNameDefault" to s.DEFAULT_SITE_NAME,
        // Locales: the configured default (read-only) + a checklist of enableable locales.
        "defaultLocaleValue" to appContext.config.defaultLocale,
        "localeChecklist" to localeChecklist,
        // When on, unprefixed paths redirect to the primary locale; off = serve them as-is.
        "localeForcePrefix" to settings.getBool(siteId, s.LOCALE_FORCE_PREFIX),
        // Rendering toggles (defaults from RenderOptions: iframes/style off, autolink on).
        "renderAllowIframes" to settings.getBool(siteId, s.RENDER_ALLOW_IFRAMES, renderDefaults.allowIframes),
        "renderAllowStyle" to settings.getBool(siteId, s.RENDER_ALLOW_STYLE, renderDefaults.allowStyleAttr),
        "renderAutolink" to settings.getBool(siteId, s.RENDER_AUTOLINK, renderDefaults.autoLink),
        "renderLineBreaks" to settings.getBool(siteId, s.RENDER_LINE_BREAKS, renderDefaults.lineBreaks),
        // External-link icon mode: option list for the <select> (logic-less template), default off.
        "renderExternalLinkModes" to run {
            val current = settings.get(siteId, s.RENDER_EXTERNAL_LINK_ICON)
                ?.takeIf { it in s.EXTERNAL_LINK_MODE_OPTIONS } ?: s.DEFAULT_EXTERNAL_LINK_ICON
            val labels = mapOf(
                "off" to "No icon",
                "site" to "Links outside this wiki site (default)",
                "instance" to "Links outside any wiki site hosted on this instance",
            )
            s.EXTERNAL_LINK_MODE_OPTIONS.map { mapOf("value" to it, "label" to labels[it], "selected" to (it == current)) }
        },
        "siteBrandColorValue" to settings.get(siteId, s.SITE_BRAND_COLOR).orEmpty(),
        "siteBrandColorDarkValue" to settings.get(siteId, s.SITE_BRAND_COLOR_DARK).orEmpty(),
        "siteHeaderColorValue" to settings.get(siteId, s.SITE_HEADER_COLOR).orEmpty(),
        "siteHeaderColorDarkValue" to settings.get(siteId, s.SITE_HEADER_COLOR_DARK).orEmpty(),
        "siteSidebarColorValue" to settings.get(siteId, s.SITE_SIDEBAR_COLOR).orEmpty(),
        "siteSidebarColorDarkValue" to settings.get(siteId, s.SITE_SIDEBAR_COLOR_DARK).orEmpty(),
        "siteSidebarHeaderLineColorValue" to settings.get(siteId, s.SITE_SIDEBAR_HEADER_LINE_COLOR).orEmpty(),
        "siteSidebarHeaderLineColorDarkValue" to settings.get(siteId, s.SITE_SIDEBAR_HEADER_LINE_COLOR_DARK).orEmpty(),
        "siteNavHeadingColorValue" to settings.get(siteId, s.SITE_NAV_HEADING_COLOR).orEmpty(),
        "siteNavHeadingColorDarkValue" to settings.get(siteId, s.SITE_NAV_HEADING_COLOR_DARK).orEmpty(),
        "siteHeadingLineColorValue" to settings.get(siteId, s.SITE_HEADING_LINE_COLOR).orEmpty(),
        // Logo / favicon: the currently-chosen image-asset URL ("" = the bundled default). Picked via the
        // asset browser; the POST handler re-validates the URL against the uploaded image assets.
        "logoValue" to logoValue,
        "faviconValue" to faviconValue,
        // Footer + SEO.
        "siteOrgNameValue" to settings.get(siteId, s.SITE_ORG_NAME).orEmpty(),
        "siteContentLicenseValue" to settings.get(siteId, s.SITE_CONTENT_LICENSE).orEmpty(),
        "siteFooterOverrideValue" to settings.get(siteId, s.SITE_FOOTER_OVERRIDE).orEmpty(),
        "siteDescriptionValue" to settings.get(siteId, s.SITE_DESCRIPTION).orEmpty(),
        "siteRobotsOptions" to siteRobotsOptions,
        "searchBoxOptions" to searchBoxOptions,
        "tocModeOptions" to tocModeOptions,
        "themeOptions" to themeOptions,
        "showThemePicker" to settings.getBool(siteId, s.APPEARANCE_SHOW_THEME_PICKER, true),
        "headHtmlValue" to settings.get(siteId, s.APPEARANCE_HEAD_HTML).orEmpty(),
        "bodyHtmlValue" to settings.get(siteId, s.APPEARANCE_BODY_HTML).orEmpty(),
        // Typography + custom styling.
        "bodyFontOptions" to fontOptions(bodyFontKey),
        "headingFontOptions" to fontOptions(headingFontKey),
        "bodyFontIsCustom" to (bodyFontKey == "custom"),
        "headingFontIsCustom" to (headingFontKey == "custom"),
        "bodyFontCustomValue" to settings.get(siteId, s.APPEARANCE_BODY_FONT_CUSTOM).orEmpty(),
        "headingFontCustomValue" to settings.get(siteId, s.APPEARANCE_HEADING_FONT_CUSTOM).orEmpty(),
        "baseFontSizeValue" to (settings.get(siteId, s.APPEARANCE_BASE_FONT_SIZE)?.toIntOrNull() ?: s.DEFAULT_BASE_FONT_SIZE),
        "emojiFontValue" to settings.getBool(siteId, s.APPEARANCE_EMOJI_FONT, s.DEFAULT_EMOJI_FONT),
        // Read-only view of the instance-wide asset-delivery config (UiConfig), so the Appearance page can
        // show where each front-end asset actually comes from and which env var changes it.
        "assetSources" to listOf(
            mapOf(
                "label" to "Bootstrap, highlight.js", "size" to "~440 KB", "env" to "WIKIKT_UI_ASSET_SOURCE",
                "cdn" to ui.useCdnAssets, "host" to "cdn.jsdelivr.net", "path" to "/static/vendor/",
            ),
            mapOf(
                "label" to "Icon font (Material Design Icons)", "size" to "~750 KB", "env" to "WIKIKT_UI_ICON_FONT_SOURCE",
                "cdn" to ui.useCdnIconFont, "host" to "cdn.jsdelivr.net", "path" to "/static/vendor/mdi/",
            ),
            mapOf(
                "label" to "Emoji font (Noto Color Emoji)", "size" to "~2 MB", "env" to "WIKIKT_UI_EMOJI_FONT_SOURCE",
                "cdn" to ui.useCdnEmojiFont, "host" to "fonts.googleapis.com", "path" to "/static/vendor/noto-emoji/",
            ),
        ),
        "customCssValue" to settings.get(siteId, s.APPEARANCE_CUSTOM_CSS).orEmpty(),
        "currentYear" to java.time.Year.now(java.time.ZoneId.systemDefault()).value,
    )
}

/** Model for the Administration > Authentication > Registration page (self-service sign-up settings). */
internal suspend fun io.ktor.server.application.ApplicationCall.registrationModel(
    saved: Boolean = false,
): Map<String, Any?> {
    val siteId = adminSiteId()
    val settings = appContext.settings
    val s = com.wikikt.service.SettingsService
    // "Default group for new accounts" dropdown — a "None" choice plus every NON-system group. System
    // (root-bearing) groups are never offered: placing every registrant in a root group would be a
    // privilege-escalation footgun (UserService.register refuses it, and the POST handler drops it).
    val currentRegGroup = settings.get(siteId, s.REGISTRATION_DEFAULT_GROUP)?.ifBlank { null }
    val systemGroups = appContext.groups.systemGroupIds()
    val registrationGroupOptions = listOf(
        mapOf("value" to "", "label" to "— None (default User group only) —", "selected" to (currentRegGroup == null)),
    ) + appContext.groups.list().filter { it.id !in systemGroups }.map { it.toDto() }.map { g ->
        mapOf("value" to g.id, "label" to g.name, "selected" to (g.id == currentRegGroup))
    }
    return adminBaseModel() + mapOf(
        "saved" to saved,
        "registrationEnabled" to settings.getBool(siteId, s.REGISTRATION_ENABLED),
        "registrationRequireApproval" to settings.getBool(siteId, s.REGISTRATION_REQUIRE_APPROVAL),
        "registrationAllowedDomainsValue" to settings.get(siteId, s.REGISTRATION_ALLOWED_DOMAINS).orEmpty(),
        "registrationGroupOptions" to registrationGroupOptions,
        // When mail is off, registration can't deliver a confirmation link — the form warns and stays inert.
        "mailConfiguredForRegistration" to settings.getBool(siteId, s.MAIL_ENABLED),
    )
}

/** Branch names we accept from the Git Sync form (conservative subset of valid git refs). */
private val GIT_BRANCH_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")

private val GIT_SYNC_MODE_LABELS = mapOf(
    "off" to "Off",
    "push" to "Push to target",
    "pull" to "Pull from target",
    "bidirectional" to "Bidirectional (pull then push)",
)

private val GIT_SYNC_INTERVAL_LABELS = mapOf(
    0 to "Manual only",
    5 to "Every 5 minutes",
    15 to "Every 15 minutes",
    30 to "Every 30 minutes",
    60 to "Every hour",
)

/**
 * Model for the Administration > Git Sync page. A manual run's outcome isn't passed in — it's read
 * from the status settings ([SettingsService.GIT_SYNC_LAST_RESULT] etc.) the background run records,
 * and `syncRunning` reports whether one is still in progress.
 */
internal suspend fun io.ktor.server.application.ApplicationCall.gitSyncModel(
    saved: Boolean = false,
): Map<String, Any?> {
    val siteId = adminSiteId()
    val settings = appContext.settings
    val s = com.wikikt.service.SettingsService
    val currentMode = settings.get(siteId, s.GIT_SYNC_MODE)?.ifBlank { null } ?: "off"
    val modeOptions = s.GIT_SYNC_MODE_OPTIONS.map {
        mapOf("value" to it, "label" to GIT_SYNC_MODE_LABELS[it], "selected" to (it == currentMode))
    }
    val currentInterval = settings.get(siteId, s.GIT_SYNC_INTERVAL_MINUTES)?.toIntOrNull() ?: s.DEFAULT_GIT_SYNC_INTERVAL_MINUTES
    val intervalOptions = s.GIT_SYNC_INTERVAL_OPTIONS.map {
        mapOf("value" to it, "label" to GIT_SYNC_INTERVAL_LABELS[it], "selected" to (it == currentInterval))
    }
    val formats = displayFormats()
    val lastRunAt = settings.get(siteId, s.GIT_SYNC_LAST_RUN_AT)?.toLongOrNull()
    return adminBaseModel() + mapOf(
        "saved" to saved,
        // A manual "Sync now"/export/import is running in the background for this site: the page
        // shows an in-progress banner and auto-refreshes until it clears, then the Last-run panel
        // below reflects the recorded outcome.
        "syncRunning" to appContext.gitSync.isRunning(siteId),
        "modeOptions" to modeOptions,
        "intervalOptions" to intervalOptions,
        "repoUrlValue" to settings.get(siteId, s.GIT_SYNC_REPO_URL).orEmpty(),
        "branchValue" to settings.get(siteId, s.GIT_SYNC_BRANCH).orEmpty(),
        "branchDefault" to s.DEFAULT_GIT_SYNC_BRANCH,
        "usernameValue" to settings.get(siteId, s.GIT_SYNC_USERNAME).orEmpty(),
        // The token itself is never sent back to the browser — only whether one is stored.
        "hasToken" to !settings.get(siteId, s.GIT_SYNC_TOKEN).isNullOrBlank(),
        "authorNameValue" to settings.get(siteId, s.GIT_SYNC_AUTHOR_NAME).orEmpty(),
        "authorEmailValue" to settings.get(siteId, s.GIT_SYNC_AUTHOR_EMAIL).orEmpty(),
        "hasLastRun" to (lastRunAt != null),
        "lastRunAt" to lastRunAt?.let { DateDisplay.format(it, formats) },
        "lastRunOk" to (settings.get(siteId, s.GIT_SYNC_LAST_OK) == "true"),
        "lastRunResult" to settings.get(siteId, s.GIT_SYNC_LAST_RESULT).orEmpty(),
        "lastRunCommit" to settings.get(siteId, s.GIT_SYNC_LAST_COMMIT)?.ifBlank { null },
    )
}

/**
 * Model for the Administration > Storage and backup page, which merges Git Sync ([gitSyncModel]) with
 * backup download/restore. [restored]/[error] carry the outcome of a just-run restore.
 */
internal suspend fun io.ktor.server.application.ApplicationCall.storageModel(
    saved: Boolean = false,
    restored: String? = null,
    error: String? = null,
    historySaved: Boolean = false,
    uploadsSaved: Boolean = false,
    purgeMessage: String? = null,
): Map<String, Any?> {
    val siteId = adminSiteId()
    val s = com.wikikt.service.SettingsService
    val settings = appContext.settings
    // Max files per upload: current effective value + the preset dropdown (config default and any custom
    // stored value are merged in so the current selection is always present, even if off the preset list).
    val maxUploadFiles = settings.uploadFileLimit(siteId, appContext.config.assets.maxFilesPerUpload)
    val uploadFileOptions = (s.UPLOAD_FILE_LIMIT_OPTIONS + appContext.config.assets.maxFilesPerUpload + maxUploadFiles)
        .distinct().sorted().map { mapOf("value" to it, "selected" to (it == maxUploadFiles)) }
    return gitSyncModel(saved) + mapOf(
        "restored" to restored,
        "error" to error,
        // Full backup export/restore dumps or replaces every account/secret, so it's root-only — hide
        // those controls from a delegated manage:groups admin (the server enforces it regardless).
        "isRoot" to appContext.permissions.isRoot(currentUserId()),
        "historySaved" to historySaved,
        "uploadsSaved" to uploadsSaved,
        "purgeMessage" to purgeMessage,
        "maxPageRevisions" to settings.getHistoryLimit(siteId, s.HISTORY_MAX_PAGE_REVISIONS, s.DEFAULT_MAX_PAGE_REVISIONS),
        "maxAssetRevisions" to settings.getHistoryLimit(siteId, s.HISTORY_MAX_ASSET_REVISIONS, appContext.config.assets.maxAssetVersions),
        "maxUploadFiles" to maxUploadFiles,
        "uploadFileOptions" to uploadFileOptions,
        "stripMetadata" to settings.getBool(siteId, s.ASSETS_STRIP_METADATA, s.DEFAULT_STRIP_METADATA),
    )
}

/** Model for the Administration > Security page (per-site Content-Security-Policy). */
internal suspend fun io.ktor.server.application.ApplicationCall.securityModel(
    saved: Boolean = false,
): Map<String, Any?> {
    val siteId = adminSiteId()
    val s = com.wikikt.service.SettingsService
    val settings = appContext.settings
    // One row per loosenable directive: name, baseline (read-only), and the admin's current additions.
    val directives = s.CSP_DIRECTIVES.filter { it.settingKey != null }.map { d ->
        mapOf(
            "name" to d.name,
            "label" to d.label,
            "field" to "csp_" + d.name.replace("-", "_"),
            "baseline" to d.baseline.joinToString(" "),
            "value" to settings.get(siteId, d.settingKey!!).orEmpty(),
        )
    }
    // Fixed directives shown read-only so admins see the whole policy shape.
    val fixed = s.CSP_DIRECTIVES.filter { it.settingKey == null }.map {
        mapOf("name" to it.name, "sources" to it.baseline.joinToString(" "))
    }
    val effective = settings.contentSecurityPolicy(siteId)
    return adminBaseModel() + mapOf(
        "saved" to saved,
        "cspDirectives" to directives,
        "cspFixed" to fixed,
        "cspReportOnly" to settings.getBool(siteId, s.SECURITY_CSP_REPORT_ONLY),
        "cspEffectiveHeader" to effective.name,
        "cspEffectiveValue" to effective.value,
    )
}

/** Bootstrap badge class for an email queue status. */
private fun emailStatusBadge(status: String): String = when (status) {
    com.wikikt.service.EmailStatus.SENT.name -> "text-bg-success"
    com.wikikt.service.EmailStatus.PENDING.name -> "text-bg-secondary"
    com.wikikt.service.EmailStatus.FAILED.name -> "text-bg-warning"
    com.wikikt.service.EmailStatus.DEAD_LETTER.name -> "text-bg-danger"
    else -> "text-bg-secondary"
}

/**
 * Model for Administration > Mail: the per-site SMTP connection settings, the list of editable
 * templates, the recent send queue, and the last-run status. [testMessage] is set after a test send.
 */
internal suspend fun io.ktor.server.application.ApplicationCall.mailModel(
    saved: Boolean = false,
    testMessage: String? = null,
): Map<String, Any?> {
    val siteId = adminSiteId()
    val s = com.wikikt.service.SettingsService
    val settings = appContext.settings
    val formats = displayFormats()
    val cfg = appContext.email.mailSettings(siteId)
    val currentSecurity = cfg.security
    val securityOptions = s.MAIL_SECURITY_OPTIONS.map {
        mapOf("value" to it, "label" to MAIL_SECURITY_LABELS[it], "selected" to (it == currentSecurity))
    }
    val templates = appContext.emailTemplates.defaults().map {
        val content = appContext.emailTemplates.content(siteId, it.key)
        mapOf(
            "key" to it.key,
            "label" to it.label,
            "description" to it.description,
            "customized" to (content?.isDefault == false),
        )
    }
    val counts = appContext.email.queueCounts(siteId)
    val queue = appContext.email.recentQueue(siteId).map {
        mapOf(
            "id" to it.id.toString(),
            "recipient" to it.recipient,
            "template" to (appContext.emailTemplates.default(it.templateKey)?.label ?: it.templateKey),
            "status" to it.status,
            "statusBadge" to emailStatusBadge(it.status),
            "attempts" to it.attempts,
            "lastError" to it.lastError,
            "createdAt" to DateDisplay.format(it.createdAt, formats),
            "sentAt" to it.sentAt?.let { at -> DateDisplay.format(at, formats) },
            // Failed or parked rows can be retried by hand; a sent/pending one can only be deleted.
            "isRetryable" to (it.status == com.wikikt.service.EmailStatus.FAILED.name || it.status == com.wikikt.service.EmailStatus.DEAD_LETTER.name),
        )
    }
    val lastRunAt = settings.get(siteId, s.MAIL_LAST_RUN_AT)?.toLongOrNull()
    return adminBaseModel() + mapOf(
        "saved" to saved,
        "testMessage" to testMessage,
        // Literal Mustache braces for showing placeholder syntax in the help text.
        "mustacheOpen" to "{{",
        "mustacheClose" to "}}",
        "mailEnabled" to cfg.enabled,
        "smtpHost" to cfg.host,
        "smtpPort" to cfg.port,
        "securityOptions" to securityOptions,
        "smtpUsername" to cfg.username,
        // The password is never echoed back — only whether one is stored.
        "hasPassword" to cfg.password.isNotEmpty(),
        "fromAddress" to cfg.fromAddress,
        "fromName" to (cfg.fromName ?: ""),
        "adminRecipients" to settings.get(siteId, s.MAIL_ADMIN_RECIPIENTS).orEmpty(),
        "isConfigured" to cfg.isConfigured,
        "templates" to templates,
        "queue" to queue,
        "hasQueue" to queue.isNotEmpty(),
        "countPending" to counts.pending,
        "countSent" to counts.sent,
        "countFailed" to counts.failed,
        "countDeadLetter" to counts.deadLetter,
        "hasLastRun" to (lastRunAt != null),
        "lastRunAt" to lastRunAt?.let { DateDisplay.format(it, formats) },
        "lastRunOk" to (settings.get(siteId, s.MAIL_LAST_OK) == "true"),
        "lastRunResult" to settings.get(siteId, s.MAIL_LAST_RESULT).orEmpty(),
    )
}

/** Human labels for the SMTP security options. */
private val MAIL_SECURITY_LABELS = mapOf(
    "starttls" to "STARTTLS (port 587)",
    "ssl" to "SSL/TLS (port 465)",
    "none" to "None (unencrypted)",
)

/**
 * Model for the single-template editor ([admin/mail-template.hbs]): the effective subject/bodies for
 * [key] (override or default), the built-in defaults (for the "reset" affordance), and the list of
 * Mustache variables the template may reference.
 */
internal suspend fun io.ktor.server.application.ApplicationCall.mailTemplateModel(
    key: String,
    saved: Boolean = false,
    reset: Boolean = false,
): Map<String, Any?> {
    val siteId = adminSiteId()
    val def = appContext.emailTemplates.default(key)!!
    val content = appContext.emailTemplates.content(siteId, key)!!
    return adminBaseModel() + mapOf(
        "saved" to saved,
        "reset" to reset,
        "mustacheOpen" to "{{",
        "mustacheClose" to "}}",
        "key" to key,
        "label" to def.label,
        "description" to def.description,
        "subject" to content.subject,
        "textBody" to content.text,
        "htmlBody" to (content.html ?: ""),
        "isDefault" to content.isDefault,
        "variables" to def.variables.map { mapOf("name" to it) },
    )
}

/** Sidebar sections; each maps to a `nav_<id>` boolean the sidebar partial uses to highlight the active one. */
private val ADMIN_NAV_SECTIONS = listOf(
    "dashboard", "sites", "general", "appearance", "locale", "rendering", "navigation",
    "pages", "fragments", "infoboxes", "users", "groups", "apikeys", "registration", "storage", "security", "mail",
    "updates",
)

// --- Infobox admin helpers ---

/** A template's editable fields from the admin form: identity + the field list as both raw text and parsed. */
private data class InfoboxTemplateFields(
    val slug: String,
    val name: String,
    val description: String,
    val fieldsText: String,
    val fieldDefs: List<InfoboxFieldDef>,
)

private val INFOBOX_FIELD_TYPES = setOf("string", "select", "boolean", "array")
private val SLUG_PATTERN = Regex("[a-z0-9_-]+")

/**
 * Parses the fields textarea (one field per line: `name | label | type | options | help`, where a `*`
 * on the type marks it required and options are comma-separated for select/array) into field defs.
 * Blank lines and lines without a name are skipped; an unknown type falls back to string.
 */
private fun parseInfoboxFieldLines(text: String): List<InfoboxFieldDef> = text.lines().mapNotNull { line ->
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return@mapNotNull null
    val parts = trimmed.split("|").map { it.trim() }
    val name = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    val label = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: name
    var type = parts.getOrNull(2)?.lowercase()?.ifBlank { "string" } ?: "string"
    val required = type.endsWith("*")
    type = type.removeSuffix("*").trim()
    if (type !in INFOBOX_FIELD_TYPES) type = "string"
    val options = parts.getOrNull(3)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    val help = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }
    InfoboxFieldDef(name = name, label = label, type = type, required = required, help = help, options = options)
}

/** Serializes field defs back to the textarea line format, trimming trailing empty columns. */
private fun fieldsToText(fields: List<InfoboxFieldDef>): String = fields.joinToString("\n") { f ->
    val cols = listOf(
        f.name,
        f.label,
        f.type + if (f.required) "*" else "",
        f.options.joinToString(", "),
        f.help.orEmpty(),
    )
    var end = cols.size
    while (end > 1 && cols[end - 1].isEmpty()) end--
    cols.subList(0, end).joinToString(" | ")
}

private fun infoboxSlugify(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "template" }

private fun io.ktor.server.application.ApplicationCall.infoboxTemplateFields(params: io.ktor.http.Parameters): InfoboxTemplateFields {
    val name = params["name"]?.trim().orEmpty()
    val slug = params["slug"]?.trim()?.ifBlank { null } ?: infoboxSlugify(name)
    val description = params["description"]?.trim().orEmpty()
    val fieldsText = params["fields"].orEmpty()
    return InfoboxTemplateFields(slug, name, description, fieldsText, parseInfoboxFieldLines(fieldsText))
}

private suspend fun io.ktor.server.application.ApplicationCall.infoboxTemplateError(fields: InfoboxTemplateFields, excludeId: UInt?): String? {
    if (fields.name.isBlank()) return "Name is required."
    if (fields.slug.isBlank()) return "Slug is required."
    if (!SLUG_PATTERN.matches(fields.slug)) return "Slug may contain only lowercase letters, numbers, hyphens and underscores."
    val existing = appContext.infobox.templateBySlug(adminSiteId(), fields.slug)
    if (existing != null && existing.id != excludeId) return "A template with the slug '${fields.slug}' already exists on this site."
    return null
}

private suspend fun io.ktor.server.application.ApplicationCall.infoboxFormModel(
    isNew: Boolean,
    id: String? = null,
    fields: InfoboxTemplateFields = InfoboxTemplateFields("", "", "", "", emptyList()),
    error: String? = null,
): Map<String, Any?> = adminBaseModel() + mapOf(
    "isNew" to isNew,
    "id" to id,
    "slug" to fields.slug,
    "name" to fields.name,
    "description" to fields.description,
    "fieldsText" to fields.fieldsText,
    "error" to error,
)

private suspend fun io.ktor.server.application.ApplicationCall.infoboxListModel(): Map<String, Any?> {
    val siteId = adminSiteId()
    val templates = appContext.infobox.listTemplates(siteId)
    val nameById = templates.associate { it.id to it.name }
    val templateModels = templates.map {
        mapOf(
            "id" to it.id.toString(), "slug" to it.slug, "name" to it.name,
            "fieldCount" to it.fields.size, "description" to it.description,
        )
    }
    val rules = appContext.infobox.listPathRules(siteId).map { r ->
        mapOf(
            "id" to r.id.toString(),
            "isTag" to (r.matchType == InfoboxService.MATCH_TAG),
            "pattern" to r.pattern,
            "templateName" to (nameById[r.templateId] ?: "—"),
        )
    }
    val templateOptions = templates.map { mapOf("id" to it.id.toString(), "name" to it.name) }
    return adminBaseModel() + mapOf(
        "templates" to templateModels,
        "hasTemplates" to templateModels.isNotEmpty(),
        "rules" to rules,
        "hasRules" to rules.isNotEmpty(),
        "templateOptions" to templateOptions,
        "hasTemplateOptions" to templateOptions.isNotEmpty(),
        "otherSite" to (request.queryParameters["otherSite"] != null),
    )
}

/**
 * Where the site switcher lands after changing sites. Resource-specific per-site pages (editing a nav
 * menu or fragment by id) would point at the OLD site's resource, so those collapse to their section's
 * list — which re-renders for the newly-managed site. Everything else (lists, per-site settings pages)
 * re-renders fine at the same URL, so it's kept.
 */
private fun safeSwitcherReturn(path: String): String = when {
    path.startsWith("/a/navigation/") -> "/a/navigation"
    path.startsWith("/a/fragments/") -> "/a/fragments"
    path.startsWith("/a/infoboxes/") -> "/a/infoboxes"
    else -> path
}

/** Which sidebar section the given admin request path belongs to (drives the active highlight). */
private fun adminActiveSection(path: String): String = when {
    path.startsWith("/a/sites") -> "sites"
    path.startsWith("/a/settings/appearance") -> "appearance"
    path.startsWith("/a/settings/locale") -> "locale"
    path.startsWith("/a/settings/rendering") -> "rendering"
    path.startsWith("/a/settings") -> "general"
    path.startsWith("/a/navigation") -> "navigation"
    path.startsWith("/a/pages") -> "pages"
    path.startsWith("/a/fragments") -> "fragments"
    path.startsWith("/a/infoboxes") -> "infoboxes"
    path.startsWith("/a/users") -> "users"
    path.startsWith("/a/api-keys") -> "apikeys"
    path.startsWith("/a/groups") -> "groups"
    path.startsWith("/a/registration") -> "registration"
    path.startsWith("/a/storage") || path.startsWith("/a/git-sync") || path.startsWith("/a/backup") -> "storage"
    path.startsWith("/a/security") -> "security"
    path.startsWith("/a/mail") -> "mail"
    path.startsWith("/a/updates") -> "updates"
    else -> "dashboard"
}

internal suspend fun io.ktor.server.application.ApplicationCall.adminBaseModel(): Map<String, Any?> {
    val ctx = appContext
    val userId = currentUserId()
    val username = userId?.let { ctx.users.findById(it)?.username }
    val canUsers = requireManageUsers()
    val canGroups = requireManageGroups()
    val canPages = requireManagePages()
    val canNav = requireManageNavigation()
    // Highlight the sidebar item for the current URL — derived here so no route/model has to pass it.
    val active = adminActiveSection(request.uri.substringBefore('?'))
    // Site switcher: which site the admin console is managing, plus the list to switch to.
    val allSites = ctx.sites.all()
    val managedId = adminSiteId()
    val returnPath = safeSwitcherReturn(request.uri.substringBefore('?'))
    return mapOf(
        "username" to username,
        "loggedIn" to (userId != null),
        "canManageUsers" to canUsers,
        "canManageGroups" to canGroups,
        "canManagePages" to canPages,
        // Sidebar section/item gating. Kept under dedicated `navSec*` keys because some admin pages (e.g.
        // the group editor) reuse `canManage*` for the *edited group's* capabilities, which would otherwise
        // override the logged-in user's and hide sidebar sections.
        // Dashboard link only for users who can actually open it (nav-only editors can't).
        "navSecDashboard" to (canUsers || canGroups || canPages),
        // Site section: General/Appearance/Locale need group admin; Navigation has its own capability.
        "navShowSite" to (canGroups || canNav),
        "navSecSite" to canGroups,
        "navSecNav" to canNav,
        // Content section: Pages needs a content-write grant (manage:pages), but Fragments and Infoboxes
        // render site-wide, so they're admin-gated (manage:groups) like the rest of the site-wide console.
        // The section header shows if either applies; each link is gated individually below.
        "navSecContent" to (canPages || canGroups),
        "navContentPages" to canPages,
        "navContentFragments" to canGroups,
        "navSecUsers" to canUsers,
        "navSecGroups" to canGroups,
        // The Authentication group shows if the user can reach any item under it.
        "navSecAuth" to (canUsers || canGroups),
        // Root-only items (Updates). Separate from navSecSite (manage:groups): a delegated group
        // admin must not be shown links that 403 on click.
        "navSecRoot" to ctx.permissions.isRoot(userId),
        "csrfField" to csrfField(),
        // The primary locale, for admin JS (e.g. the nav editor's page picker builds targets from it).
        "defaultLocale" to ctx.config.defaultLocale,
        // Header search box: admin pages aren't locale-scoped, so default to the site locale.
        "searchLocale" to ctx.config.defaultLocale,
        "searchQ" to "",
        // Marks the admin/tools area so the header swaps the Administration gear for an "Exit" button.
        "adminArea" to true,
        // Site switcher (shown to group admins): the managed site's name + the options to switch to.
        "showSiteSwitcher" to canGroups,
        "managedSiteName" to (allSites.firstOrNull { it.id == managedId }?.name ?: "—"),
        "switcherReturn" to returnPath,
        "switcherSites" to allSites.map {
            mapOf("id" to it.id.toString(), "name" to it.name, "current" to (it.id == managedId))
        },
    ) + ADMIN_NAV_SECTIONS.associate { "nav_$it" to (it == active) }
}

/** Model for the Administration > Sites page. [deleteResult] is set after a delete attempt. */
internal suspend fun io.ktor.server.application.ApplicationCall.sitesModel(
    deleteResult: SiteDeleteResult? = null,
    saved: Boolean = false,
): Map<String, Any?> {
    val managedId = adminSiteId()
    val sites = appContext.sites.all().map {
        mapOf(
            "id" to it.id.toString(),
            "name" to it.name,
            "hostname" to it.hostname,
            "hasHostname" to (it.hostname != null),
            "isCatchAll" to it.isCatchAll,
            "current" to (it.id == managedId),
        )
    }
    val deleteError = when (deleteResult) {
        SiteDeleteResult.IS_CATCHALL -> "You can't delete the catch-all site. Make another site the catch-all first."
        SiteDeleteResult.NOT_FOUND -> "Site not found."
        else -> null
    }
    return adminBaseModel() + mapOf(
        "sites" to sites,
        "deleted" to (deleteResult == SiteDeleteResult.DELETED),
        "deleteError" to deleteError,
        "saved" to saved,
    )
}

/**
 * Model for the create/edit-site form ([admin/site-edit.hbs]). [editId] null = the "new site" form.
 * The optional field values are echoed back when re-rendering after a validation error; otherwise the
 * form is seeded from the stored site (or blank for a new one).
 */
internal suspend fun io.ktor.server.application.ApplicationCall.siteEditModel(
    editId: UInt?,
    error: String? = null,
    name: String? = null,
    hostname: String? = null,
    isCatchAll: Boolean? = null,
): Map<String, Any?> {
    val existing = editId?.let { appContext.sites.byId(it) }
    val managing = existing != null && existing.id == adminSiteId()
    return adminBaseModel() + mapOf(
        "isNew" to (editId == null),
        "editId" to editId?.toString(),
        "formAction" to if (editId == null) "/a/sites" else "/a/sites/$editId",
        "nameValue" to (name ?: existing?.name ?: ""),
        "hostnameValue" to (hostname ?: existing?.hostname ?: ""),
        "isCatchAll" to (isCatchAll ?: existing?.isCatchAll ?: false),
        // Whether this is the site the console is currently managing (drives the "manage settings" hint).
        "isManaging" to managing,
        "error" to error,
    )
}

/**
 * Handles a create (editId null) or update site POST: validates the hostname is unique, then persists
 * and PRG-redirects to the list; on a hostname clash re-renders the form with the entered values.
 */
private suspend fun io.ktor.server.application.ApplicationCall.handleSiteSave(editId: UInt?) {
    val params = receiveParameters()
    if (!validateFormCsrf(params)) return
    val name = params["name"]?.trim().orEmpty().ifBlank { "Untitled site" }
    val hostname = params["hostname"]?.trim()?.lowercase()?.ifBlank { null }
    val isCatchAll = params["isCatchAll"] != null
    if (editId != null && appContext.sites.byId(editId) == null) {
        respond(HttpStatusCode.NotFound)
        return
    }
    // Hostnames are unique across sites (a request maps to at most one site).
    val clash = hostname?.let { appContext.sites.byHostname(it) }
    if (clash != null && clash.id != editId) {
        respond(
            MustacheContent(
                "admin/site-edit.hbs",
                siteEditModel(editId, error = "The hostname “$hostname” is already used by “${clash.name}”.", name = name, hostname = hostname, isCatchAll = isCatchAll),
            ),
        )
        return
    }
    if (editId == null) {
        // A brand-new site starts empty; give it the same starter home page + Home-link sidebar the
        // first-run install gets, so its hostname doesn't open on a 404 and a blank nav.
        val created = appContext.sites.create(name, hostname, isCatchAll)
        appContext.seed.seedNewSite(created.id)
    } else {
        appContext.sites.update(editId, name, hostname, isCatchAll)
    }
    respondRedirect("/a/sites?saved=1")
}
