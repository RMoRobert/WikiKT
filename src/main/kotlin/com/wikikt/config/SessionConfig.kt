package com.wikikt.config

import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory

private val sessionLogger = LoggerFactory.getLogger("com.wikikt.config.session")

/**
 * Configuration for the encrypted/signed session cookie.
 *
 * [encryptionKey] must be 16, 24, or 32 bytes (AES-128/192/256); [signKey] is the HMAC key.
 * Both are supplied as hex strings via config (`wikikt.session.*`) or env
 * (`WIKIKT_SESSION_ENCRYPTION_KEY` / `WIKIKT_SESSION_SIGN_KEY`). When absent a fixed
 * development key is used and a warning is logged — never rely on that in production, because
 * the keys are public in source control and anyone could forge a session cookie.
 */
data class SessionConfig(
    val encryptionKey: ByteArray,
    val signKey: ByteArray,
    val secureCookie: Boolean,
    val maxAgeSeconds: Long,
)

// Default session lifetime: 15 days, for both the browser cookie and the server-side session row.
// Explicit revocation is immediate and does NOT wait for this: sessions are server-side, so deleting
// the row — on logout, user deletion, or a password change — logs the user out on their next request,
// and permissions/groups are re-read from the DB every request. This bound only matters for a session
// nobody explicitly revokes: an idle login eventually expires, and a stolen-but-unrevoked cookie stops
// working after at most this long.
private const val DEFAULT_MAX_AGE_SECONDS = 15L * 24 * 60 * 60

// Deterministic dev-only keys so sessions survive a restart in local development.
private val DEV_ENCRYPTION_KEY = "00112233445566778899aabbccddeeff".decodeHex()
private val DEV_SIGN_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".decodeHex()

fun ApplicationConfig.loadSessionConfig(): SessionConfig {
    val configuredEncryption = envOrConfig("wikikt.session.encryptionKey", "WIKIKT_SESSION_ENCRYPTION_KEY")
    val configuredSign = envOrConfig("wikikt.session.signKey", "WIKIKT_SESSION_SIGN_KEY")

    val production = isProductionEnvironment()

    if (configuredEncryption == null || configuredSign == null) {
        val message = "Session keys are not configured. Set wikikt.session.encryptionKey / " +
            "wikikt.session.signKey (or WIKIKT_SESSION_ENCRYPTION_KEY / WIKIKT_SESSION_SIGN_KEY) " +
            "to random hex values."
        if (production) {
            // Falling back to the in-source dev keys in production would let anyone forge a session
            // cookie and become any user, so refuse to start instead.
            throw IllegalStateException("Refusing to start in production: $message")
        }
        sessionLogger.warn("$message Using built-in DEVELOPMENT keys for now.")
    }

    val encryptionKey = configuredEncryption?.decodeSessionKey("encryptionKey") ?: DEV_ENCRYPTION_KEY
    val signKey = configuredSign?.decodeSessionKey("signKey") ?: DEV_SIGN_KEY

    val secureCookie = envOrConfig("wikikt.session.secureCookie", "WIKIKT_SESSION_SECURE_COOKIE")
        ?.toBoolean() ?: false

    if (production && !secureCookie) {
        // Without the Secure attribute the session cookie is sent over plain HTTP (and HSTS is not
        // emitted), so an on-path attacker can intercept/replay it and hijack any session. Refuse to
        // start rather than ship interceptable sessions, matching the default-password / session-key
        // checks. A production deployment must terminate TLS and set this true.
        throw IllegalStateException(
            "Refusing to start in production: secureCookie is disabled, so the session cookie would be " +
                "sent over plain HTTP. Set wikikt.session.secureCookie=true (WIKIKT_SESSION_SECURE_COOKIE=true) " +
                "on an HTTPS deployment.",
        )
    }

    val maxAgeSeconds = envOrConfig("wikikt.session.maxAgeSeconds", "WIKIKT_SESSION_MAX_AGE_SECONDS")
        ?.toLongOrNull() ?: DEFAULT_MAX_AGE_SECONDS

    return SessionConfig(
        encryptionKey = encryptionKey,
        signKey = signKey,
        secureCookie = secureCookie,
        maxAgeSeconds = maxAgeSeconds,
    )
}

private fun String.decodeSessionKey(name: String): ByteArray =
    try {
        trim().decodeHex()
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("wikikt.session.$name must be a hex string", e)
    }

internal fun String.decodeHex(): ByteArray {
    require(length % 2 == 0) { "hex string must have an even length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "invalid hex character" }
        ((hi shl 4) or lo).toByte()
    }
}
