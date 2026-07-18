package com.wikikt.routing

import com.wikikt.appContext
import com.wikikt.siteId
import com.wikikt.auth.PasswordHasher
import com.wikikt.auth.PasswordPolicy
import com.wikikt.auth.UserSession
import com.wikikt.auth.csrfField
import com.wikikt.auth.generateCsrfToken
import com.wikikt.model.parseId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set

/**
 * Self-service account area. Any authenticated user manages their OWN API keys here: keys are always
 * created as that user (no owner picker) and a user can only see/revoke/delete keys they own.
 * Administrators additionally get full control over everyone's keys via the admin area (/a/api-keys).
 */
fun Route.configureAccountRouting() {
    route("/p") {
        // Bare /p lands on the Account tab.
        get {
            call.currentUserId() ?: return@get call.redirectToLogin()
            call.respondRedirect("/p/settings")
        }

        // Account tab: profile summary + the per-user display timezone override. All three tabs render
        // on one page (client-side switching); the path just decides which pane opens active.
        get("/settings") {
            call.currentUserId() ?: return@get call.redirectToLogin()
            call.respond(MustacheContent("account/index.hbs", call.accountPageModel("account")))
        }

        post("/settings") {
            val userId = call.currentUserId() ?: return@post call.redirectToLogin()
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            // Empty clears the override (fall back to the server zone); otherwise it must be a known
            // IANA zone id.
            val tz = params["timezone"]?.trim().orEmpty()
            if (tz.isNotEmpty() && tz !in java.time.ZoneId.getAvailableZoneIds()) {
                call.respond(MustacheContent("account/index.hbs", call.accountPageModel("account", settingsError = "Unknown time zone.")))
                return@post
            }
            // Trim, cap to the column length, and treat blank as "cleared" (null).
            fun field(name: String, max: Int) = params[name]?.trim()?.take(max)?.ifBlank { null }
            val displayName = field("displayName", 100)
            // Two people can't self-assign the same display name. Only enforced when the name actually
            // changes (case-insensitively) — so a user whose duplicate an admin deliberately set can still
            // save the rest of their profile without being blocked on their own unchanged name.
            val currentDisplayName = call.appContext.users.findById(userId)?.displayName
            if (displayName != null && !displayName.equals(currentDisplayName, ignoreCase = true) &&
                call.appContext.users.displayNameTaken(displayName, userId)
            ) {
                call.respond(
                    MustacheContent(
                        "account/index.hbs",
                        call.accountPageModel("account", settingsError = "That display name is already taken — please choose another."),
                    ),
                )
                return@post
            }
            call.appContext.users.updateProfile(
                id = userId,
                displayName = displayName,
                jobTitle = field("jobTitle", 150),
                location = field("location", 150),
                timezone = tz.ifEmpty { null },
            )
            // Color theme: a known mode, or blank to follow the site default.
            val theme = params["theme"]?.trim().orEmpty()
            call.appContext.users.updateTheme(userId, theme.takeIf { it in com.wikikt.service.SettingsService.THEME_OPTIONS })
            // Date/time display preferences: each a known catalog key, or blank to follow the defaults.
            fun choice(name: String, options: List<DateDisplay.Option>) =
                params[name]?.trim()?.takeIf { v -> options.any { it.key == v } }
            call.appContext.users.updateDateTimeFormats(
                userId,
                dateFormatShort = choice("dateFormatShort", DateDisplay.SHORT_DATE_OPTIONS),
                dateFormatLong = choice("dateFormatLong", DateDisplay.LONG_DATE_OPTIONS),
                timeFormat = choice("timeFormat", DateDisplay.TIME_OPTIONS),
            )
            call.respond(MustacheContent("account/index.hbs", call.accountPageModel("account", saved = true)))
        }

        // Lightweight theme-only save for the header theme switch (fetch POST). Persists the logged-in
        // user's choice so it follows them across devices.
        post("/theme") {
            val userId = call.currentUserId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val theme = params["theme"]?.trim().orEmpty()
            call.appContext.users.updateTheme(userId, theme.takeIf { it in com.wikikt.service.SettingsService.THEME_OPTIONS })
            call.respond(HttpStatusCode.NoContent)
        }

        // Self-service password change: verify the current password, enforce the policy, then rotate
        // the session. Requires knowing the current password, so a hijacked-but-idle session can't
        // silently change it.
        post("/password") {
            val userId = call.currentUserId() ?: return@post call.redirectToLogin()
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val ctx = call.appContext
            val user = ctx.users.findById(userId) ?: return@post call.redirectToLogin()
            val current = params["currentPassword"].orEmpty()
            val next = params["newPassword"].orEmpty()
            val confirm = params["confirmPassword"].orEmpty()
            val error = when {
                !PasswordHasher.verify(current, user.passwordHash) -> "Your current password is incorrect."
                next != confirm -> "The new passwords do not match."
                else -> PasswordPolicy.validate(next, ctx.config.minPasswordLength)
            }
            if (error != null) {
                call.respond(MustacheContent("account/index.hbs", call.accountPageModel("security", pwError = error)))
                return@post
            }
            ctx.users.setPassword(userId, next)
            // Drop every existing session (logs out other devices), then mint a fresh one for THIS
            // browser so the user stays logged in — and the session id rotates on a credential change.
            ctx.sessions.deleteAllForUser(userId)
            // Also void any outstanding password-reset tokens: changing the password is a remediation, so
            // a still-valid reset link an attacker may hold must not survive it (mirrors the reset flow).
            ctx.passwordReset.deleteAllForUser(userId)
            val sessionId = ctx.sessions.create(userId, ctx.config.session.maxAgeSeconds * 1000)
            call.sessions.set(UserSession(sessionId, generateCsrfToken()))
            call.respond(MustacheContent("account/index.hbs", call.accountPageModel("security", pwSaved = true)))
        }

        // --- Two-factor authentication (TOTP) self-service ---
        route("/security") {
            get {
                call.currentUserId() ?: return@get call.redirectToLogin()
                call.respond(MustacheContent("account/index.hbs", call.accountPageModel("security")))
            }

            // Start enrollment: mint a pending secret and show the setup page (secret/QR + confirm form).
            post("/enable") {
                val userId = call.currentUserId() ?: return@post call.redirectToLogin()
                val params = call.receiveParameters()
                if (!call.validateFormCsrf(params)) return@post
                val ctx = call.appContext
                if (ctx.mfa.hasMfa(userId)) return@post call.respondRedirect("/p/security")
                val username = ctx.users.findById(userId)?.username ?: "user"
                val enrollment = ctx.mfa.beginTotpEnrollment(userId, call.mfaIssuer(), username)
                call.respond(MustacheContent("account/index.hbs", call.accountPageModel("security", setup = enrollment)))
            }

            // Finish enrollment: verify a live code, then reveal the one-time recovery codes.
            post("/confirm") {
                val userId = call.currentUserId() ?: return@post call.redirectToLogin()
                val params = call.receiveParameters()
                if (!call.validateFormCsrf(params)) return@post
                val code = params["code"]?.trim().orEmpty()
                val recoveryCodes = call.appContext.mfa.confirmTotpEnrollment(userId, code)
                if (recoveryCodes == null) {
                    call.respond(
                        MustacheContent(
                            "account/index.hbs",
                            call.accountPageModel("security", mfaError = "That code didn't match. Check your authenticator app's time and try again."),
                        ),
                    )
                    return@post
                }
                call.respond(
                    MustacheContent("account/index.hbs", call.accountPageModel("security", justEnabled = true, recoveryCodes = recoveryCodes)),
                )
            }

            // Fresh recovery codes (invalidates the old set), shown once.
            post("/regenerate") {
                val userId = call.currentUserId() ?: return@post call.redirectToLogin()
                if (!call.validateFormCsrf(call.receiveParameters())) return@post
                val recoveryCodes = call.appContext.mfa.regenerateRecoveryCodes(userId)
                    ?: return@post call.respondRedirect("/p/security")
                call.respond(MustacheContent("account/index.hbs", call.accountPageModel("security", recoveryCodes = recoveryCodes)))
            }

            // Turn MFA off — re-authenticate with the current password so an idle hijacked session can't.
            post("/disable") {
                val userId = call.currentUserId() ?: return@post call.redirectToLogin()
                val params = call.receiveParameters()
                if (!call.validateFormCsrf(params)) return@post
                val ctx = call.appContext
                val user = ctx.users.findById(userId) ?: return@post call.redirectToLogin()
                if (!PasswordHasher.verify(params["currentPassword"].orEmpty(), user.passwordHash)) {
                    call.respond(
                        MustacheContent("account/index.hbs", call.accountPageModel("security", mfaError = "Your current password is incorrect.")),
                    )
                    return@post
                }
                ctx.mfa.disableMfa(userId)
                call.respond(MustacheContent("account/index.hbs", call.accountPageModel("security", justDisabled = true)))
            }
        }

        route("/api-keys") {
        get {
            val userId = call.currentUserId() ?: return@get call.redirectToLogin()
            call.respond(MustacheContent("account/index.hbs", call.accountPageModel("apikeys")))
        }

        get("/new") {
            val userId = call.currentUserId() ?: return@get call.redirectToLogin()
            if (!call.appContext.permissions.canCreateApiKeys(userId)) return@get call.respondForbidden()
            call.respond(MustacheContent("admin/api-key-form.hbs", call.accountApiKeyFormModel()))
        }

        post {
            val userId = call.currentUserId() ?: return@post call.redirectToLogin()
            if (!call.appContext.permissions.canCreateApiKeys(userId)) return@post call.respondForbidden()
            val params = call.receiveParameters()
            if (!call.validateFormCsrf(params)) return@post
            val name = params["name"]?.trim().orEmpty()
            val expiresIn = params["expiresIn"].orEmpty()
            val error = when {
                name.isBlank() -> "A name is required."
                expiresIn.isNotEmpty() && expiresIn.toLongOrNull() == null -> "Invalid expiry."
                else -> null
            }
            if (error != null) {
                call.respond(
                    MustacheContent("admin/api-key-form.hbs", call.accountApiKeyFormModel(name = name, expiresIn = expiresIn, error = error)),
                )
                return@post
            }
            // Owner is always the current user — the form carries no owner field, and any userId param
            // is ignored, so a non-admin cannot mint a key for someone else.
            val ttlMillis = expiresIn.toLongOrNull()?.let { it * 86_400_000L }
            val created = call.appContext.apiKeys.create(userId, name, ttlMillis)
            call.respond(MustacheContent("account/index.hbs", call.accountPageModel("apikeys", newKey = created.plaintext)))
        }

        post("/{id}/revoke") {
            val userId = call.currentUserId() ?: return@post call.redirectToLogin()
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val key = call.ownedKeyParam(userId) ?: return@post
            call.appContext.apiKeys.revoke(key.id)
            call.respondRedirect("/p/api-keys")
        }

        post("/{id}/delete") {
            val userId = call.currentUserId() ?: return@post call.redirectToLogin()
            if (!call.validateFormCsrf(call.receiveParameters())) return@post
            val key = call.ownedKeyParam(userId) ?: return@post
            call.appContext.apiKeys.delete(key.id)
            call.respondRedirect("/p/api-keys")
        }
        }
    }
}

