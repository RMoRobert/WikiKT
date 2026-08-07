package com.wikikt

import com.wikikt.auth.UserSession
import com.wikikt.auth.csrfField
import com.wikikt.auth.csrfToken
import com.wikikt.routing.configureAccountRouting
import com.wikikt.routing.configureAdminRouting
import com.wikikt.routing.configureAssetRouting
import com.wikikt.routing.configureBackupRouting
import com.wikikt.routing.configureApiRouting
import com.wikikt.routing.HOME_PAGE_PATH
import com.wikikt.routing.configureAuthRouting
import com.wikikt.routing.currentUserId
import com.wikikt.routing.errorModel
import com.wikikt.routing.configureSearchRouting
import com.wikikt.routing.configureTagRouting
import com.wikikt.routing.configureWikiRouting
import com.wikikt.config.isProductionEnvironment
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun Application.module() {
    configureSerialization()
    configureMustache()
    configureSecurity()

    // Compress text responses for deployments where clients reach the app directly (bare jar, or a
    // proxy/LB that doesn't compress). The bundled Caddy stack instead strips Accept-Encoding toward
    // the app (docker/Caddyfile) and compresses at the edge, where zstd is also on offer. Only
    // text-shaped types are matched — images, fonts, and archives are already compressed.
    install(Compression) {
        gzip {
            matchContentType(ContentType.Text.Any, ContentType.Application.Json, ContentType.Application.JavaScript, ContentType.Image.SVG)
            minimumSize(1024)
        }
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            // Log the real reason server-side, but never echo internal messages to the client.
            call.application.environment.log.debug("Bad request", cause)
            call.respond(HttpStatusCode.BadRequest, "Bad request")
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            // Preserve the 404 status — MustacheContent alone would send the error page as 200. A missing
            // wiki page stashes a richer model (chrome + "create page" offer); use it when present.
            val rich = call.attributes.getOrNull(com.wikikt.routing.NotFoundModelKey)
            if (rich != null) {
                call.respond(HttpStatusCode.NotFound, MustacheContent("page/not-found.hbs", rich))
            } else {
                call.respond(HttpStatusCode.NotFound, MustacheContent("error.hbs", call.errorModel("Not found", 404)))
            }
        }
    }

    runBlocking {
        createAppContext()
    }

    // Return pooled connections to the database on shutdown instead of letting the process drop them.
    monitor.subscribe(ApplicationStopping) { com.wikikt.db.DatabaseFactory.close() }

    // Inject site branding (name, logo, brand color) + the rendered footer into every Mustache page
    // model, so every <title>/header/footer reflects the admin-configured site without each route
    // knowing about it. Runs before the Mustache plugin renders, so it sees the raw MustacheContent.
    // Page-supplied keys win over branding defaults (none currently collide). Which site's branding
    // that is comes from chromeSiteId(): the request's site everywhere except the admin console, which
    // wears the branding of the site it is managing.
    sendPipeline.intercept(ApplicationSendPipeline.Before) { message ->
        if (message is MustacheContent && message.model is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val call = context as ApplicationCall
            val ctx = call.appContext
            val year = java.time.Year.now(java.time.ZoneId.systemDefault()).value
            // Header account identity, resolved once here so every page's account menu has it (routes
            // may still set their own username/loggedIn; those win on collision — same values).
            val currentUser = call.currentUserId()?.let { ctx.users.findById(it) }
            // A logged-in user's saved theme overrides the site default (from brandingModel); merged
            // after it so it wins. Guests fall through to the site default (+ their localStorage choice).
            val s = com.wikikt.service.SettingsService
            val userTheme = currentUser?.theme?.takeIf { it in s.THEME_OPTIONS }
            val chromeSiteId = call.chromeSiteId()
            // Content width: like the theme, a per-account override of the site default, plus a
            // per-request `?fullWidth=true|false` (the only override available to guests), which wins
            // over the saved preference. Either/both ignored when the admin has allowed user choice on the
            // Appearance page; otherwise the site default from brandingModel stands untouched.
            val widthOverride = if (ctx.settings.getBool(chromeSiteId, s.APPEARANCE_CONTENT_WIDTH_USER_CHOICE)) {
                call.request.queryParameters["fullWidth"]?.toBooleanStrictOrNull()
                    ?: currentUser?.contentWidth?.takeIf { it in s.CONTENT_WIDTH_OPTIONS }
                        ?.let { it == s.CONTENT_WIDTH_FULL }
            } else {
                null
            }
            val merged = mapOf(
                "assetsCdn" to ctx.config.ui.useCdnAssets,
                // Webfont hosts, each independent of assetsCdn (see UiConfig.useCdnEmojiFont / useCdnIconFont).
                "emojiFontCdn" to ctx.config.ui.useCdnEmojiFont,
                "iconFontCdn" to ctx.config.ui.useCdnIconFont,
                // Mermaid, loaded lazily by page-mermaid.js (see UiConfig.useCdnMermaid).
                "mermaidCdn" to ctx.config.ui.useCdnMermaid,
                // Session CSRF hidden-input on every page (empty when logged out), so shared
                // chrome — e.g. the header's POST /logout form — always has a token to submit.
                "csrfField" to call.csrfField(),
                // Raw CSRF token for JS fetches (the header theme switch persists a logged-in user's pick).
                "csrfToken" to (call.csrfToken() ?: ""),
                "loggedIn" to (currentUser != null),
                "username" to currentUser?.username,
                "displayName" to currentUser?.displayName?.takeIf { it.isNotBlank() },
                // Cache-busting token appended (?v=) to every local /static URL, paired with the
                // long-lived Cache-Control on the /static route below.
                "assetVersion" to BuildInfo.assetVersion,
            ) +
                ctx.settings.brandingModel(chromeSiteId, ctx.markdown, year) +
                (userTheme?.let { mapOf("themeMode" to it) } ?: emptyMap()) +
                (widthOverride?.let { mapOf("contentWidthFull" to it) } ?: emptyMap()) +
                (message.model as Map<String, Any?>)
            proceedWith(MustacheContent(message.template, merged, message.etag, message.contentType))
        }
    }

    startPublishScheduler()
    startGitSyncScheduler()
    startEmailWorker()

    val production = environment.config.isProductionEnvironment()
    routing {
        staticResources("/static", "static") {
            // Every local /static reference carries ?v=assetVersion, so production can cache hard for a
            // year — a new build changes the token and browsers fetch fresh files immediately.
            // `immutable` additionally spares revalidating the versioned URLs on plain reloads (Ktor's
            // CacheControl.MaxAge can't express it, hence the literal). Dev keeps today's no-header
            // behavior so edits show on a plain reload.
            if (production) {
                cacheControl {
                    listOf(object : CacheControl(Visibility.Public) {
                        override fun toString() = "public, max-age=31536000, immutable"
                    })
                }
            }
        }

        // Bundled default favicon + logo, served at root paths. Admin-selected assets (site.faviconUrl
        // / site.logoUrl) override them via the page <head> and header markup. Both defaults are
        // referenced from every page without a version token, and previously went out header-less —
        // no validator, no freshness — inviting a refetch per page view. So: bytes read once, an
        // assetVersion ETag turns refetches into 304s (a new build changes it), and production adds a
        // day of freshness so repeat views skip the request entirely.
        val bundledSvgEtag = "\"${BuildInfo.assetVersion}\""
        for (name in listOf("favicon.svg", "logo.svg")) {
            val bytes = environment.classLoader.getResourceAsStream("static/$name")?.readBytes()
            get("/$name") {
                if (bytes == null) return@get call.respond(HttpStatusCode.NotFound)
                if (call.request.header(HttpHeaders.IfNoneMatch) == bundledSvgEtag) {
                    return@get call.respond(HttpStatusCode.NotModified)
                }
                call.response.headers.append(HttpHeaders.ETag, bundledSvgEtag)
                if (production) call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=86400")
                call.respondBytes(bytes, ContentType.Image.SVG)
            }
        }

        // Liveness probe for the container healthcheck (and any external monitor): no database, no
        // session, no site resolution -- just checks for "is the server process serving HTTP?". Kept
        // separate from `/` (which does a DB-backed settings read and redirects) so health polling
        // is cheap, versionless, and unauthenticated.
        get("/healthz") {
            call.respondText("ok", ContentType.Text.Plain)
        }

        get("/") {
            // Send to the home page (the reserved `home` path). When locale prefixes are forced, use the
            // canonical locale-qualified URL (/<locale>/home); otherwise the unprefixed /home.
            val ctx = call.appContext
            val forcePrefix = ctx.settings.getBool(call.siteId(), com.wikikt.service.SettingsService.LOCALE_FORCE_PREFIX)
            call.respondRedirect(
                if (forcePrefix) "/${ctx.config.defaultLocale}/$HOME_PAGE_PATH" else "/$HOME_PAGE_PATH",
            )
        }
        configureAuthRouting()
        configureAccountRouting()
        configureAdminRouting()
        configureBackupRouting()
        configureAssetRouting()
        configureApiRouting()
        configureSearchRouting()
        configureTagRouting()
        configureWikiRouting()
    }
}

