package com.wikikt.routing

import com.wikikt.appContext
import com.wikikt.auth.CSRF_FIELD
import com.wikikt.auth.LoginThrottle
import com.wikikt.auth.MfaPendingSession
import com.wikikt.auth.MfaThrottle
import com.wikikt.auth.PasswordPolicy
import com.wikikt.auth.RegisterThrottle
import com.wikikt.auth.ResetRequestThrottle
import com.wikikt.auth.UserSession
import com.wikikt.db.UserStatus
import com.wikikt.auth.csrfFieldFor
import com.wikikt.auth.ensureAnonCsrf
import com.wikikt.auth.generateCsrfToken
import com.wikikt.auth.isAnonCsrfValid
import com.wikikt.auth.isCsrfValid
import kotlinx.coroutines.launch
import com.wikikt.service.EmailTemplateService
import com.wikikt.service.SettingsService
import com.wikikt.siteId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.util.AttributeKey
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set

fun Route.configureAuthRouting() {
    get("/login") {
        // Only redirect if the session is genuinely valid server-side. Checking the raw cookie isn't
        // enough: a cookie can outlive its server session (expiry, revocation, or a DB reset), which
        // would bounce the user straight back here and leave them unable to log in. In that case clear
        // the stale cookie and show the form.
        if (call.currentUserId() != null) {
            call.respondRedirect("/")
            return@get
        }
        call.sessions.clear<UserSession>()
        val siteId = call.siteId()
        val mailEnabled = call.appContext.settings.getBool(siteId, SettingsService.MAIL_ENABLED)
        val registrationEnabled = mailEnabled && call.appContext.settings.getBool(siteId, SettingsService.REGISTRATION_ENABLED)
        // Issue an anonymous-form CSRF token so the POST can reject cross-site login attempts.
        call.respond(
            MustacheContent(
                "auth/login.hbs",
                mapOf("error" to null, "mailEnabled" to mailEnabled, "registrationEnabled" to registrationEnabled, "csrfField" to csrfFieldFor(call.ensureAnonCsrf())) + call.navModel(),
            ),
        )
    }

    post("/login") {
        val ctx = call.appContext
        val params = call.receiveParameters()
        val siteId = call.siteId()
        val mailEnabled = ctx.settings.getBool(siteId, SettingsService.MAIL_ENABLED)
        val registrationEnabled = mailEnabled && ctx.settings.getBool(siteId, SettingsService.REGISTRATION_ENABLED)
        // Re-render the login form carrying a fresh (idempotent) anon-CSRF token.
        suspend fun loginPage(error: String?) = MustacheContent(
            "auth/login.hbs",
            mapOf("error" to error, "mailEnabled" to mailEnabled, "registrationEnabled" to registrationEnabled, "csrfField" to csrfFieldFor(call.ensureAnonCsrf())) + call.navModel(),
        )

        // Anti-CSRF: a cross-site page must not be able to silently log a victim into the attacker's
        // account. The token is issued on GET /login (or forgot/reset) and can't be read cross-origin.
        if (!call.isAnonCsrfValid(params[CSRF_FIELD])) {
            call.respond(loginPage("Your session expired. Please try again."))
            return@post
        }
        val username = params["username"]?.trim().orEmpty()
        val password = params["password"] ?: ""

        val clientKey = call.request.origin.remoteHost
        if (LoginThrottle.isLockedOut(clientKey, username)) {
            call.respond(loginPage("Too many failed attempts. Please wait a minute and try again."))
            return@post
        }

        val user = ctx.users.authenticate(username, password)
        if (user == null) {
            LoginThrottle.recordFailure(clientKey, username)
            call.respond(loginPage("Invalid username or password"))
            return@post
        }

        // Credentials are valid, but a self-registered account may not be usable yet. Don't create a
        // session; explain why (this is the user's own account, so the status isn't sensitive to reveal).
        if (user.status != UserStatus.ACTIVE) {
            LoginThrottle.recordSuccess(clientKey, username)
            call.respond(
                loginPage(
                    when (user.status) {
                        UserStatus.PENDING_EMAIL ->
                            "Please confirm your email address to activate your account — check your inbox for the confirmation link."
                        UserStatus.PENDING_APPROVAL ->
                            "Your account is awaiting administrator approval. You'll be able to sign in once it's approved."
                        else -> "Your account isn't active."
                    },
                ),
            )
            return@post
        }

        LoginThrottle.recordSuccess(clientKey, username)
        val redirect = sanitizeSameSiteRedirect(params["redirect"])
        // Second factor: if the account has MFA enabled, do NOT create a session yet. Issue a short-lived
        // pending cookie (password verified, awaiting a code) and send the user to the code step.
        if (ctx.mfa.hasMfa(user.id)) {
            call.sessions.set(MfaPendingSession(user.id, System.currentTimeMillis(), redirect))
            call.respondRedirect("/login/mfa")
            return@post
        }
        val sessionId = ctx.sessions.create(user.id, ctx.config.session.maxAgeSeconds * 1000)
        call.sessions.set(UserSession(sessionId, generateCsrfToken()))
        call.respondRedirect(redirect)
    }

    // --- Second factor (MFA) ---
    //
    // Reached only after a correct password for an MFA-enabled account (which set an MfaPendingSession — NOT
    // a real session). The user submits their authenticator code, or a one-time recovery code; on success
    // the pending cookie is exchanged for a real session. Attempts are throttled per user, and the POST
    // carries an anonymous CSRF token issued on the GET.
    get("/login/mfa") {
        val pending = call.sessions.get<MfaPendingSession>()
        if (pending == null || !mfaPendingValid(pending)) {
            call.sessions.clear<MfaPendingSession>()
            call.respondRedirect("/login")
            return@get
        }
        call.respond(call.mfaChallengePage(error = null))
    }

    post("/login/mfa") {
        val ctx = call.appContext
        val params = call.receiveParameters()
        val pending = call.sessions.get<MfaPendingSession>()
        if (pending == null || !mfaPendingValid(pending)) {
            call.sessions.clear<MfaPendingSession>()
            call.respondRedirect("/login")
            return@post
        }
        if (!call.isAnonCsrfValid(params[CSRF_FIELD])) {
            call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Invalid or missing CSRF token", 403)))
            return@post
        }
        if (MfaThrottle.isLockedOut(pending.userId)) {
            call.respond(call.mfaChallengePage(error = "Too many attempts. Please wait a few minutes and try again."))
            return@post
        }
        // Accept either a TOTP code or a one-time recovery code from the single field.
        val code = params["code"]?.trim().orEmpty()
        val ok = ctx.mfa.verifyCode(pending.userId, code) || ctx.mfa.verifyRecoveryCode(pending.userId, code)
        if (!ok) {
            MfaThrottle.recordFailure(pending.userId)
            call.respond(call.mfaChallengePage(error = "That code wasn't valid. Try again, or use one of your recovery codes."))
            return@post
        }
        MfaThrottle.recordSuccess(pending.userId)
        call.sessions.clear<MfaPendingSession>()
        val sessionId = ctx.sessions.create(pending.userId, ctx.config.session.maxAgeSeconds * 1000)
        call.sessions.set(UserSession(sessionId, generateCsrfToken()))
        call.respondRedirect(sanitizeSameSiteRedirect(pending.redirect))
    }

    // POST-only (a GET would let any <img src="/logout"> or link prefetcher end the session), and
    // CSRF-checked like every other state change. With no session at all there's nothing to do.
    post("/logout") {
        if (call.sessions.get<UserSession>() != null &&
            !call.isCsrfValid(call.receiveParameters()[CSRF_FIELD])
        ) {
            call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Invalid or missing CSRF token", 403)))
            return@post
        }
        call.endServerSession()
        call.respondRedirect("/")
    }

    // --- Self-service password reset ---
    //
    // Two anonymous steps: request a link (/forgot-password) and set a new password (/reset-password?token).
    // The request endpoint never reveals whether an address is registered (no user enumeration) — it always
    // reports the same "if it exists, we sent a link" outcome — and is rate-limited by client host. The
    // token is single-use, short-lived, and stored hashed (PasswordResetService). Both POSTs carry an
    // anonymous CSRF token issued on the GET.

    get("/forgot-password") {
        // With mail off there's no way to deliver a reset link, so don't show the form — just say so.
        if (!call.appContext.settings.getBool(call.siteId(), SettingsService.MAIL_ENABLED)) {
            call.respond(MustacheContent("auth/forgot-password.hbs", mapOf("mailDisabled" to true) + call.navModel()))
            return@get
        }
        val csrf = call.ensureAnonCsrf()
        call.respond(
            MustacheContent(
                "auth/forgot-password.hbs",
                mapOf("csrfField" to csrfFieldFor(csrf), "sent" to false, "error" to null) + call.navModel(),
            ),
        )
    }

    post("/forgot-password") {
        val ctx = call.appContext
        val params = call.receiveParameters()
        if (!call.isAnonCsrfValid(params[CSRF_FIELD])) {
            call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Invalid or missing CSRF token", 403)))
            return@post
        }

        val siteId = call.siteId()
        // Mail off → nothing to send. Report it honestly rather than pretending a link went out.
        if (!ctx.settings.getBool(siteId, SettingsService.MAIL_ENABLED)) {
            call.respond(MustacheContent("auth/forgot-password.hbs", mapOf("mailDisabled" to true) + call.navModel()))
            return@post
        }

        // Rate-limit by client host so one source can't flood the mail queue / inboxes with reset requests.
        val clientKey = call.request.origin.remoteHost
        if (ResetRequestThrottle.isLockedOut(clientKey)) {
            call.respond(
                MustacheContent(
                    "auth/forgot-password.hbs",
                    mapOf(
                        "csrfField" to csrfFieldFor(call.ensureAnonCsrf()),
                        "sent" to false,
                        "error" to "Too many requests. Please wait a few minutes and try again.",
                    ) + call.navModel(),
                ),
            )
            return@post
        }
        ResetRequestThrottle.record(clientKey)

        val email = params["email"]?.trim().orEmpty()
        if (email.isNotEmpty()) {
            val siteName = ctx.settings.get(siteId, SettingsService.SITE_NAME)?.ifBlank { null }
                ?: SettingsService.DEFAULT_SITE_NAME
            // Capture the trusted link base from the request now, then run the lookup + token mint +
            // enqueue OFF the response path: a registered address otherwise costs extra DB writes before
            // the (identical) response is sent, leaking via response timing whether the address exists.
            val urlBase = call.outboundUrl("")
            val app = call.application
            app.launch {
                runCatching {
                    for (user in ctx.users.findByEmail(email)) {
                        val recipient = user.email ?: continue
                        val token = ctx.passwordReset.createToken(user.id)
                        ctx.email.enqueue(
                            siteId = siteId,
                            recipient = recipient,
                            templateKey = EmailTemplateService.PASSWORD_RESET,
                            context = mapOf(
                                "siteName" to siteName,
                                "username" to user.username,
                                "displayName" to (user.displayName?.ifBlank { null } ?: user.username),
                                "resetLink" to "$urlBase/reset-password?token=$token",
                            ),
                        )
                    }
                }.onFailure { app.environment.log.warn("Failed to enqueue password-reset email", it) }
            }
        }

        // Always the same generic confirmation, whether or not the address matched — no enumeration.
        call.respond(
            MustacheContent(
                "auth/forgot-password.hbs",
                mapOf("sent" to true, "error" to null) + call.navModel(),
            ),
        )
    }

    get("/reset-password") {
        val ctx = call.appContext
        val token = call.request.queryParameters["token"].orEmpty()
        val userId = if (token.isEmpty()) null else ctx.passwordReset.resolve(token)
        if (userId == null) {
            call.respond(MustacheContent("auth/reset-password.hbs", mapOf("invalidToken" to true) + call.navModel()))
            return@get
        }
        val csrf = call.ensureAnonCsrf()
        call.respond(
            MustacheContent(
                "auth/reset-password.hbs",
                mapOf("csrfField" to csrfFieldFor(csrf), "token" to token, "error" to null) + call.navModel(),
            ),
        )
    }

    post("/reset-password") {
        val ctx = call.appContext
        val params = call.receiveParameters()
        if (!call.isAnonCsrfValid(params[CSRF_FIELD])) {
            call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Invalid or missing CSRF token", 403)))
            return@post
        }

        val token = params["token"].orEmpty()
        val password = params["password"] ?: ""
        val confirm = params["confirm"] ?: ""

        // Validate the token WITHOUT spending it, so a weak/mismatched password leaves the link usable.
        if (token.isEmpty() || ctx.passwordReset.resolve(token) == null) {
            call.respond(MustacheContent("auth/reset-password.hbs", mapOf("invalidToken" to true) + call.navModel()))
            return@post
        }

        val error = when {
            password != confirm -> "Passwords do not match."
            else -> PasswordPolicy.validate(password, ctx.config.minPasswordLength)
        }
        if (error != null) {
            call.respond(
                MustacheContent(
                    "auth/reset-password.hbs",
                    mapOf("csrfField" to csrfFieldFor(call.ensureAnonCsrf()), "token" to token, "error" to error) + call.navModel(),
                ),
            )
            return@post
        }

        // Spend the token (single-use, race-safe), apply the new password, then invalidate the account's
        // sessions and any other outstanding reset links so a hijacked session or a second link can't
        // outlive the reset. consume() can still fail here if the link was redeemed concurrently.
        val userId = ctx.passwordReset.consume(token)
        if (userId == null) {
            call.respond(MustacheContent("auth/reset-password.hbs", mapOf("invalidToken" to true) + call.navModel()))
            return@post
        }
        ctx.users.setPassword(userId, password)
        ctx.sessions.deleteAllForUser(userId)
        ctx.passwordReset.deleteAllForUser(userId)

        call.respond(MustacheContent("auth/reset-password.hbs", mapOf("done" to true) + call.navModel()))
    }

    // --- Self-service registration (opt-in) ---
    //
    // Reachable only when registration.enabled AND mail.enabled (accounts must confirm their address). A
    // new account is created PENDING_EMAIL and a single-use confirmation link is emailed
    // (EmailVerificationService); clicking it activates the account — or leaves it PENDING_APPROVAL when
    // the site requires an admin to approve new sign-ups. Privacy-first: the form never reveals that an
    // email is already registered (the existing owner is emailed instead), while username availability is
    // shown inline (usernames are already public as page authors). Rate-limited by client host; the POST
    // carries an anonymous CSRF token issued on the GET.

    get("/register") {
        val ctx = call.appContext
        if (call.currentUserId() != null) {
            call.respondRedirect("/")
            return@get
        }
        if (!registrationOpen(ctx, call.siteId())) {
            call.respond(MustacheContent("auth/register.hbs", mapOf("disabled" to true) + call.navModel()))
            return@get
        }
        call.respond(
            MustacheContent(
                "auth/register.hbs",
                mapOf(
                    "csrfField" to csrfFieldFor(call.ensureAnonCsrf()),
                    "sent" to false,
                    "error" to null,
                    "username" to "",
                    "email" to "",
                ) + call.navModel(),
            ),
        )
    }

    post("/register") {
        val ctx = call.appContext
        val params = call.receiveParameters()
        val siteId = call.siteId()
        if (!registrationOpen(ctx, siteId)) {
            call.respond(MustacheContent("auth/register.hbs", mapOf("disabled" to true) + call.navModel()))
            return@post
        }
        if (!call.isAnonCsrfValid(params[CSRF_FIELD])) {
            call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Invalid or missing CSRF token", 403)))
            return@post
        }
        // Honeypot: a hidden field real users never see. A bot that auto-fills every input trips it —
        // respond with the same generic "check your email" page so it can't tell it was caught, and do
        // nothing else (no account, no mail, no throttle spend).
        if (params["homepage"]?.isNotBlank() == true) {
            call.respond(MustacheContent("auth/register.hbs", mapOf("sent" to true) + call.navModel()))
            return@post
        }
        val s = SettingsService
        val username = params["username"]?.trim().orEmpty()
        val email = params["email"]?.trim().orEmpty()
        val password = params["password"] ?: ""
        val confirm = params["confirm"] ?: ""

        suspend fun registerPage(error: String) = MustacheContent(
            "auth/register.hbs",
            mapOf(
                "csrfField" to csrfFieldFor(call.ensureAnonCsrf()),
                "sent" to false,
                "error" to error,
                "username" to username,
                "email" to email,
            ) + call.navModel(),
        )

        // Per-host cap blunts one source; the site-wide cap bounds how much confirmation/notification mail
        // the endpoint can emit per window, so a distributed attacker rotating IPs can't use registration to
        // spray email at third parties (and harm our sender reputation). Same generic message either way.
        val clientKey = call.request.origin.remoteHost
        if (RegisterThrottle.isLockedOut(clientKey) || RegisterThrottle.isGloballyLockedOut()) {
            call.respond(registerPage("Too many attempts. Please wait a few minutes and try again."))
            return@post
        }

        // Inline validation. Username availability is checked only against real (non-PENDING_EMAIL)
        // accounts — a never-confirmed pending row is reclaimable, so it doesn't block the name.
        val existing = ctx.users.findByUsername(username)
        val usernameTaken = existing != null && existing.status != UserStatus.PENDING_EMAIL
        val error = when {
            !REGISTER_USERNAME_PATTERN.matches(username) ->
                "Choose a username of 3–100 characters: letters, numbers, and . _ - (starting with a letter or number)."
            usernameTaken -> "That username is already taken."
            !REGISTER_EMAIL_PATTERN.matches(email) -> "Enter a valid email address."
            !ctx.settings.isRegistrationDomainAllowed(siteId, email) ->
                "Registration on this site is limited to specific email domains. Please use an eligible address."
            password != confirm -> "Passwords do not match."
            else -> PasswordPolicy.validate(password, ctx.config.minPasswordLength)
        }
        if (error != null) {
            call.respond(registerPage(error))
            return@post
        }
        RegisterThrottle.record(clientKey)

        // Do the account/email work OFF the response path so both branches (new sign-up vs. address already
        // in use) take the same time and return the same message — response timing can't reveal whether the
        // address is already registered.
        val siteName = ctx.settings.get(siteId, s.SITE_NAME)?.ifBlank { null } ?: s.DEFAULT_SITE_NAME
        val urlBase = call.outboundUrl("")
        val app = call.application
        app.launch {
            runCatching {
                val owners = ctx.users.findByEmail(email).filter { it.status != UserStatus.PENDING_EMAIL }
                if (owners.isNotEmpty()) {
                    // Address already belongs to a real account: create nothing, notify the owner instead.
                    for (owner in owners) {
                        val recipient = owner.email ?: continue
                        ctx.email.enqueue(
                            siteId = siteId,
                            recipient = recipient,
                            templateKey = EmailTemplateService.REGISTRATION_EMAIL_EXISTS,
                            context = mapOf(
                                "siteName" to siteName,
                                "username" to owner.username,
                                "loginUrl" to "$urlBase/login",
                                "resetUrl" to "$urlBase/forgot-password",
                            ),
                        )
                    }
                } else {
                    val defaultGroupId = ctx.settings.get(siteId, s.REGISTRATION_DEFAULT_GROUP)
                        ?.trim()?.toUIntOrNull()
                        ?.takeIf { ctx.groups.findById(it) != null }
                    val user = ctx.users.register(username, email, password, defaultGroupId)
                    val token = ctx.emailVerification.createToken(user.id)
                    ctx.email.enqueue(
                        siteId = siteId,
                        recipient = email,
                        templateKey = EmailTemplateService.REGISTRATION_CONFIRM,
                        context = mapOf(
                            "siteName" to siteName,
                            // The stored (normalized) name, not the raw form input -- to match DB exactly:
                            "username" to user.username,
                            "confirmLink" to "$urlBase/verify-email?token=$token",
                        ),
                    )
                }
            }.onFailure { app.environment.log.warn("Registration processing failed", it) }
        }

        // Always the same generic confirmation, whether the address was new or already in use — no enumeration.
        call.respond(MustacheContent("auth/register.hbs", mapOf("sent" to true) + call.navModel()))
    }

    // Confirmation is a two-step GET -> POST so that a mail-security scanner or link prefetcher that
    // merely FETCHES the confirmation link can't spend the single-use token (or silently activate the
    // account) before the human clicks. The GET only resolves the token and renders a confirm button; the
    // POST — carrying the anon-CSRF token issued on the GET — consumes it and activates the account.
    get("/verify-email") {
        val ctx = call.appContext
        val token = call.request.queryParameters["token"].orEmpty()
        val userId = if (token.isEmpty()) null else ctx.emailVerification.resolve(token)
        if (userId == null) {
            call.respond(MustacheContent("auth/verify-email.hbs", mapOf("invalid" to true) + call.navModel()))
            return@get
        }
        call.respond(
            MustacheContent(
                "auth/verify-email.hbs",
                mapOf(
                    "confirm" to true,
                    "token" to token,
                    "csrfField" to csrfFieldFor(call.ensureAnonCsrf()),
                ) + call.navModel(),
            ),
        )
    }

    post("/verify-email") {
        val ctx = call.appContext
        val siteId = call.siteId()
        val params = call.receiveParameters()
        if (!call.isAnonCsrfValid(params[CSRF_FIELD])) {
            call.respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", call.errorModel("Invalid or missing CSRF token", 403)))
            return@post
        }
        val token = params["token"].orEmpty()
        // Spend the token (single-use, race-safe) and activate in the same step.
        val userId = if (token.isEmpty()) null else ctx.emailVerification.consume(token)
        val requireApproval = ctx.settings.getBool(siteId, SettingsService.REGISTRATION_REQUIRE_APPROVAL)
        val status = if (userId == null) null else ctx.users.markEmailConfirmed(userId, requireApproval)
        if (status == null) {
            // Unknown/expired/replayed token, or the account was already confirmed or purged/deleted.
            call.respond(MustacheContent("auth/verify-email.hbs", mapOf("invalid" to true) + call.navModel()))
            return@post
        }
        call.respond(
            MustacheContent(
                "auth/verify-email.hbs",
                mapOf("confirmed" to true, "pendingApproval" to (status == UserStatus.PENDING_APPROVAL)) + call.navModel(),
            ),
        )
    }
}

