package com.wikikt.auth

import java.security.MessageDigest

/**
 * SHA-256 hex digest for opaque credentials stored at rest (session tokens, API keys). A plain,
 * unsalted hash is sufficient — the inputs are 256-bit `SecureRandom` values, so rainbow tables
 * and brute force are moot; the point is that a leaked database row is not a usable credential.
 */
object TokenHash {
    fun sha256Hex(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
