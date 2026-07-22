package com.wikikt

import com.wikikt.service.MetadataStripper
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stripper parses container structure only (no pixel decode), so these fixtures use real segment/
 * chunk framing with placeholder payloads — enough to exercise the removal logic.
 */
class MetadataStripperTest {

    private fun bytes(block: ByteArrayOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().apply(block).toByteArray()

    private fun ByteArrayOutputStream.u(vararg v: Int) = v.forEach { write(it and 0xFF) }
    private fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.US_ASCII))
    private fun ByteArray.containsSeq(s: String): Boolean {
        val needle = s.toByteArray(Charsets.US_ASCII)
        if (needle.isEmpty() || size < needle.size) return false
        for (i in 0..size - needle.size) if (copyOfRange(i, i + needle.size).contentEquals(needle)) return true
        return false
    }

    // --- JPEG ---

    @Test
    fun `jpeg APP1 EXIF is removed but JFIF and image data are kept`() {
        val jpeg = bytes {
            u(0xFF, 0xD8) // SOI
            // APP0 / JFIF (keep): FF E0, length 0x0008 (2 len bytes + 6 payload)
            u(0xFF, 0xE0, 0x00, 0x08); ascii("JFIF"); u(0x00, 0x01)
            // APP1 / EXIF (drop): FF E1, length 0x000E (2 + 12 payload)
            u(0xFF, 0xE1, 0x00, 0x0E); ascii("Exif"); u(0x00, 0x00); ascii("GPS123")
            // SOS: FF DA, length 0x0008, then scan data, then EOI
            u(0xFF, 0xDA, 0x00, 0x08); u(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
            u(0xAA, 0xBB, 0xCC) // entropy-coded scan bytes
            u(0xFF, 0xD9) // EOI
        }

        val out = MetadataStripper.strip(jpeg, "image/jpeg")

        assertTrue(out.size < jpeg.size, "the EXIF segment was removed")
        assertFalse(out.containsSeq("Exif"), "no EXIF marker remains")
        assertFalse(out.containsSeq("GPS123"), "no EXIF payload remains")
        assertTrue(out.containsSeq("JFIF"), "the JFIF (APP0) segment is preserved")
        assertEquals(0xFF, out[0].toInt() and 0xFF)
        assertEquals(0xD8, out[1].toInt() and 0xFF)
        assertEquals(0xD9, out[out.size - 1].toInt() and 0xFF) // still ends at EOI
    }

    @Test
    fun `jpeg without metadata is returned unchanged`() {
        val jpeg = bytes {
            u(0xFF, 0xD8)
            u(0xFF, 0xE0, 0x00, 0x08); ascii("JFIF"); u(0x00, 0x01)
            u(0xFF, 0xDA, 0x00, 0x04); u(0x11, 0x22); u(0xFF, 0xD9)
        }
        assertContentEquals(jpeg, MetadataStripper.strip(jpeg, "image/jpeg"))
    }

    // --- PNG ---

    @Test
    fun `png tEXt and eXIf chunks are removed but IHDR IDAT IEND are kept`() {
        fun ByteArrayOutputStream.chunk(len: Int, type: String, data: ByteArray) {
            u((len ushr 24) and 0xFF, (len ushr 16) and 0xFF, (len ushr 8) and 0xFF, len and 0xFF)
            ascii(type); write(data); u(0xDE, 0xAD, 0xBE, 0xEF) // placeholder CRC (stripper ignores it)
        }
        val png = bytes {
            u(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) // signature
            chunk(2, "IHDR", byteArrayOf(1, 2))
            chunk(7, "tEXt", "Comment".toByteArray(Charsets.US_ASCII))
            chunk(3, "eXIf", byteArrayOf(9, 9, 9))
            chunk(2, "IDAT", byteArrayOf(0x33, 0x44))
            chunk(0, "IEND", ByteArray(0))
        }

        val out = MetadataStripper.strip(png, "image/png")

        assertTrue(out.size < png.size)
        assertFalse(out.containsSeq("tEXt"))
        assertFalse(out.containsSeq("eXIf"))
        assertFalse(out.containsSeq("Comment"))
        assertTrue(out.containsSeq("IHDR"))
        assertTrue(out.containsSeq("IDAT"))
        assertTrue(out.containsSeq("IEND"))
    }

    // --- WebP ---

    @Test
    fun `webp EXIF chunk is removed and the RIFF size is corrected`() {
        fun webpChunk(fourcc: String, data: ByteArray) = bytes {
            ascii(fourcc)
            val n = data.size
            u(n and 0xFF, (n ushr 8) and 0xFF, (n ushr 16) and 0xFF, (n ushr 24) and 0xFF)
            write(data)
            if (n and 1 == 1) u(0x00) // pad to even
        }
        val vp8 = webpChunk("VP8 ", byteArrayOf(1, 2, 3, 4))
        val exif = webpChunk("EXIF", "GPS-here".toByteArray(Charsets.US_ASCII))
        val body = vp8 + exif
        val webp = bytes {
            ascii("RIFF")
            val riffSize = 4 + body.size
            u(riffSize and 0xFF, (riffSize ushr 8) and 0xFF, (riffSize ushr 16) and 0xFF, (riffSize ushr 24) and 0xFF)
            ascii("WEBP")
            write(body)
        }

        val out = MetadataStripper.strip(webp, "image/webp")

        assertTrue(out.containsSeq("RIFF"))
        assertTrue(out.containsSeq("WEBP"))
        assertTrue(out.containsSeq("VP8 "))
        assertFalse(out.containsSeq("EXIF"), "the EXIF chunk is gone")
        assertFalse(out.containsSeq("GPS-here"))
        // RIFF size field now equals 4 ("WEBP") + remaining chunk bytes.
        val declared = (out[4].toInt() and 0xFF) or ((out[5].toInt() and 0xFF) shl 8) or
            ((out[6].toInt() and 0xFF) shl 16) or ((out[7].toInt() and 0xFF) shl 24)
        assertEquals(out.size - 8, declared, "RIFF size matches the trimmed payload")
    }

    // --- passthrough ---

    @Test
    fun `gif and unknown types are returned unchanged`() {
        val gif = "GIF89a-pretend-image".toByteArray(Charsets.US_ASCII)
        assertContentEquals(gif, MetadataStripper.strip(gif, "image/gif"))
        assertContentEquals(gif, MetadataStripper.strip(gif, "application/octet-stream"))
    }

    @Test
    fun `malformed jpeg is left intact rather than corrupted`() {
        // SOI then a byte that isn't a marker — the stripper must bail and return the input verbatim.
        val junk = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x11, 0x22, 0x33)
        assertContentEquals(junk, MetadataStripper.strip(junk, "image/jpeg"))
    }
}
