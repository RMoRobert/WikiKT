package com.wikikt.service

/**
 * Detects raster image types by their magic bytes — the authoritative check for uploads. We do NOT
 * trust the client-declared content type or the filename extension (both forgeable), nor
 * `Files.probeContentType` (extension/OS-dependent and unreliable on a headless JVM for files stored
 * without an extension). An HTML/script file renamed `.png` fails here, closing the asset-XSS vector.
 */
object ImageType {
    fun detect(bytes: ByteArray): String? {
        fun matches(signature: IntArray, offset: Int = 0): Boolean {
            if (bytes.size < offset + signature.size) return false
            return signature.indices.all { (bytes[offset + it].toInt() and 0xFF) == signature[it] }
        }
        return when {
            matches(intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
            matches(intArrayOf(0xFF, 0xD8, 0xFF)) -> "image/jpeg"
            matches(intArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)) ||
                matches(intArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)) -> "image/gif"
            // WebP: "RIFF" .... "WEBP" — must check both ranges ("RIFF" alone is also WAV/AVI).
            matches(intArrayOf(0x52, 0x49, 0x46, 0x46)) && matches(intArrayOf(0x57, 0x45, 0x42, 0x50), offset = 8) -> "image/webp"
            else -> null
        }
    }
}
