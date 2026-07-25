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
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
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
    // Page-supplied keys win over branding defaults (none currently collide).
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
            val userTheme = currentUser?.theme?.takeIf { it in com.wikikt.service.SettingsService.THEME_OPTIONS }
            val merged = mapOf(
                "assetsCdn" to ctx.config.ui.useCdnAssets,
                // Webfont hosts, each independent of assetsCdn (see UiConfig.useCdnEmojiFont / useCdnIconFont).
                "emojiFontCdn" to ctx.config.ui.useCdnEmojiFont,
                "iconFontCdn" to ctx.config.ui.useCdnIconFont,
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
                ctx.settings.brandingModel(call.siteId(), ctx.markdown, year) +
                (userTheme?.let { mapOf("themeMode" to it) } ?: emptyMap()) +
                (message.model as Map<String, Any?>)
            proceedWith(MustacheContent(message.template, merged, message.etag, message.contentType))
        }
    }

    startPublishScheduler()
    startGitSyncScheduler()
    startEmailWorker()

    routing {
        staticResources("/static", "static") {
            // Every local /static reference carries ?v=assetVersion, so production can cache hard for a
            // year — a new build changes the token and browsers fetch fresh files immediately. Dev keeps
            // today's no-header behavior so edits show on a plain reload.
            if (environment.config.isProductionEnvironment()) {
                cacheControl {
                    listOf(CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Public))
                }
            }
        }

        // Bundled default favicon + logo, served at root paths. Admin-selected assets (site.faviconUrl
        // / site.logoUrl) override them via the page <head> and header markup.
        get("/favicon.svg") {
            val bytes = call.application.environment.classLoader.getResourceAsStream("static/favicon.svg")?.readBytes()
            if (bytes != null) call.respondBytes(bytes, ContentType.Image.SVG) else call.respond(HttpStatusCode.NotFound)
        }
        get("/logo.svg") {
            val bytes = call.application.environment.classLoader.getResourceAsStream("static/logo.svg")?.readBytes()
            if (bytes != null) call.respondBytes(bytes, ContentType.Image.SVG) else call.respond(HttpStatusCode.NotFound)
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