/** Registration is available only when the feature is enabled AND mail is configured to deliver the
 *  confirmation link. */
private suspend fun registrationOpen(ctx: com.wikikt.AppContext, siteId: UInt): Boolean =
    ctx.settings.getBool(siteId, SettingsService.MAIL_ENABLED) &&
        ctx.settings.getBool(siteId, SettingsService.REGISTRATION_ENABLED)

// 3–100 chars, starting with a letter/number, then letters/numbers/dot/underscore/hyphen.
internal val REGISTER_USERNAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,99}$")

// Pragmatic email shape check (a single @, a dot in the domain, no whitespace). Deliverability is proven
// by the confirmation link, not by this regex.
private val REGISTER_EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

/**
 * Sanitizes a post-login "return to" target down to a SAME-SITE path, falling back to "/". Accepts only a
 * value that starts with a single "/" (not "//"), contains no backslash (browsers normalize "\" to "/"),
 * and no whitespace or control character. That last check matters: a tab/newline like "/\t/evil.com"
 * survives the prefix checks yet a browser strips the control char during URL parsing, turning it into the
 * protocol-relative "//evil.com" that escapes the site. Defense in depth — today no request path feeds an
 * attacker-controlled `redirect`, but this keeps the guard correct if one ever does.
 */
internal fun sanitizeSameSiteRedirect(raw: String?): String =
    raw?.takeIf {
        it.startsWith("/") && !it.startsWith("//") && '\\' !in it &&
            it.none { c -> c.isWhitespace() || c.isISOControl() }
    } ?: "/"

