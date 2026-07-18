package com.wikikt

import com.wikikt.auth.MfaSecretCipher
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class MfaSecretCipherTest {
    private val cipher = MfaSecretCipher(ByteArray(32) { it.toByte() })

    @Test
    fun `encrypt then decrypt round-trips`() {
        val secret = "12345678901234567890".toByteArray()
        assertContentEquals(secret, cipher.decrypt(cipher.encrypt(secret)))
    }

    @Test
    fun `each encryption uses a fresh iv`() {
        val s = "abc".toByteArray()
        assertNotEquals(cipher.encrypt(s), cipher.encrypt(s), "a random IV makes each ciphertext differ")
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val blob = Base64.getDecoder().decode(cipher.encrypt("secret".toByteArray()))
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte() // corrupt the GCM tag
        assertFailsWith<Exception> { cipher.decrypt(Base64.getEncoder().encodeToString(blob)) }
    }

    @Test
    fun `a different key cannot decrypt`() {
        val enc = cipher.encrypt("secret".toByteArray())
        val other = MfaSecretCipher(ByteArray(32) { (it + 7).toByte() })
        assertFailsWith<Exception> { other.decrypt(enc) }
    }
}