/**
 * Background loop that publishes scheduled drafts. Single-instance only: it polls every minute
 * rather than using a distributed scheduler. Runs on the application's coroutine scope, so it's
 * cancelled when the server stops. For HA, add row-locking or a shared job store.
 */
private fun Application.startPublishScheduler() {
    launch {
        while (isActive) {
            delay(60_000)
            val now = System.currentTimeMillis()
            val ctx = appContext
            // Each subsystem is isolated so one failure doesn't starve the others.
            runCatching { ctx.pages.publishScheduled(now) }
                .onFailure { environment.log.warn("Scheduled-publish run failed", it) }
            runCatching { ctx.pages.promoteScheduledStaged(now) }
                .onFailure { environment.log.warn("Staged-promotion run failed", it) }
            runCatching {
                ctx.assets.promoteScheduledReplacements(now, ctx.config.assets.allowedMimeTypes) { site ->
                    ctx.settings.getHistoryLimit(
                        site, com.wikikt.service.SettingsService.HISTORY_MAX_ASSET_REVISIONS, ctx.config.assets.maxAssetVersions,
                    )
                }
            }.onFailure { environment.log.warn("Scheduled asset-replacement run failed", it) }
            runCatching { ctx.sessions.purgeExpired(now) }
                .onFailure { environment.log.warn("Expired-session purge failed", it) }
            runCatching { ctx.passwordReset.purgeExpired(now) }
                .onFailure { environment.log.warn("Expired password-reset-token purge failed", it) }
            runCatching { ctx.emailVerification.purgeExpired(now) }
                .onFailure { environment.log.warn("Expired email-verification-token purge failed", it) }
            // Drop unconfirmed self-registrations older than the confirmation-link TTL, freeing their usernames.
            runCatching { ctx.users.purgeUnverified(now - com.wikikt.service.EmailVerificationService.DEFAULT_TTL_MILLIS) }
                .onFailure { environment.log.warn("Unverified-account purge failed", it) }
        }
    }
}

/**
 * Background loop for scheduled git synchronization. Its own coroutine (not part of
 * [startPublishScheduler]) so a slow git network operation never delays the publish/purge work.
 * The tick itself decides whether a sync is due (mode enabled + interval elapsed since last run).
 */
private fun Application.startGitSyncScheduler() {
    launch {
        while (isActive) {
            delay(60_000)
            runCatching { appContext.gitSync.autoSyncTick(System.currentTimeMillis()) }
                .onFailure { environment.log.warn("Scheduled git sync tick failed", it) }
        }
    }
}

/**
 * Background loop that drains the email outbox. Its own coroutine (like the git-sync scheduler) so a
 * slow SMTP relay never delays publish/purge work. Polls every 30s; the tick itself no-ops fast when
 * the queue is empty or a site's mail is unconfigured. Single-instance only (no row locking) — for HA,
 * add a claim step. Cancelled when the server stops.
 */
private fun Application.startEmailWorker() {
    launch {
        while (isActive) {
            delay(30_000)
            runCatching { appContext.email.processPending() }
                .onFailure { environment.log.warn("Email worker tick failed", it) }
        }
    }
}

/** Used by tests to bootstrap the application with the same module list. */
fun Application.configure() {
    module()
}