private suspend fun io.ktor.server.application.ApplicationCall.endServerSession() {
    sessions.get<UserSession>()?.sessionId?.let { appContext.sessions.delete(it) }
    sessions.clear<UserSession>()
}

/** The MFA-pending cookie is short-lived; also reject a stale one server-side (defense in depth). */
private const val MFA_PENDING_TTL_MS = 5 * 60_000L

private fun mfaPendingValid(pending: MfaPendingSession): Boolean =
    System.currentTimeMillis() - pending.issuedAt <= MFA_PENDING_TTL_MS

/** The second-factor challenge page, carrying a fresh anon-CSRF token for its POST. */
private suspend fun io.ktor.server.application.ApplicationCall.mfaChallengePage(error: String?) =
    MustacheContent(
        "auth/mfa-challenge.hbs",
        mapOf("csrfField" to csrfFieldFor(ensureAnonCsrf()), "error" to error) + navModel(),
    )

private val ResolvedUserIdKey = AttributeKey<ResolvedUserId>("wikikt.resolvedUserId")
private class ResolvedUserId(val id: UInt?)

/**
 * The current user. Resolved first from the session cookie (validated against the server store), and
 * failing that from an `Authorization: Bearer <key>` API token. A resolved API key authenticates as
 * its owning user, so downstream permission checks are identical to a logged-in session.
 */
