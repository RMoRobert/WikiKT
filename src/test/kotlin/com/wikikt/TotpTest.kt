package com.wikikt

import com.wikikt.auth.Totp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Correctness is pinned to the published test vectors: RFC 6238 Appendix B for TOTP and RFC 4648 §10 for
 * Base32. If a hand-rolled implementation matches those, it matches every authenticator app.
 */
class TotpTest {
    // RFC 6238 uses the ASCII secret "12345678901234567890" (20 bytes) with SHA-1.
    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @Test
    fun `matches the RFC 6238 8-digit test vectors`() {
        val vectors = listOf(
            59L to "94287082",
            1111111109L to "07081804",
            1111111111L to "14050471",
            1234567890L to "89005924",
            2000000000L to "69279037",
            20000000000L to "65353130",
        )
        for ((time, expected) in vectors) {
            assertEquals(expected, Totp.codeAt(rfcSecret, time, digits = 8), "TOTP-8 at t=$time")
        }
    }

    @Test
    fun `6-digit codes are the low 6 digits of the same vectors`() {
        assertEquals("287082", Totp.codeAt(rfcSecret, 59L, digits = 6))
        assertEquals("081804", Totp.codeAt(rfcSecret, 1111111109L, digits = 6))
        assertEquals("050471", Totp.codeAt(rfcSecret, 1111111111L, digits = 6))
    }

    @Test
    fun `verify accepts the current code and rejects a wrong one`() {
        val code = Totp.codeAt(rfcSecret, 1111111111L, digits = 6)
        assertTrue(Totp.verify(rfcSecret, code, timeSeconds = 1111111111L, driftSteps = 0))
        assertFalse(Totp.verify(rfcSecret, "000000", timeSeconds = 1111111111L, driftSteps = 0), "a wrong code is rejected")
        assertFalse(Totp.verify(rfcSecret, "12345", timeSeconds = 1111111111L), "wrong length is rejected")
        assertFalse(Totp.verify(rfcSecret, "abcdef", timeSeconds = 1111111111L), "non-digits are rejected")
    }

    @Test
    fun `verify tolerates one step of clock drift within the window but not outside it`() {
        val now = 1111111111L
        val prevStepCode = Totp.codeAt(rfcSecret, now - Totp.PERIOD_SECONDS, digits = 6) // one step ago
        assertTrue(Totp.verify(rfcSecret, prevStepCode, timeSeconds = now, driftSteps = 1), "±1 window accepts the previous step")
        assertFalse(Totp.verify(rfcSecret, prevStepCode, timeSeconds = now, driftSteps = 0), "a zero window rejects it")

        val farCode = Totp.codeAt(rfcSecret, now - 5 * Totp.PERIOD_SECONDS, digits = 6)
        assertFalse(Totp.verify(rfcSecret, farCode, timeSeconds = now, driftSteps = 1), "a code five steps old is outside the window")
    }

    @Test
    fun `base32 matches the RFC 4648 vector and round-trips`() {
        // RFC 4648 §10: BASE32("foobar") = "MZXW6YTBOI======" — we emit it unpadded.
        assertEquals("MZXW6YTBOI", Totp.base32Encode("foobar".toByteArray(Charsets.US_ASCII)))
        assertEquals("foobar", String(Totp.base32Decode("MZXW6YTBOI"), Charsets.US_ASCII))
        // Decoding tolerates padding, spaces, and lowercase.
        assertEquals("foobar", String(Totp.base32Decode("mzxw6 ytboi======"), Charsets.US_ASCII))

        val secret = Totp.generateSecret()
        assertTrue(Totp.base32Decode(Totp.base32Encode(secret)).contentEquals(secret), "encode/decode round-trips")
    }

    @Test
    fun `a freshly generated secret verifies against its own current code`() {
        val secret = Totp.generateSecret()
        val now = System.currentTimeMillis() / 1000
        assertTrue(Totp.verify(secret, Totp.codeAt(secret, now)), "a live secret validates its own code")
        assertEquals(Totp.SECRET_BYTES, secret.size)
    }

    @Test
    fun `provisioning uri carries the standard parameters and percent-encodes labels`() {
        val uri = Totp.provisioningUri("12345678901234567890".toByteArray(Charsets.US_ASCII), "My Wiki", "alice")
        assertTrue(uri.startsWith("otpauth://totp/My%20Wiki:alice?"), "label is issuer:account, space-encoded: $uri")
        assertTrue(uri.contains("secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"), "secret is Base32: $uri")
        assertTrue(uri.contains("issuer=My%20Wiki"), uri)
        assertTrue(uri.contains("algorithm=SHA1"), uri)
        assertTrue(uri.contains("digits=6"), uri)
        assertTrue(uri.contains("period=30"), uri)
    }
}
