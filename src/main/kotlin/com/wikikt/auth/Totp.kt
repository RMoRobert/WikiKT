package com.wikikt.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based One-Time Passwords (TOTP, RFC 6238), built on HMAC-based OTP (HOTP, RFC 4226). Hand-rolled
 * and dependency-free — the whole thing is HMAC-SHA1 over a time-step counter plus the standard dynamic
 * truncation — so a leaked authenticator secret is the only thing that can produce a valid code.
 *
 * Parameters are the interoperable defaults every authenticator app understands: **SHA-1**, **6** digits,
 * a **30-second** period, and a **±1 step** verification window for clock skew. (SHA-1 here means HMAC-SHA1,
 * which is not a weakness for OTP; SHA-256/512 and non-30 periods exist in the spec but many apps ignore
 * them, so we stick to the defaults.) The digit count is a parameter only so the code can be checked against
 * the RFC 6238 8-digit test vectors.
 *
 * This file is pure and stateless: it does not store, encrypt, or persist anything. Enrollment, secret
 * storage-at-rest, recovery codes, and rate limiting live in the MFA service/routing layers.
 */
object Totp {
    const val DIGITS = 6
    const val PERIOD_SECONDS = 30L
    const val SECRET_BYTES = 20 // 160 bits — the RFC-recommended size for HMAC-SHA1

    private val random = SecureRandom()
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** A fresh random shared secret (raw bytes). Show it to the user Base32-encoded / in the QR. */
    fun generateSecret(bytes: Int = SECRET_BYTES): ByteArray = ByteArray(bytes).also { random.nextBytes(it) }

    /**
     * Verifies [code] against [secret] at [timeSeconds] (epoch seconds), tolerating ±[driftSteps] time
     * steps of clock skew. Length/shape are checked first (a fast, non-secret reject), then each candidate
     * is compared in constant time.
     */
    fun verify(
        secret: ByteArray,
        code: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        digits: Int = DIGITS,
        periodSeconds: Long = PERIOD_SECONDS,
        driftSteps: Int = 1,
    ): Boolean = matchingStep(secret, code, timeSeconds, digits, periodSeconds, driftSteps) != null

    /**
     * Like [verify], but returns the TIME STEP the code matched (or null if none in the window). Callers
     * persist the matched step to block replay: a code is accepted only if its step is newer than the last
     * one used for that secret.
     */
    fun matchingStep(
        secret: ByteArray,
        code: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        digits: Int = DIGITS,
        periodSeconds: Long = PERIOD_SECONDS,
        driftSteps: Int = 1,
    ): Long? {
        val trimmed = code.trim()
        if (trimmed.length != digits || trimmed.any { !it.isDigit() }) return null
        val step = Math.floorDiv(timeSeconds, periodSeconds)
        val submitted = trimmed.toByteArray(Charsets.US_ASCII)
        for (drift in -driftSteps..driftSteps) {
            val candidate = hotp(secret, step + drift, digits).toByteArray(Charsets.US_ASCII)
            // Constant-time compare (both are fixed-width digit strings, so length leaks nothing).
            if (MessageDigest.isEqual(candidate, submitted)) return step + drift
        }
        return null
    }

    /** The code for [secret] at [timeSeconds] — used for enrollment display and (mostly) tests. */
    fun codeAt(
        secret: ByteArray,
        timeSeconds: Long,
        digits: Int = DIGITS,
        periodSeconds: Long = PERIOD_SECONDS,
    ): String = hotp(secret, Math.floorDiv(timeSeconds, periodSeconds), digits)

    /**
     * The `otpauth://totp/...` provisioning URI an authenticator app consumes (via QR or manual entry).
     * [issuer] is the site name; [account] the username.
     */
    fun provisioningUri(
        secret: ByteArray,
        issuer: String,
        account: String,
        digits: Int = DIGITS,
        periodSeconds: Long = PERIOD_SECONDS,
    ): String {
        // The label is `issuer:account` with a LITERAL colon separator (Key URI Format); the issuer and
        // account are percent-encoded individually so the colon isn't turned into %3A.
        val label = "${urlEncode(issuer)}:${urlEncode(account)}"
        return "otpauth://totp/$label?secret=${base32Encode(secret)}" +
            "&issuer=${urlEncode(issuer)}&algorithm=SHA1&digits=$digits&period=$periodSeconds"
    }

    // --- RFC 4226 HOTP ---

    private fun hotp(key: ByteArray, counter: Long, digits: Int): String {
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xff).toByte()
            c = c ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
            .apply { init(SecretKeySpec(key, "HmacSHA1")) }
            .doFinal(msg)
        // Dynamic truncation (RFC 4226 §5.3): the low nibble of the last byte picks a 4-byte window.
        val offset = mac[mac.size - 1].toInt() and 0x0f
        val binary = ((mac[offset].toInt() and 0x7f) shl 24) or
            ((mac[offset + 1].toInt() and 0xff) shl 16) or
            ((mac[offset + 2].toInt() and 0xff) shl 8) or
            (mac[offset + 3].toInt() and 0xff)
        return (binary % pow10(digits)).toString().padStart(digits, '0')
    }

    private fun pow10(n: Int): Int {
        var p = 1
        repeat(n) { p *= 10 }
        return p
    }

    // --- Base32 (RFC 4648, unpadded, uppercase) — the encoding authenticator apps and otpauth use ---

    fun base32Encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                sb.append(BASE32_ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1f])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            sb.append(BASE32_ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1f])
        }
        return sb.toString()
    }

    fun base32Decode(encoded: String): ByteArray {
        val clean = encoded.trim().replace(" ", "").trimEnd('=').uppercase()
        if (clean.isEmpty()) return ByteArray(0)
        val out = ArrayList<Byte>(clean.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val v = BASE32_ALPHABET.indexOf(ch)
            require(v >= 0) { "invalid base32 character: $ch" }
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xff).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