suspend fun io.ktor.server.application.ApplicationCall.currentUserId(): UInt? {
    attributes.getOrNull(ResolvedUserIdKey)?.let { return it.id }
    val sessionId = sessions.get<UserSession>()?.sessionId
    var userId = if (sessionId.isNullOrEmpty()) null else appContext.sessions.resolveUserId(sessionId)
    if (userId == null) {
        bearerToken()?.let { userId = appContext.apiKeys.resolveUserId(it) }
    }
    attributes.put(ResolvedUserIdKey, ResolvedUserId(userId))
    return userId
}

/**
 * Builds an absolute URL for [path] (which must start with `/`) from the current request's origin —
 * scheme, host, and non-default port, honoring `X-Forwarded-*` via [origin]. NOTE: the host here comes
 * from the request (the client `Host` header unless a trusted proxy overrides it), so this must NOT be
 * used to build links sent to third parties (email) — use [outboundUrl] for those.
 */
fun io.ktor.server.application.ApplicationCall.absoluteUrl(path: String): String {
    val o = request.origin
    val port = o.serverPort
    val portPart = if ((o.scheme == "http" && port == 80) || (o.scheme == "https" && port == 443)) "" else ":$port"
    return "${o.scheme}://${o.serverHost}$portPart$path"
}

/**
 * Builds an absolute URL for [path] to place into OUTBOUND EMAIL (password reset, welcome). Unlike
 * [absoluteUrl] this never trusts an attacker-controllable `Host` header — which would otherwise let an
 * anonymous attacker poison a password-reset link and steal the token. Preference order:
 *  1. the configured canonical `publicUrl` (`WIKIKT_PUBLIC_URL`), if set;
 *  2. the request host, but only when it matches a hostname the operator actually configured on a site;
 *  3. otherwise a configured site hostname (so a spoofed host is replaced by a real one);
 *  4. as a last resort — a deployment with no publicUrl and no configured site hostnames (a bare dev
 *     setup) — the request origin, matching the old behavior.
 * Set `WIKIKT_PUBLIC_URL` (or a per-site hostname) on any internet-facing deployment to pin (1)/(2).
 */
