package com.wikikt

import com.wikikt.auth.UserSession
import com.wikikt.config.envOrConfig
import com.wikikt.config.loadSessionConfig
import com.wikikt.service.SettingsService
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie

fun Application.configureSecurity() {
    val sessionConfig = environment.config.loadSessionConfig()

    // Only honor X-Forwarded-* when explicitly told the app sits behind a trusted reverse proxy.
    // Without it request.origin.remoteHost is the proxy for every request, so the login throttle
    // would lock all users out together. useLastProxy() reads the value the proxy itself appended —
    // taking the first (the default) would trust a client-supplied X-Forwarded-For and let an
    // attacker rotate fake addresses past the throttle.
    val trustProxy = environment.config.envOrConfig("wikikt.server.trustProxy", "WIKIKT_TRUST_PROXY")
        ?.toBoolean() ?: false
    if (trustProxy) {
        install(XForwardedHeaders) { useLastProxy() }
    }

    install(Sessions) {
        cookie<UserSession>("WIKIKT_SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = sessionConfig.secureCookie
            cookie.maxAgeInSeconds = sessionConfig.maxAgeSeconds
            cookie.extensions["SameSite"] = "lax"
            transform(SessionTransportTransformerEncrypt(sessionConfig.encryptionKey, sessionConfig.signKey))
        }
        // The admin "site switcher" selection — which site the admin console is managing.
        cookie<com.wikikt.auth.AdminSiteSession>("WIKIKT_ADMIN_SITE") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = sessionConfig.secureCookie
            cookie.maxAgeInSeconds = sessionConfig.maxAgeSeconds
            cookie.extensions["SameSite"] = "lax"
            transform(SessionTransportTransformerEncrypt(sessionConfig.encryptionKey, sessionConfig.signKey))
        }
        // A password-verified login awaiting its second factor (MFA). Short-lived — the code step must be
        // completed promptly — and never grants access on its own (it's swapped for a real session only
        // after the code checks out).
        cookie<com.wikikt.auth.MfaPendingSession>("WIKIKT_MFA_PENDING") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = sessionConfig.secureCookie
            cookie.maxAgeInSeconds = 300
            cookie.extensions["SameSite"] = "lax"
            transform(SessionTransportTransformerEncrypt(sessionConfig.encryptionKey, sessionConfig.signKey))
        }
        // CSRF token for anonymous forms (forgot/reset password), where there's no login session to hold
        // one. Short-lived; the flow is expected to complete within an hour.
        cookie<com.wikikt.auth.AnonCsrfSession>("WIKIKT_ANON_CSRF") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = sessionConfig.secureCookie
            cookie.maxAgeInSeconds = 3600
            cookie.extensions["SameSite"] = "lax"
            transform(SessionTransportTransformerEncrypt(sessionConfig.encryptionKey, sessionConfig.signKey))
        }
    }

    install(
        createApplicationPlugin("SecurityHeaders") {
            onCall { call ->
                val headers = call.response.headers
                // Per-site Content-Security-Policy (baseline in code + the admin's extra trusted sources
                // from Administration > Security). Fail safe: any error falls back to the fixed baseline,
                // so a response is never sent without a CSP.
                val csp = runCatching { call.appContext.settings.contentSecurityPolicy(call.siteId()) }
                    .getOrElse { SettingsService.CspHeader("Content-Security-Policy", SettingsService.baselineCspValue()) }
                headers.append(csp.name, csp.value)
                headers.append("X-Content-Type-Options", "nosniff")
                // Redundant with frame-ancestors for modern browsers; kept for older ones.
                headers.append("X-Frame-Options", "SAMEORIGIN")
                headers.append("Referrer-Policy", "strict-origin-when-cross-origin")
                if (sessionConfig.secureCookie) {
                    // secureCookie doubles as the "deployed behind HTTPS" signal; HSTS on a plain-
                    // HTTP deployment would lock browsers out. Two years, no includeSubDomains —
                    // other services may share the domain of a self-hosted wiki.
                    headers.append("Strict-Transport-Security", "max-age=63072000")
                }
            }
        },
    )
}
