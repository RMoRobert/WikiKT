package com.wikikt.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.util.encodeBase64
import java.security.MessageDigest
import java.security.SecureRandom

/** Name of the hidden form field and the session-stored token for CSRF protection. */
const val CSRF_FIELD = "_csrf"

/** Request/response header carrying the CSRF token for JSON API clients. */
const val CSRF_HEADER = "X-CSRF-Token"

private val secureRandom = SecureRandom()

/** Generates a fresh, unguessable CSRF token to store in a user's session at login. */
fun generateCsrfToken(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return bytes.encodeBase64()
}

/** The CSRF token bound to the current session, or null if there is no session. */
fun ApplicationCall.csrfToken(): String? = sessions.get<UserSession>()?.csrfToken?.takeIf { it.isNotEmpty() }

/** Ready-to-embed hidden input carrying the session CSRF token (empty when not logged in). */
fun ApplicationCall.csrfField(): String {
    val token = csrfToken() ?: return ""
    return csrfFieldFor(token)
}

/** A hidden CSRF input carrying [token]. Used by the anonymous forms, which mint their own token
 *  ([ensureAnonCsrf]) rather than the login-session one that [csrfField] reads. */
fun csrfFieldFor(token: String): String {
    val escaped = token.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    return "<input type=\"hidden\" name=\"$CSRF_FIELD\" value=\"$escaped\">"
}

/**
 * Returns the anonymous-form CSRF token, minting and storing one in the [AnonCsrfSession] cookie if the
 * caller doesn't have one yet. Call this from the GET that renders a pre-login form (forgot/reset
 * password); the matching POST validates the submitted token with [isAnonCsrfValid].
 */
fun ApplicationCall.ensureAnonCsrf(): String {
    sessions.get<AnonCsrfSession>()?.token?.takeIf { it.isNotEmpty() }?.let { return it }
    val token = generateCsrfToken()
    sessions.set(AnonCsrfSession(token))
    return token
}

/**
 * Constant-time check of a [submitted] token against the [AnonCsrfSession] cookie. Returns false when
 * either side is missing, so a request without the issued cookie can never pass.
 */
fun ApplicationCall.isAnonCsrfValid(submitted: String?): Boolean {
    val expected = sessions.get<AnonCsrfSession>()?.token?.takeIf { it.isNotEmpty() } ?: return false
    if (submitted.isNullOrEmpty()) return false
    return MessageDigest.isEqual(expected.toByteArray(), submitted.toByteArray())
}

/**
 * Constant-time comparison of a submitted token against the session token. Returns false when
 * either is missing, so a request with no session can never pass.
 */
fun ApplicationCall.isCsrfValid(submitted: String?): Boolean {
    val expected = csrfToken() ?: return false
    if (submitted.isNullOrEmpty()) return false
    return MessageDigest.isEqual(expected.toByteArray(), submitted.toByteArray())
}

/**
 * CSRF check for JSON API mutations. CSRF only threatens requests that ride on an ambient
 * browser credential (the session cookie), so:
 *  - No session cookie -> not a CSRF-prone request (e.g. a future API-key client, or login
 *    itself before a session exists). Allowed here; the route's own auth/permission check applies.
 *  - Session cookie present -> require a matching [CSRF_HEADER]. A cross-site caller cannot set a
 *    custom header without a CORS preflight, which this server does not grant, so this blocks the
 *    forged-request case while leaving same-origin and token-bearing clients working.
 */
fun ApplicationCall.isApiCsrfValid(): Boolean {
    sessions.get<UserSession>() ?: return true
    return isCsrfValid(request.header(CSRF_HEADER))
}