suspend fun io.ktor.server.application.ApplicationCall.outboundUrl(path: String): String {
    appContext.config.publicUrl?.trimEnd('/')?.let { return it + path }
    val configuredHosts = appContext.sites.all().mapNotNull { it.hostname?.trim()?.ifBlank { null } }
    val requestHost = request.origin.serverHost
    if (requestHost in configuredHosts) return absoluteUrl(path)
    val trustedHost = configuredHosts.firstOrNull() ?: return absoluteUrl(path)
    return "${request.origin.scheme}://$trustedHost$path"
}

/** The token from an `Authorization: Bearer <token>` header, or null if absent/malformed. */
private fun io.ktor.server.application.ApplicationCall.bearerToken(): String? {
    val header = request.headers[io.ktor.http.HttpHeaders.Authorization] ?: return null
    val prefix = "Bearer "
    if (!header.startsWith(prefix, ignoreCase = true)) return null
    return header.substring(prefix.length).trim().ifEmpty { null }
}

private val ResolvedFormatsKey = AttributeKey<DateDisplay.DisplayFormats>("wikikt.displayFormats")

/**
 * How timestamps should be rendered for the current request: the logged-in user's chosen timezone and
 * date/time format preferences (each falling back to the server zone / code defaults when unset or
 * invalid). Resolved once per request and cached. Stored times are always epoch millis (UTC) — this
 * only affects how they're rendered. Pass the result to [DateDisplay.format] / [DateDisplay.formatDate].
 */
