package com.wikikt.auth

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts small secrets (TOTP shared secrets) at rest with AES-GCM under a dedicated instance key
 * (`WIKIKT_MFA_KEY`). Output is Base64 of `iv(12) || ciphertext‖tag(16)`, with a fresh random IV per call.
 * GCM authenticates, so a tampered blob or the wrong key fails to decrypt (throws) rather than returning
 * garbage — a leaked database can't yield anyone's authenticator secret without the key. Dependency-free
 * (JDK `javax.crypto` only).
 */
class MfaSecretCipher(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    fun encrypt(plaintext: ByteArray): String {
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
        }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext))
    }

    /** Decrypts a blob produced by [encrypt]. Throws if the key is wrong or the blob was tampered with. */
    fun decrypt(encoded: String): ByteArray {
        val blob = Base64.getDecoder().decode(encoded)
        require(blob.size > IV_BYTES) { "ciphertext too short" }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val ct = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(ct)
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