/** Sends an unauthenticated visitor to the login form, returning them here afterwards. */
private suspend fun io.ktor.server.application.ApplicationCall.redirectToLogin() =
    respondRedirect("/login?redirect=${request.path()}")

/**
 * Resolves the `{id}` path param to a key the current user owns. Responds 400/404 (and returns null)
 * for a bad id or a key that isn't theirs — so a user can never act on another user's key by id.
 */
private suspend fun io.ktor.server.application.ApplicationCall.ownedKeyParam(userId: UInt): com.wikikt.service.ApiKeyRecord? {
    val id = parameters["id"]?.let(::parseId) ?: run {
        respond(HttpStatusCode.BadRequest)
        return null
    }
    val key = appContext.apiKeys.findById(id)
    if (key == null || key.userId != userId) {
        respond(HttpStatusCode.NotFound)
        return null
    }
    return key
}

/** Base chrome for account pages: the normal site header (not the admin shell) + CSRF field. */
private suspend fun io.ktor.server.application.ApplicationCall.accountBaseModel(): Map<String, Any?> {
    val ctx = appContext
    val userId = currentUserId()
    val username = userId?.let { ctx.users.findById(it)?.username }
    return navModel() + mapOf(
        "loggedIn" to (userId != null),
        "canAdmin" to ctx.permissions.canAccessAdmin(userId),
        "username" to username,
        "csrfField" to csrfField(),
        "searchLocale" to ctx.config.defaultLocale,
        "searchQ" to "",
    )
}