suspend fun io.ktor.server.application.ApplicationCall.displayFormats(): DateDisplay.DisplayFormats {
    attributes.getOrNull(ResolvedFormatsKey)?.let { return it }
    val user = currentUserId()?.let { appContext.users.findById(it) }
    val zone = user?.timezone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
        ?: java.time.ZoneId.systemDefault()
    val locale = java.util.Locale.forLanguageTag(appContext.config.defaultLocale)
    val formats = DateDisplay.resolve(zone, user?.dateFormatShort, user?.dateFormatLong, user?.timeFormat, locale)
    attributes.put(ResolvedFormatsKey, formats)
    return formats
}

/**
 * The zone timestamps should be displayed in for the current request (see [displayFormats]). Kept for
 * callers that only need the zone — e.g. `<input type="datetime-local">` round-tripping.
 */
suspend fun io.ktor.server.application.ApplicationCall.displayZone(): java.time.ZoneId = displayFormats().zone

suspend fun io.ktor.server.application.ApplicationCall.requireManageUsers(): Boolean {
    val userId = currentUserId() ?: return false
    return appContext.permissions.canManageUsers(userId)
}

/** Root (`manage:system`) only. For operations that can rewrite the whole security state — e.g. a
 *  full backup export/restore, which dumps or replaces every account, group, and secret. */
