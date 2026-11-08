package com.wikikt

import com.wikikt.model.assetFilename
import com.wikikt.model.cleanFilename
import com.wikikt.model.slugFilename
import com.wikikt.model.withImageExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Upload filename handling: the cleaned display/save-as name, its extension, and the URL slug. */
class FileNamesTest {
    @Test
    fun `display names keep case, spaces and any script`() {
        assertEquals("My Logo (final).png", cleanFilename("My Logo (final).png"))
        assertEquals("日本 x.png", cleanFilename("日本 x.png"))
        assertEquals("👩\u200d💻.png", cleanFilename("👩\u200d💻.png")) // ZWJ sequence survives
    }

    @Test
    fun `display names drop the client directory, invisible characters and unsafe punctuation`() {
        assertEquals("x.png", cleanFilename("C:\\Users\\bob\\Desktop\\x.png"))
        assertEquals("x.png", cleanFilename("/home/bob/x.png"))
        // Browsers send `"` as %22 in a multipart filename; decoded, then replaced like any other `"`.
        assertEquals("naïve _q_.png", cleanFilename("naïve %22q%22.png"))
        // Bidi override (would show "gnp.exe" as "exe.png"), zero-width space, BOM, controls: dropped.
        assertEquals("invoiceexe.png", cleanFilename("invoice\u202eexe.png"))
        assertEquals("ab.png", cleanFilename("a\u200b\ufeffb\u0007.png"))
        // Every kind of space becomes one plain space; ends are trimmed of spaces and dots.
        assertEquals("a b c.png", cleanFilename("\ta\u00a0\u3000b\r\n\u2028c.png "))
        assertEquals("x", cleanFilename("..x.."))
        // Windows-reserved chars, and `%` / `?` that browsers re-decode in a plain filename parameter.
        assertEquals("a_b_c_d_e_f_g_.png", cleanFilename("a*b:c<d>e|f?g%.png"))
        assertEquals("", cleanFilename("\u202e\u200b"))
    }

    @Test
    fun `display names are NFC and capped in UTF-8 bytes, keeping the extension`() {
        assertEquals("naïve.png", cleanFilename("nai\u0308ve.png"))
        val long = cleanFilename("日".repeat(200) + ".png")
        assertEquals("日".repeat(78) + ".png", long) // 78 × 3 bytes + 4 = 238 ≤ 240
        // Never split a surrogate pair.
        val emoji = cleanFilename("😀".repeat(100) + ".png")
        assertEquals("😀".repeat(59) + ".png", emoji)
        assertTrue(emoji.toByteArray().size <= 240)
    }

    @Test
    fun `the save-as extension always matches the detected type`() {
        assertEquals("x.PNG", withImageExtension("x.PNG", "image/png"))
        assertEquals("x.jpeg", withImageExtension("x.jpeg", "image/jpeg"))
        assertEquals("x.jpg", withImageExtension("x.png", "image/jpeg")) // another image type's: swapped
        assertEquals("invoice.hta.png", withImageExtension("invoice.hta", "image/png")) // anything else: appended
        assertEquals("photo.webp", withImageExtension("photo", "image/webp"))
        assertEquals("x.hta", withImageExtension("x.hta", "application/octet-stream")) // unknown type: untouched
        assertEquals("invoice.html.png", assetFilename("invoice.html", "image/png"))
        assertEquals("file.gif", assetFilename("\u202e", "image/gif"))
    }

    @Test
    fun `upload slugs keep letters in any script and hyphenate the rest`() {
        assertEquals("日本-x.png", slugFilename("日本 x.png", "image/png"))
        assertEquals("naïve-x.png", slugFilename("Naïve X.PNG", "image/png"))
        assertEquals("naïve.png", slugFilename("nai\u0308ve.png", "image/png")) // NFC
        assertEquals("ünïcödé-name.jpg", slugFilename("Ünïcödé  Name.JPG", "image/jpeg"))
        assertEquals("my_file-v2.png", slugFilename("my_file (v2).png", "image/png")) // `_` kept
        assertEquals("my-photo.png", slugFilename("my.photo.png", "image/png")) // one dot per asset name
        assertEquals("مرحبا.png", slugFilename("مرحبا.png", "image/png"))
        assertEquals("file.png", slugFilename("😀.png", "image/png"))
        assertEquals("file.png", slugFilename("\u202e😀\u200b.png", "image/png"))
        assertEquals("a.png", slugFilename("\u0301a.png", "image/png")) // a mark with nothing to attach to
    }

    @Test
    fun `upload slugs get an extension that matches the detected type`() {
        assertEquals("photo.jpg", slugFilename("photo.png", "image/jpeg"))
        assertEquals("photo.jfif", slugFilename("photo.jfif", "image/jpeg"))
        assertEquals("photo.png", slugFilename("photo", "image/png"))
        assertEquals("invoice-hta.png", slugFilename("invoice.hta", "image/png"))
        assertEquals("写真-画像.png", slugFilename("写真.画像", "image/png"))
    }
}
