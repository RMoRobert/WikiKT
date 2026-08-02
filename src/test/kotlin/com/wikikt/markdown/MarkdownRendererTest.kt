package com.wikikt.markdown

import com.wikikt.db.ContentFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownRendererTest {
    private val renderer = MarkdownRenderer()

    @Test
    fun `markdown strips inline script tags`() {
        val html = renderer.render("Hello <script>alert('xss')</script> world", ContentFormat.MARKDOWN)
        assertFalse(html.contains("<script"), "script tag should be removed: $html")
        assertTrue(html.contains("Hello"))
    }

    @Test
    fun `html content is sanitized`() {
        val html = renderer.render("<p onclick=\"steal()\">hi</p><script>x()</script>", ContentFormat.HTML)
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("onclick"))
        assertTrue(html.contains("hi"))
    }

    @Test
    fun `javascript urls are dropped`() {
        val html = renderer.render("[click](javascript:alert(1))", ContentFormat.MARKDOWN)
        assertFalse(html.contains("javascript:"), "javascript: URL should be removed: $html")
    }

    @Test
    fun `mdi-prefixed icon shortcode becomes mdi markup`() {
        val html = renderer.render(":mdi-shield-check-outline: Secure", ContentFormat.MARKDOWN)
        assertTrue(
            html.contains("<i class=\"mdi mdi-shield-check-outline\""),
            "expected MDI icon markup: $html",
        )
        assertTrue(html.contains("Secure"))
    }

    @Test
    fun `bare shortcode is not turned into an MDI icon (mdi- prefix required)`() {
        // Not an emoji and no mdi- prefix -> left as literal text (icons require the prefix).
        val html = renderer.render("Text :shield-check-outline: here", ContentFormat.MARKDOWN)
        assertFalse(html.contains("mdi-shield-check-outline"), "no MDI icon without mdi- prefix: $html")
        assertTrue(html.contains(":shield-check-outline:"), "left as literal text: $html")
    }

    @Test
    fun `icon shortcode inside code span is left untouched`() {
        val html = renderer.render("Literal `:mdi-home:` text", ContentFormat.MARKDOWN)
        assertFalse(html.contains("mdi-home\""), "code span should not be converted: $html")
        assertTrue(html.contains(":mdi-home:"))
    }

    @Test
    fun `task lists render checkboxes`() {
        val html = renderer.render("- [ ] todo\n- [x] done", ContentFormat.MARKDOWN)
        assertTrue(html.contains("<input type=\"checkbox\" disabled>"), "unchecked box: $html")
        assertTrue(html.contains("checked"), "checked box: $html")
    }

    @Test
    fun `footnotes render with working jump anchors`() {
        val html = renderer.render("Text.[^1]\n\n[^1]: Note.", ContentFormat.MARKDOWN)
        assertTrue(html.contains("href=\"#fn-1\""), "ref links to definition: $html")
        assertTrue(html.contains("id=\"fn-1\""), "definition keeps its id: $html")
        assertTrue(html.contains("class=\"footnotes\""), "footnotes section rendered: $html")
    }

    @Test
    fun `subscript and superscript render, double tilde stays strikethrough`() {
        val html = renderer.render("H~2~O and 19^th^ and ~~gone~~", ContentFormat.MARKDOWN)
        assertTrue(html.contains("H<sub>2</sub>O"), "subscript: $html")
        assertTrue(html.contains("19<sup>th</sup>"), "superscript: $html")
        assertTrue(html.contains("<del>gone</del>"), "double tilde still strikethrough: $html")
    }

    @Test
    fun `keyboard keys survive sanitizing`() {
        val html = renderer.render("Press <kbd>Ctrl</kbd>+<kbd>C</kbd>", ContentFormat.MARKDOWN)
        assertTrue(html.contains("<kbd>Ctrl</kbd>"), "kbd preserved: $html")
    }

    @Test
    fun `emoji shortcodes expand to unicode`() {
        val html = renderer.render("Ship it :rocket: :+1: :100:", ContentFormat.MARKDOWN)
        assertTrue(html.contains("🚀"), "rocket emoji: $html")   // 🚀
        assertTrue(html.contains("👍"), "+1 emoji: $html")        // 👍
        assertTrue(html.contains("💯"), "100 emoji: $html")       // 💯
        assertFalse(html.contains(":rocket:"), "shortcode replaced: $html")
    }

    @Test
    fun `unknown emoji shortcode is left as text`() {
        val html = renderer.render("Not an emoji :definitely-not-real:", ContentFormat.MARKDOWN)
        assertTrue(html.contains(":definitely-not-real:"), "unknown shortcode kept: $html")
    }

    @Test
    fun `emoji shortcode inside code span is left untouched`() {
        val html = renderer.render("Code `:rocket:` here", ContentFormat.MARKDOWN)
        assertTrue(html.contains(":rocket:"), "code span shortcode kept literal: $html")
    }

    @Test
    fun `tabset heading becomes a bootstrap tab group`() {
        val md = "## Pick {.tabset}\n\n### One\nfirst\n\n### Two\nsecond"
        val html = renderer.render(md, ContentFormat.MARKDOWN)
        assertTrue(html.contains("class=\"wk-tabset\""), "tabset wrapper: $html")
        assertTrue(html.contains("data-bs-toggle=\"tab\""), "tab toggles: $html")
        assertTrue(html.contains(">One<") && html.contains(">Two<"), "tab labels: $html")
        assertTrue(html.contains("first") && html.contains("second"), "pane content: $html")
        assertFalse(html.contains("{.tabset}"), "marker hidden: $html")
        assertFalse(html.contains("Pick"), "parent heading text hidden: $html")
    }

    @Test
    fun `links-list decorate marks the list and strips the marker`() {
        val md = "- [A *first*](/a)\n- [B *second*](/b)\n{.links-list}"
        val html = renderer.render(md, ContentFormat.MARKDOWN)
        assertTrue(html.contains("<ul class=\"links-list\">"), "class applied to list: $html")
        assertFalse(html.contains("{.links-list}"), "marker text stripped: $html")
        assertTrue(html.contains("href=\"/b\""), "links preserved: $html")
        assertTrue(html.contains("<em>second</em>"), "descriptions preserved: $html")
    }

    @Test
    fun `emphasis inside link text nests like markdown-it even against the closing bracket`() {
        // The trailing `**` abuts `]` and follows punctuation (`!`), which
        // the CommonMark spec leaves as literal asterisks; markdown-it nests it. LinkEmphasisPostProcessor
        // recovers the nested form. (This is the exact {.links-list} description case from the field.)
        val md = "- [:book: Getting Started *Initial setup, and more. *New users, start here!**](/x)\n{.links-list}"
        val html = renderer.render(md, ContentFormat.MARKDOWN)
        assertFalse(html.contains("start here!**"), "asterisks should not render literally: $html")
        assertTrue(
            html.contains("<em>Initial setup, and more. <em>New users, start here!</em></em>"),
            "description nests emphasis like markdown-it: $html",
        )
    }

    @Test
    fun `link text without failed emphasis is left exactly as the spec parses it`() {
        // Guardrails: the recovery must fire ONLY on the give-up shape, never on ordinary links.
        assertTrue(
            renderer.render("[a *b* c](/x)", ContentFormat.MARKDOWN).contains("a <em>b</em> c"),
            "normal emphasis untouched",
        )
        // Literal asterisks that the spec keeps literal (spaced, non-flanking) stay literal.
        assertTrue(
            renderer.render("[2 * 3 and 4 * 2](/x)", ContentFormat.MARKDOWN).contains("2 * 3 and 4 * 2"),
            "arithmetic asterisks untouched",
        )
        assertTrue(
            renderer.render("[rating *****](/x)", ContentFormat.MARKDOWN).contains("rating *****"),
            "bare star run untouched",
        )
    }

    @Test
    fun `links-list works as raw HTML too`() {
        val raw = "<ul class=\"links-list\">\n<li><a href=\"/x\">Title <em>desc</em></a></li>\n</ul>"
        // As an HTML block inside Markdown:
        val fromMd = renderer.render(raw, ContentFormat.MARKDOWN)
        assertTrue(fromMd.contains("<ul class=\"links-list\">"), "class kept (md html-block): $fromMd")
        assertTrue(fromMd.contains("href=\"/x\""), "link kept: $fromMd")
        // And on an HTML-format page:
        val fromHtml = renderer.render(raw, ContentFormat.HTML)
        assertTrue(fromHtml.contains("<ul class=\"links-list\">"), "class kept (html page): $fromHtml")
    }

    @Test
    fun `details and summary disclosure survives sanitizing`() {
        val html = renderer.render(
            "<details><summary>More</summary>\n\nHidden content.\n\n</details>",
            ContentFormat.MARKDOWN,
        )
        assertTrue(html.contains("<details"), "details kept: $html")
        assertTrue(html.contains("<summary>More</summary>"), "summary kept: $html")
        assertTrue(html.contains("Hidden content."), "inner content kept: $html")
    }

    private val allowIframes = RenderOptions(allowIframes = true)

    @Test
    fun `iframes are stripped by default (disabled)`() {
        val html = renderer.render(
            "<iframe src=\"https://www.youtube.com/embed/x\" allowfullscreen></iframe>",
            ContentFormat.MARKDOWN,
        )
        assertFalse(html.contains("<iframe"), "iframe removed when disabled: $html")
    }

    @Test
    fun `https iframe is allowed, sandboxed, and lazy when enabled`() {
        val html = renderer.render(
            "<iframe src=\"https://www.youtube.com/embed/x\" allowfullscreen></iframe>",
            ContentFormat.MARKDOWN,
            allowIframes,
        )
        assertTrue(html.contains("<iframe"), "iframe kept: $html")
        assertTrue(html.contains("src=\"https://www.youtube.com/embed/x\""), "https src kept: $html")
        assertTrue(html.contains("sandbox="), "sandbox added: $html")
        assertTrue(html.contains("loading=\"lazy\""), "lazy loading added: $html")
    }

    @Test
    fun `unsafe iframes are neutralized even when iframes are enabled`() {
        val jsHtml = renderer.render("<iframe src=\"javascript:alert(1)\"></iframe>", ContentFormat.MARKDOWN, allowIframes)
        assertFalse(jsHtml.contains("javascript:"), "javascript: src dropped: $jsHtml")
        assertFalse(jsHtml.contains("<iframe"), "src-less iframe removed: $jsHtml")

        val srcdocHtml = renderer.render(
            "<iframe srcdoc=\"<script>alert(1)</script>\" src=\"https://ok.test/\"></iframe>",
            ContentFormat.MARKDOWN,
            allowIframes,
        )
        assertFalse(srcdocHtml.contains("srcdoc"), "srcdoc dropped: $srcdocHtml")
        assertFalse(srcdocHtml.contains("<script"), "no inline script: $srcdocHtml")
    }

    @Test
    fun `style attribute is stripped by default but kept when allowed`() {
        val md = "<p style=\"color:red\">hi</p>"
        assertFalse(renderer.render(md, ContentFormat.MARKDOWN).contains("style="), "style stripped by default")
        val allowed = renderer.render(md, ContentFormat.MARKDOWN, RenderOptions(allowStyleAttr = true))
        assertTrue(allowed.contains("style=\"color:red\""), "style kept when allowed: $allowed")
    }

    @Test
    fun `autolink converts bare urls only when enabled`() {
        val md = "see https://example.com/x now"
        // Default options have autolink on.
        assertTrue(
            renderer.render(md, ContentFormat.MARKDOWN).contains("<a href=\"https://example.com/x\""),
            "bare url linked by default",
        )
        val off = renderer.render(md, ContentFormat.MARKDOWN, RenderOptions(autoLink = false))
        assertFalse(off.contains("<a href"), "bare url not linked when off: $off")
    }

    @Test
    fun `line breaks toggle turns single newlines into br`() {
        val md = "line one\nline two"
        assertFalse(renderer.render(md, ContentFormat.MARKDOWN).contains("<br"), "no br by default")
        val br = renderer.render(md, ContentFormat.MARKDOWN, RenderOptions(lineBreaks = true))
        assertTrue(br.contains("<br"), "single newline becomes br when enabled: $br")
    }

    @Test
    fun `callout marker after a blockquote becomes a class`() {
        val html = renderer.render("> Be careful here\n{.is-warning}", ContentFormat.MARKDOWN)
        assertTrue(html.contains("class=\"is-warning\""), "blockquote should get is-warning: $html")
        assertTrue(html.contains("mdi-alert-outline"), "warning box should get its icon: $html")
        assertFalse(html.contains("{.is-warning}"), "marker text should be stripped: $html")
        assertTrue(html.contains("Be careful here"))
    }

    @Test
    fun `standalone callout marker applies to the preceding block`() {
        val html = renderer.render("Heads up about this.\n\n{.is-info}", ContentFormat.MARKDOWN)
        assertTrue(html.contains("class=\"is-info\""), "preceding paragraph should get is-info: $html")
        assertFalse(html.contains("{.is-info}"))
    }

    @Test
    fun `unknown callout classes are ignored`() {
        val html = renderer.render("> Sneaky\n{.site-header .evil}", ContentFormat.MARKDOWN)
        assertFalse(html.contains("evil"), "arbitrary classes must not be applied: $html")
        assertFalse(html.contains("site-header"))
        assertFalse(html.contains("{."), "marker should still be stripped: $html")
    }

    @Test
    fun `safe markdown formatting is preserved`() {
        val html = renderer.render("# Title\n\n**bold** and a [link](https://example.com)", ContentFormat.MARKDOWN)
        // Headings are demoted one level on render so the page title <h1> stays the only h1: `#` → <h2>.
        assertTrue(html.contains("<h2>"))
        assertFalse(html.contains("<h1>"), "content headings are demoted, leaving the title as the sole h1")
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("href=\"https://example.com\""))
    }

    @Test
    fun `relative links to other wiki pages keep their href`() {
        val html = renderer.render("* [File One](/dir1/dir2/file1)", ContentFormat.MARKDOWN)
        // The href must survive sanitizing (relative links were previously stripped to <a>File One</a>)
        // and stay relative — not rewritten to an absolute URL against the protocol-check base URI.
        assertTrue(
            html.contains("href=\"/dir1/dir2/file1\""),
            "relative wiki link should be preserved: $html",
        )
    }

    @Test
    fun `image sizing sets width and height, not literal text`() {
        // Width only (the dominant form in the imported content, e.g. `=350x`, `=900x`).
        val w = renderer.render("![setup](/dir1/dir2/dir3/image1.png =900x)", ContentFormat.MARKDOWN)
        assertTrue(w.contains("<img"), "must render an <img>, not literal markdown: $w")
        assertFalse(w.contains("=900x"), "the size marker must be consumed, not shown: $w")
        assertTrue(w.contains("src=\"/dir1/dir2/dir3/image1.png\""), w)
        assertTrue(w.contains("width=\"900\""), "width attribute applied: $w")
        assertFalse(w.contains("height="), "no height when the H side is omitted: $w")
        assertFalse(w.contains("title="), "the size marker title is dropped: $w")

        // Height only, and both dimensions.
        val h = renderer.render("![x](/a.png =x200)", ContentFormat.MARKDOWN)
        assertTrue(h.contains("height=\"200\"") && !h.contains("width="), "height only: $h")
        val wh = renderer.render("![x](/a.png =350x200)", ContentFormat.MARKDOWN)
        assertTrue(wh.contains("width=\"350\"") && wh.contains("height=\"200\""), "both dimensions: $wh")
    }

    @Test
    fun `image sizing is not applied inside code`() {
        // Inside a fenced code block, the syntax must be shown verbatim, not turned into an <img>.
        val fenced = renderer.render("```\n![x](/a.png =350x)\n```", ContentFormat.MARKDOWN)
        assertTrue(fenced.contains("<code"), "still a code block: $fenced")
        assertTrue(fenced.contains("=350x"), "literal syntax preserved in code: $fenced")
        assertFalse(fenced.contains("<img"), "no image produced inside code: $fenced")
        assertFalse(fenced.contains("wk-img-size"), "no leaked marker: $fenced")

        // Inside an inline code span, likewise verbatim.
        val inline = renderer.render("Write `![x](/a.png =350x)` to size it.", ContentFormat.MARKDOWN)
        assertTrue(inline.contains("<code>![x](/a.png =350x)</code>"), "inline code preserved: $inline")
        assertFalse(inline.contains("<img"), inline)

        // A real sized image on the SAME line as an unrelated code span still gets sized.
        val mixed = renderer.render("`code` then ![x](/a.png =350x)", ContentFormat.MARKDOWN)
        assertTrue(mixed.contains("<code>code</code>"), mixed)
        assertTrue(mixed.contains("width=\"350\""), "the real image outside the span is still sized: $mixed")
    }

    @Test
    fun `image sizing and a title can be combined`() {
        // A title AND a size on the same image: both must survive (regression — used to fail together).
        val both = renderer.render(
            "![Screenshot of dir1 (Main View)](/dir1/dir2/image1.png \"Image One\" =900x)",
            ContentFormat.MARKDOWN,
        )
        assertTrue(both.contains("<img"), "must render an <img>, not literal markdown: $both")
        assertTrue(both.contains("src=\"/dir1/dir2/image1.png\""), both)
        assertTrue(both.contains("width=\"900\""), "width attribute applied: $both")
        assertFalse(both.contains("height="), "no height when the H side is omitted: $both")
        assertTrue(both.contains("title=\"Image One\""), "the author title is preserved: $both")
        assertFalse(both.contains("wk-img-size"), "the size marker is consumed, not leaked into the title: $both")
        assertFalse(both.contains("=900x"), "the size marker must be consumed, not shown: $both")
        assertTrue(both.contains("alt=\"Screenshot of dir1 (Main View)\""), "alt with parens kept: $both")

        // Both dimensions plus a title.
        val wh = renderer.render("![x](/a.png \"Cap\" =350x200)", ContentFormat.MARKDOWN)
        assertTrue(wh.contains("width=\"350\"") && wh.contains("height=\"200\""), "both dimensions: $wh")
        assertTrue(wh.contains("title=\"Cap\""), "title kept alongside both dimensions: $wh")
    }

    @Test
    fun `plain images and non-size titles are untouched by image sizing`() {
        val plain = renderer.render("![x](/a.png)", ContentFormat.MARKDOWN)
        assertTrue(plain.contains("src=\"/a.png\"") && !plain.contains("width="), "plain image unaffected: $plain")
        // A genuine title stays a title; only the wk-img-size marker is special.
        val titled = renderer.render("![x](/a.png \"A caption\")", ContentFormat.MARKDOWN)
        assertTrue(titled.contains("title=\"A caption\""), "real titles are preserved: $titled")
    }

    // --- External-link icon ---------------------------------------------------------------------

    private val siteMode = RenderOptions(externalLinkMode = ExternalLinkMode.SITE)

    @Test
    fun `off mode adds no external link marker`() {
        // The code baseline (RenderOptions.DEFAULT) is OFF; the product default for a live site is SITE
        // (SettingsService.DEFAULT_EXTERNAL_LINK_ICON), applied via SettingsService.renderOptions.
        val html = renderer.render("[ext](https://example.com)", ContentFormat.MARKDOWN,
            RenderOptions(externalLinkMode = ExternalLinkMode.OFF))
        assertFalse(html.contains("wk-external-link"), "no marker when mode is off: $html")
        assertFalse(html.contains("mdi-open-in-new"), html)
    }

    @Test
    fun `site mode marks absolute links but not relative, anchor, or mailto`() {
        val md = """
            [ext](https://example.com)
            [rel](/docs/page)
            [anchor](#section)
            [mail](mailto:a@b.test)
        """.trimIndent()
        val html = renderer.render(md, ContentFormat.MARKDOWN, siteMode)
        // The external link is decorated once, with the class on the <a> and the icon inside it.
        assertTrue(html.contains("href=\"https://example.com\""), html)
        assertTrue(html.contains("wk-external-link"), "class added to the external anchor: $html")
        assertTrue(html.contains("mdi-open-in-new"), "icon appended to the external link: $html")
        assertEquals(1, "wk-external-link-icon".toRegex().findAll(html).count(), "only the external link is marked: $html")
        // Internal / non-web links are left alone (kept, but not decorated).
        assertTrue(html.contains("href=\"/docs/page\""), "relative link preserved: $html")
        assertTrue(html.contains("href=\"mailto:a@b.test\""), "mailto link preserved: $html")
    }

    @Test
    fun `instance mode treats configured internal hosts as internal`() {
        val opts = RenderOptions(
            externalLinkMode = ExternalLinkMode.INSTANCE,
            internalHosts = setOf("wiki.example.com", "docs.example.com"),
        )
        val md = "[a](https://docs.example.com/x) [b](https://outside.test/y)"
        val html = renderer.render(md, ContentFormat.MARKDOWN, opts)
        // The sibling instance host is internal (no icon); the truly-external host is marked.
        assertFalse(html.substringBefore("outside.test").contains("wk-external-link"), "instance host not marked: $html")
        assertTrue(html.contains("href=\"https://outside.test/y\""), "off-instance link kept: $html")
        assertTrue(html.contains("wk-external-link"), "off-instance link marked: $html")
        assertEquals(1, "wk-external-link-icon".toRegex().findAll(html).count(), html)
    }

    @Test
    fun `image-only links are not decorated`() {
        val html = renderer.render("[![logo](/logo.png)](https://example.com)", ContentFormat.MARKDOWN, siteMode)
        assertFalse(html.contains("mdi-open-in-new"), "an image link should not get a trailing icon: $html")
    }

    // `data-line` anchors the editor's split-view scroll sync (static/page-edit.js) to the source. It is
    // opt-in per render: page bodies are cached and stored, so they must not carry editor-only markup.
    @Test
    fun `source line anchors are off by default`() {
        val html = renderer.render("# Title\n\nBody text.\n", ContentFormat.MARKDOWN)
        assertFalse(html.contains("data-line"), "stored/cached page HTML carries no line anchors: $html")
    }

    @Test
    fun `source line anchors label blocks with their 0-based source line`() {
        val md = "# Title\n\nBody text.\n\n- one\n- two\n\n```\ncode\n```\n"
        val html = renderer.render(md, ContentFormat.MARKDOWN, sourceLines = true)
        assertTrue(html.contains("<h2 data-line=\"0\">"), "heading anchored: $html")
        assertTrue(html.contains("<p data-line=\"2\">"), "paragraph anchored: $html")
        assertTrue(html.contains("<ul data-line=\"4\">"), "list anchored: $html")
        assertTrue(html.contains("<li data-line=\"4\">"), "list items anchored individually: $html")
        assertTrue(html.contains("<li data-line=\"5\">"), "list items anchored individually: $html")
        assertTrue(html.contains("<pre data-line=\"7\">"), "fenced block anchored: $html")
        // Only the outer element of a multi-tag block is stamped, so the anchor list stays one per block.
        assertFalse(html.contains("<code data-line"), "the inner <code> is not stamped too: $html")
    }
}