suspend fun io.ktor.server.application.ApplicationCall.requireRoot(): Boolean {
    val userId = currentUserId() ?: return false
    return appContext.permissions.isRoot(userId)
}

suspend fun io.ktor.server.application.ApplicationCall.requireManageGroups(): Boolean {
    val userId = currentUserId() ?: return false
    return appContext.permissions.canManageGroups(userId)
}

suspend fun io.ktor.server.application.ApplicationCall.requireManagePages(): Boolean {
    val userId = currentUserId() ?: return false
    return appContext.permissions.canManagePages(userId)
}

suspend fun io.ktor.server.application.ApplicationCall.requireManageNavigation(): Boolean {
    val userId = currentUserId() ?: return false
    return appContext.permissions.canManageNavigation(userId)
}

suspend fun io.ktor.server.application.ApplicationCall.respondForbidden() {
    respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", errorModel("Access denied", 403)))
}

/**
 * Validates the CSRF token on a state-changing form submission. On failure it writes a 403 and
 * returns false, so callers should `return` immediately.
 */
suspend fun io.ktor.server.application.ApplicationCall.validateFormCsrf(
    params: io.ktor.http.Parameters,
): Boolean {
    if (isCsrfValid(params[CSRF_FIELD])) return true
    respond(HttpStatusCode.Forbidden, MustacheContent("error.hbs", errorModel("Invalid or missing CSRF token", 403)))
    return false
}

