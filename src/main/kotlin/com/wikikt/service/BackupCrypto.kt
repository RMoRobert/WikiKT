package com.wikikt.service

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Whole-backup, password-based encryption. When an admin sets a backup password the ENTIRE backup ZIP is
 * encrypted into an opaque container: nothing in it — pages, users, hashes, or the SMTP password/git
 * token it then carries — is readable or restorable without that password.
 *
 * Construction is streaming and memory-bounded (a backup can be gigabytes): AES-256-CTR for confidentiality
 * plus an HMAC-SHA256 over the whole header+ciphertext for authentication (encrypt-then-MAC). CTR needs no
 * buffering, and the single MAC at the end catches a wrong password, truncation, or any tampering — so
 * decryption to a temp file either succeeds wholesale or is rejected before a single row is restored.
 * Both keys are PBKDF2-HMAC-SHA256-derived from the password with a fresh random salt. Dependency-free
 * (JDK `javax.crypto` only).
 *
 * Container layout: `MAGIC(8) | version(1) | salt(16) | iv(16) | ciphertext(…) | hmac(32)`.
 */
object BackupCrypto {
    /** File signature marking an encrypted backup; distinct from a ZIP's `PK\x03\x04` so the two are told apart. */
    val MAGIC: ByteArray = "WKBKENC1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val ITERATIONS = 210_000 // OWASP 2023 guidance for PBKDF2-HMAC-SHA256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 16 // AES/CTR block-sized counter block
    private const val MAC_BYTES = 32 // HMAC-SHA256 tag, held back as the stream trailer
    private const val KEY_BITS = 512 // 32-byte AES key + 32-byte MAC key, split from one derivation
    private val random = SecureRandom()

    /** True if [source] begins with [MAGIC] — i.e. it's an encrypted backup rather than a plain ZIP. */
    fun isEncryptedBackup(source: Path): Boolean {
        val head = ByteArray(MAGIC.size)
        Files.newInputStream(source).use { if (!it.readFully(head)) return false }
        return head.contentEquals(MAGIC)
    }

    /**
     * Decrypts an encrypted backup [source] into [dest], returning false (leaving [dest] not to be used)
     * when the password is wrong or the file is truncated/tampered — never throwing on those. Verifies the
     * HMAC over everything before the result is trusted.
     */
    fun decryptToFile(source: Path, password: String, dest: Path): Boolean {
        Files.newInputStream(source).use { input ->
            val magic = ByteArray(MAGIC.size)
            if (!input.readFully(magic) || !magic.contentEquals(MAGIC)) return false
            val header = ByteArray(1 + SALT_BYTES + IV_BYTES)
            if (!input.readFully(header)) return false
            if (header[0] != VERSION) return false
            val salt = header.copyOfRange(1, 1 + SALT_BYTES)
            val iv = header.copyOfRange(1 + SALT_BYTES, 1 + SALT_BYTES + IV_BYTES)
            val (encKey, macKey) = deriveKeys(password, salt)
            val mac = Mac.getInstance("HmacSHA256").apply { init(macKey) }
            mac.update(MAGIC); mac.update(header) // MAC binds the header (version/salt/iv) too
            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, encKey, IvParameterSpec(iv))
            }
            Files.newOutputStream(dest).use { out ->
                // Hold back the last MAC_BYTES of the stream as the trailer; everything before it is ciphertext.
                var carry = ByteArray(0)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val combined = carry + buf.copyOf(n)
                    if (combined.size <= MAC_BYTES) { carry = combined; continue }
                    val ctLen = combined.size - MAC_BYTES
                    val ct = combined.copyOfRange(0, ctLen)
                    carry = combined.copyOfRange(ctLen, combined.size)
                    mac.update(ct)
                    out.write(cipher.update(ct))
                }
                if (carry.size != MAC_BYTES) return false // truncated before a full tag
                out.write(cipher.doFinal())
                return MessageDigest.isEqual(mac.doFinal(), carry) // constant-time compare
            }
        }
    }

    private fun deriveKeys(password: String, salt: ByteArray): Pair<SecretKeySpec, SecretKeySpec> {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)).encoded
        return SecretKeySpec(bytes, 0, 32, "AES") to SecretKeySpec(bytes, 32, 32, "HmacSHA256")
    }

    /** Reads exactly [b].size bytes into [b]; false if EOF came first (stream too short). */
    private fun InputStream.readFully(b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val n = read(b, off, b.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    /**
     * An [OutputStream] that transparently encrypts everything written to it into the container format on
     * [out]. Write the plaintext backup ZIP through this; closing it flushes the final block and appends the
     * HMAC trailer. The header (magic/version/salt/iv) is emitted immediately on construction.
     */
    class EncryptingOutputStream(private val out: OutputStream, password: String) : OutputStream() {
        private val mac: Mac
        private val cipher: Cipher

        init {
            val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
            val (encKey, macKey) = deriveKeys(password, salt)
            mac = Mac.getInstance("HmacSHA256").apply { init(macKey) }
            cipher = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encKey, IvParameterSpec(iv)) }
            val header = byteArrayOf(VERSION) + salt + iv
            out.write(MAGIC); out.write(header)
            mac.update(MAGIC); mac.update(header)
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val ct = cipher.update(b, off, len)
            if (ct != null && ct.isNotEmpty()) { out.write(ct); mac.update(ct) }
        }

        override fun flush() = out.flush()

        override fun close() {
            val ct = cipher.doFinal()
            if (ct.isNotEmpty()) { out.write(ct); mac.update(ct) }
            out.write(mac.doFinal()) // 32-byte trailer
            out.flush()
            out.close()
        }
    }
}