private fun accountTab(active: String): Map<String, Any?> =
    mapOf(
        "account" to (active == "account"),
        "security" to (active == "security"),
        "apiKeys" to (active == "apikeys"),
    )

/** The issuer shown in the authenticator app — the site's configured name (falls back to the brand). */
private suspend fun io.ktor.server.application.ApplicationCall.mfaIssuer(): String {
    val s = com.wikikt.service.SettingsService
    return appContext.settings.get(siteId(), s.SITE_NAME)?.ifBlank { null } ?: s.DEFAULT_SITE_NAME
}

/**
 * Single model for the whole account page. All three tabs render together so switching between them is
 * client-side; [activeTab] (`account` / `security` / `apikeys`) only decides which pane opens active.
 *
 * The Security pane resolves MFA into exactly one of three views — ON (status + regenerate/disable),
 * SETUP (a pending enrollment's secret/QR + confirm form), or OFF (an enable button). Per-pane status
 * flags are namespaced (`saved`/`settingsError`, `pwSaved`/`pwError`, `mfaError`) so an alert raised on
 * one tab never bleeds into another.
 */
private suspend fun io.ktor.server.application.ApplicationCall.accountPageModel(
    activeTab: String,
    saved: Boolean = false,
    settingsError: String? = null,
    pwSaved: Boolean = false,
    pwError: String? = null,
    setup: com.wikikt.service.TotpEnrollment? = null,
    recoveryCodes: List<String>? = null,
    justEnabled: Boolean = false,
    justDisabled: Boolean = false,
    mfaError: String? = null,
    newKey: String? = null,
): Map<String, Any?> {
    val ctx = appContext
    val userId = currentUserId()
    val user = userId?.let { ctx.users.findById(it) }

    // --- Account pane: profile fields, timezone + theme preferences, group membership ---
    // Region/City zones (plus UTC), sorted — the legacy 3-letter and Etc/* aliases are omitted.
    val currentZone = user?.timezone.orEmpty()
    val zoneOptions = java.time.ZoneId.getAvailableZoneIds()
        .filter { it.contains("/") || it == "UTC" }
        .sorted()
        .map { mapOf("value" to it, "label" to it, "selected" to (it == currentZone)) }
    // Group membership (read-only here — changing it is an admin action), by name.
    val groups = userId?.let { ctx.users.groupsForUser(it) }.orEmpty().map { mapOf("name" to it.name) }
    // Color-theme override: "" = follow the site default, else light/dark/auto.
    val userTheme = user?.theme.orEmpty()
    val themeLabels = mapOf("" to "Site default", "light" to "Light", "dark" to "Dark", "auto" to "Auto (match my device)")
    val themeChoices = listOf("", "light", "dark", "auto").map {
        mapOf("value" to it, "label" to themeLabels[it], "selected" to (it == userTheme))
    }
    // Date/time format prefs: a "Site default" row (blank = follow the code defaults) plus each catalog
    // option, labelled with a live example rendered in the site locale so the choice is self-explanatory.
    val locale = java.util.Locale.forLanguageTag(ctx.config.defaultLocale)
    fun formatChoices(current: String, options: List<DateDisplay.Option>, example: (String) -> String) =
        listOf(mapOf("value" to "", "label" to "Site default", "selected" to current.isEmpty())) +
            options.map { mapOf("value" to it.key, "label" to "${it.name} (${example(it.key)})", "selected" to (it.key == current)) }
    val shortDateChoices = formatChoices(user?.dateFormatShort.orEmpty(), DateDisplay.SHORT_DATE_OPTIONS) { DateDisplay.shortDateExample(it, locale) }
    val longDateChoices = formatChoices(user?.dateFormatLong.orEmpty(), DateDisplay.LONG_DATE_OPTIONS) { DateDisplay.longDateExample(it, locale) }
    val timeChoices = formatChoices(user?.timeFormat.orEmpty(), DateDisplay.TIME_OPTIONS) { DateDisplay.timeExample(it, locale) }

    // --- Security pane: resolve MFA into ON / SETUP / OFF ---
    val mfaOn = userId != null && ctx.mfa.hasMfa(userId)
    // Setup view: an enrollment supplied by the caller (just started / mistyped code), else any pending one.
    val enrollment = when {
        mfaOn || userId == null -> null
        setup != null -> setup
        else -> ctx.mfa.pendingTotpEnrollment(userId, mfaIssuer(), user?.username ?: "user")
    }

    // --- API keys pane: the current user's own keys (no owner column, no picker) ---
    val now = com.wikikt.model.nowMillis()
    val formats = displayFormats()
    val keys = ctx.apiKeys.list().filter { it.userId == userId }.map { apiKeyRowModel(it, user?.username ?: "", now, formats) }

    return accountBaseModel() + mapOf(
        "accountTab" to accountTab(activeTab),
        // Account pane
        "email" to user?.email,
        "displayName" to user?.displayName.orEmpty(),
        "jobTitle" to user?.jobTitle.orEmpty(),
        "location" to user?.location.orEmpty(),
        "groups" to groups,
        "hasGroups" to groups.isNotEmpty(),
        "timezoneOptions" to zoneOptions,
        "usingServerDefault" to currentZone.isEmpty(),
        "serverZone" to java.time.ZoneId.systemDefault().id,
        "userThemeOptions" to themeChoices,
        "shortDateOptions" to shortDateChoices,
        "longDateOptions" to longDateChoices,
        "timeFormatOptions" to timeChoices,
        "saved" to saved,
        "settingsError" to settingsError,
        // Security pane
        "pwSaved" to pwSaved,
        "pwError" to pwError,
        "mfaError" to mfaError,
        "mfaOn" to mfaOn,
        "setup" to enrollment?.let {
            // Group the setup key in 4s for legibility; the otpauth link carries the unspaced value.
            mapOf(
                "secret" to it.secretBase32.chunked(4).joinToString(" "),
                "otpauthUri" to it.provisioningUri,
                "qrSvg" to com.wikikt.auth.QrSvg.render(it.provisioningUri),
            )
        },
        "mfaOff" to (!mfaOn && enrollment == null),
        "remaining" to (if (mfaOn && userId != null) ctx.mfa.remainingRecoveryCodes(userId) else 0),
        "recoveryCodes" to recoveryCodes?.let { codes -> mapOf("codes" to codes.map { mapOf("code" to it) }) },
        "justEnabled" to justEnabled,
        "justDisabled" to justDisabled,
        // API keys pane
        "keys" to keys,
        "hasKeys" to keys.isNotEmpty(),
        "newKey" to newKey,
        "baseUrl" to "/p/api-keys",
        "showOwner" to false,
        "canCreate" to ctx.permissions.canCreateApiKeys(userId),
        // Key-managers get a pointer to the admin area, where they can manage everyone's keys.
        "canManageAllKeys" to ctx.permissions.canManageUsers(userId),
    )
}

/** Self-service create-key form: name + expiry only (owner is implicitly the current user). */
private suspend fun io.ktor.server.application.ApplicationCall.accountApiKeyFormModel(
    name: String = "",
    expiresIn: String = "",
    error: String? = null,
): Map<String, Any?> = accountBaseModel() + mapOf(
    "name" to name,
    "showOwnerPicker" to false,
    "expiryOptions" to apiKeyExpiryOptions(expiresIn),
    "error" to error,
    "baseUrl" to "/p/api-keys",
)
