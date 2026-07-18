package com.wikikt

import com.wikikt.service.ImageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageTypeTest {
    @Test
    fun `detects supported image types by magic bytes`() {
        assertEquals("image/png", ImageType.detect(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)))
        assertEquals("image/jpeg", ImageType.detect(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("image/gif", ImageType.detect("GIF89a".toByteArray()))
        assertEquals("image/gif", ImageType.detect("GIF87a".toByteArray()))
        val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray()
        assertEquals("image/webp", ImageType.detect(webp))
    }

    @Test
    fun `rejects non-images and look-alikes`() {
        // RIFF container that is not WebP (e.g. WAV) must not pass.
        val wav = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WAVE".toByteArray()
        assertNull(ImageType.detect(wav))
        assertNull(ImageType.detect("<!DOCTYPE html><script>".toByteArray()), "HTML renamed to an image is rejected")
        assertNull(ImageType.detect(ByteArray(0)))
        assertNull(ImageType.detect(byteArrayOf(0x89.toByte(), 0x50))) // truncated PNG signature
    }
}
