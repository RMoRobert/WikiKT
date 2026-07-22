package com.wikikt.service

import java.io.ByteArrayOutputStream

/**
 * Removes privacy-sensitive metadata (EXIF GPS/camera, XMP, IPTC, comments) from uploaded images by
 * rewriting the container structure only. It never decodes pixels, so it adds no decompression-bomb
 * surface (the codebase deliberately avoids server-side image decoding). Operates on the already
 * magic-byte-validated bytes for the four supported raster types; anything it doesn't recognize, or any
 * structural anomaly, returns the input unchanged (i.e., fail open).
 */
object MetadataStripper {
    private const val FF = 0xFF

    /** Strips metadata appropriate to [mime]; returns the original array when there's nothing to do. */
    fun strip(bytes: ByteArray, mime: String): ByteArray = when (mime) {
        "image/jpeg" -> stripJpeg(bytes)
        "image/png" -> stripPng(bytes)
        "image/webp" -> stripWebp(bytes)
        else -> bytes // GIF carries no EXIF; unknown types are left untouched.
    }

    private fun u(b: Byte) = b.toInt() and 0xFF

    // --- JPEG: drop APP1 (EXIF/XMP), APP13 (IPTC/Photoshop) and COM (comment) marker segments ---

    private val JPEG_DROP = setOf(0xE1, 0xED, 0xFE)

    private fun stripJpeg(bytes: ByteArray): ByteArray {
        if (bytes.size < 4 || u(bytes[0]) != FF || u(bytes[1]) != 0xD8) return bytes // not SOI
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 2) // SOI
        var i = 2
        while (i + 1 < bytes.size) {
            if (u(bytes[i]) != FF) return bytes // expected a marker; bail rather than risk corruption
            var j = i
            while (j < bytes.size && u(bytes[j]) == FF) j++ // collapse any 0xFF fill bytes
            if (j >= bytes.size) return bytes
            val marker = u(bytes[j])
            val markerStart = j - 1 // the single 0xFF we keep
            when {
                // SOS or EOI: the rest of the file is entropy-coded scan data — copy it verbatim.
                marker == 0xDA || marker == 0xD9 -> {
                    out.write(bytes, markerStart, bytes.size - markerStart)
                    return out.toByteArray()
                }
                // Standalone markers (RST/TEM) carry no length.
                marker == 0x01 || marker in 0xD0..0xD7 -> {
                    out.write(bytes, markerStart, 2)
                    i = j + 1
                }
                else -> {
                    if (j + 2 >= bytes.size) return bytes
                    val segLen = (u(bytes[j + 1]) shl 8) or u(bytes[j + 2]) // includes the 2 length bytes
                    val segEnd = j + 1 + segLen
                    if (segLen < 2 || segEnd > bytes.size) return bytes
                    if (marker !in JPEG_DROP) out.write(bytes, markerStart, segEnd - markerStart)
                    i = segEnd
                }
            }
        }
        return out.toByteArray()
    }

    // --- PNG: drop the ancillary text / EXIF / timestamp chunks ---

    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PNG_DROP = setOf("tEXt", "iTXt", "zTXt", "eXIf", "tIME")

    private fun stripPng(bytes: ByteArray): ByteArray {
        if (bytes.size < PNG_SIG.size || !bytes.copyOfRange(0, PNG_SIG.size).contentEquals(PNG_SIG)) return bytes
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, PNG_SIG.size)
        var i = PNG_SIG.size
        while (i + 8 <= bytes.size) {
            val dataLen = int32BE(bytes, i)
            if (dataLen < 0) return bytes // >2GiB chunk — not a real PNG
            val type = String(bytes, i + 4, 4, Charsets.US_ASCII)
            val chunkTotal = 12L + dataLen // length(4) + type(4) + data + crc(4)
            if (i + chunkTotal > bytes.size) return bytes
            if (type !in PNG_DROP) out.write(bytes, i, chunkTotal.toInt())
            i += chunkTotal.toInt()
            if (type == "IEND") break
        }
        return out.toByteArray()
    }

    // --- WebP (RIFF): drop EXIF/XMP chunks and clear the VP8X feature flags for them ---

    private fun stripWebp(bytes: ByteArray): ByteArray {
        if (bytes.size < 12) return bytes
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WEBP") return bytes
        val body = ByteArrayOutputStream(bytes.size)
        var i = 12
        while (i + 8 <= bytes.size) {
            val fourCc = String(bytes, i, 4, Charsets.US_ASCII)
            val size = int32LE(bytes, i + 4)
            if (size < 0) return bytes
            val padded = size + (size and 1) // chunks are padded to an even length
            val chunkTotal = 8L + padded
            if (i + chunkTotal > bytes.size) return bytes
            when (fourCc) {
                "EXIF", "XMP " -> {} // drop
                "VP8X" -> {
                    // Copy, but clear the EXIF (0x08) and XMP (0x04) feature bits in the flags byte so
                    // readers don't expect chunks we removed.
                    val copy = bytes.copyOfRange(i, i + chunkTotal.toInt())
                    if (copy.size > 8) copy[8] = (copy[8].toInt() and 0x08.inv() and 0x04.inv()).toByte()
                    body.write(copy, 0, copy.size)
                }
                else -> body.write(bytes, i, chunkTotal.toInt())
            }
            i += chunkTotal.toInt()
        }
        val payload = body.toByteArray()
        val out = ByteArrayOutputStream(12 + payload.size)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(int32LEBytes(4 + payload.size)) // RIFF size = "WEBP" + chunks
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    private fun int32BE(b: ByteArray, o: Int): Int =
        (u(b[o]) shl 24) or (u(b[o + 1]) shl 16) or (u(b[o + 2]) shl 8) or u(b[o + 3])

    private fun int32LE(b: ByteArray, o: Int): Int =
        u(b[o]) or (u(b[o + 1]) shl 8) or (u(b[o + 2]) shl 16) or (u(b[o + 3]) shl 24)

    private fun int32LEBytes(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
