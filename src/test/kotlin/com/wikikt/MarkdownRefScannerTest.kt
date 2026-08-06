package com.wikikt

import com.wikikt.markdown.MarkdownRefScanner
import com.wikikt.markdown.ScannedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The AST-walking URL extractor behind the asset usage/broken scans. Each case here is a way an
 * author can reference an asset that renders fine — so missing it would put a working asset on
 * /f/unused (where an editor may delete it) — or a way of merely *documenting* syntax that must
 * NOT count as a reference.
 */
class MarkdownRefScannerTest {
    private fun urls(content: String, html: Boolean = false): List<ScannedUrl> =
        MarkdownRefScanner.scan(content, html)

    private fun has(content: String, url: String, embed: Boolean): Boolean =
        urls(content).contains(ScannedUrl(url, embed))

    @Test
    fun `inline images are embeds and inline links are links`() {
        assertTrue(has("![x](/images/a.png)", "/images/a.png", embed = true))
        assertTrue(has("[x](/files/manual.pdf)", "/files/manual.pdf", embed = false))
        assertTrue(has("![x](/a.png \"a title\")", "/a.png", embed = true), "title doesn't join the URL")
    }

    @Test
    fun `image nested inside a link yields both URLs`() {
        // The clickable-thumbnail idiom. The old regex scan saw only the thumbnail, so the full-size
        // image showed as unused — exactly the false positive that gets a working asset deleted.
        val content = "[![thumb](/shots/thumb.png)](/shots/full.png)"
        assertTrue(has(content, "/shots/thumb.png", embed = true), "inner image found")
        assertTrue(has(content, "/shots/full.png", embed = false), "outer link target found")
    }

    @Test
    fun `reference-style links and images resolve to their definition's URL`() {
        val content = "![logo][ref] and [the manual][doc]\n\n[ref]: /branding/logo.png\n[doc]: /files/manual.pdf"
        assertTrue(has(content, "/branding/logo.png", embed = true), "reference-style image is an embed")
        assertTrue(has(content, "/files/manual.pdf", embed = false), "reference-style link found")
        // A definition nothing references yet still counts (conservatively, as a link).
        assertTrue(has("[orphan]: /files/orphan.png", "/files/orphan.png", embed = false))
    }

    @Test
    fun `code in every flavor is not a reference`() {
        assertTrue(urls("`![x](/inline.png)`").isEmpty(), "inline code span")
        assertTrue(urls("```\n![x](/fenced.png)\n```").isEmpty(), "backtick fence")
        assertTrue(urls("~~~\n![x](/tilde.png)\n~~~").isEmpty(), "tilde fence")
        assertTrue(urls("para\n\n    ![x](/indented.png)").isEmpty(), "indented code block")
    }

    @Test
    fun `image size suffix parses like the renderer`() {
        // `![x](url =WxH)` is invalid CommonMark; the renderer lifts it into the title pre-parse, and
        // the scan applies the same lift — otherwise every sized image would look unused.
        assertTrue(has("![x](/pics/wide.png =200x100)", "/pics/wide.png", embed = true))
    }

    @Test
    fun `footnote definitions are not link definitions`() {
        val found = urls("text[^1]\n\n[^1]: just a note")
        assertTrue(found.none { it.url.contains("note") }, "footnote text must not become a URL: $found")
    }

    @Test
    fun `raw HTML src and href are scanned, data-src is not`() {
        val content = "<div>\n<img src=\"/html/pic.png\" data-src=\"/html/lazy.png\">\n</div>\n\nand <a href='/html/doc.pdf'>doc</a>"
        assertTrue(has(content, "/html/pic.png", embed = true), "src in an HTML block")
        assertTrue(has(content, "/html/doc.pdf", embed = false), "href in inline HTML")
        assertFalse(urls(content).any { it.url == "/html/lazy.png" }, "data-src is not src")
    }

    @Test
    fun `unquoted HTML attribute values are scanned too`() {
        // Valid HTML that renders; the value ends at whitespace or `>`.
        assertTrue(has("<img src=/html/unquoted.png>", "/html/unquoted.png", embed = true))
        assertTrue(has("<img src=/html/tight.png alt=x>", "/html/tight.png", embed = true), "next attribute doesn't join the URL")
        assertTrue(urls("<img src=\"\">").isEmpty(), "empty value yields no URL")
    }

    @Test
    fun `HTML entities in attribute values decode like the render pipeline`() {
        // jsoup decodes entities when it sanitizes author HTML, so the served src is the decoded
        // path — the scan must see the same identity, not the raw `&eacute;` text.
        assertTrue(has("<img src=\"/html/caf&eacute;.png\">", "/html/café.png", embed = true), "named entity")
        assertTrue(has("<img src=\"/html/my&#45;pic.png\">", "/html/my-pic.png", embed = true), "numeric entity")
        assertTrue(has("<a href=\"/html/a&b.pdf\">x</a>", "/html/a&b.pdf", embed = false), "a bare ampersand stays as-is")
    }

    @Test
    fun `html mode skips the Markdown parse entirely`() {
        // An HTML-format page: Markdown block rules must not apply (an indented img is still real),
        // and Markdown syntax in its text is just text.
        val content = "<div>\n\n    <img src=\"/html/indented.png\">\n</div>\n![m](/md/not-a-ref.png)"
        val found = urls(content, html = true)
        assertEquals(listOf(ScannedUrl("/html/indented.png", true)), found)
    }
}
