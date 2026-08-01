package com.wikikt.markdown

import com.wikikt.db.ContentFormat
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlRenderer

class MarkdownRenderer {
    // Shared (stateless) extensions used by both the parser and the renderer.
    // Strikethrough requires TWO tildes so a single `~` is free for subscript (see SubSupPostProcessor).
    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.builder().requireTwoTildes(true).build(),
        TaskListItemsExtension.create(),
        FootnotesExtension.create(),
    )

    // Autolink is a parser extension (it post-processes Text into Link nodes); the renderer is
    // unaffected. Both parser variants are prebuilt so the autolink toggle costs nothing per render.
    private val parser = buildParser(withAutolink = false)
    private val autolinkParser = buildParser(withAutolink = true)

    private fun buildParser(withAutolink: Boolean): Parser =
        Parser.builder()
            .extensions(if (withAutolink) extensions + AutolinkExtension.create() else extensions)
            // Record which source line each block came from. Only the editor's live preview renders them
            // out (as data-line, see [SourceLineAttributeProvider]); tracking them always costs one small
            // object per block and keeps a single parser per autolink setting.
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            // First: recover nested emphasis inside link text while the link's children are still
            // the raw parsed Text (before icon/emoji rewrite them).
            .postProcessor(LinkEmphasisPostProcessor(extensions))
            // Icons first (they own the `:mdi-…:` form), then plain `:name:` emoji, then sub/sup.
            .postProcessor(IconShortcodePostProcessor())
            .postProcessor(EmojiPostProcessor())
            .postProcessor(SubSupPostProcessor())
            .build()

    // Renderers differ only in soft-break handling (single newline -> space vs <br>) and whether blocks
    // carry a `data-line` source reference. Escaping raw HTML is deliberately NOT toggled here: our
    // post-processors emit HtmlInline (<sub>, <i class=mdi>) that escapeHtml would break -- the sanitizer
    // is the security boundary for author HTML instead.
    private val renderers = listOf(false, true).flatMap { hb ->
        listOf(false, true).map { sl -> (hb to sl) to buildRenderer(hardBreaks = hb, sourceLines = sl) }
    }.toMap()

    private fun buildRenderer(hardBreaks: Boolean, sourceLines: Boolean): HtmlRenderer =
        HtmlRenderer.builder()
            .extensions(extensions)
            .sanitizeUrls(true)
            // Turns the `wk-img-size:` title left by [liftImageSizes] into <img> width/height attributes.
            .attributeProviderFactory { ImageSizeAttributeProvider }
            .apply {
                if (sourceLines) attributeProviderFactory { SourceLineAttributeProvider }
                if (hardBreaks) softbreak("<br />\n")
            }
            .build()

    /**
     * @param sourceLines stamp each block element with `data-line="<0-based source line>"`. Off for stored
     *   and cached page HTML; the editor's live preview turns it on so the split view can scroll the
     *   preview to the block the caret is in (see the scroll sync in `static/page-edit.js`).
     */
    fun render(
        content: String,
        format: ContentFormat,
        options: RenderOptions = RenderOptions.DEFAULT,
        sourceLines: Boolean = false,
    ): String {
        val rendered = when (format) {
            ContentFormat.MARKDOWN -> {
                val p = if (options.autoLink) autolinkParser else parser
                val r = renderers.getValue(options.lineBreaks to sourceLines)
                r.render(p.parse(liftImageSizes(normalizeHeadings(content))))
            }
            ContentFormat.HTML -> content
        }
        // Always run output through the allowlist sanitizer: CommonMark passes raw inline HTML
        // (including <script>) through untouched, and HTML pages are author-supplied.
        return HtmlSanitizer.sanitize(rendered, options)
    }

    private fun normalizeHeadings(content: String): String =
        content.lines().joinToString("\n") { line ->
            if (line.startsWith("# ¶ ")) {
                line.replaceFirst("# ¶ ", "# ")
            } else {
                line
            }
        }

    companion object {
        // Image sizing: `![alt](url =WIDTHxHEIGHT)` (either dimension optional, e.g.
        // `=350x`, `=x200`, `=350x200`), optionally with a title in front (`![alt](url "title" =WxH)`).
        // The trailing ` =WxH` is invalid CommonMark -- a space isn't allowed in a link destination -- so the
        // whole image renders as literal text if following that strictly. We rewrite it into the image title,
        // appending a `wk-img-size:WxH` marker to any existing title (`![alt](url "title wk-img-size:WxH")`),
        // which parses cleanly; [ImageSizeAttributeProvider] then splits that marker back out into real `width`/`height` attributes
        // on the `<img>` (both allowed by the sanitizer), leaving any author title intact. Ensures compatibiltiy with
        // WKJS-flavored Markdown.
        private val IMAGE_SIZE = Regex("""(!\[[^]]*]\([^()\s]+)(?:\s+"([^"]*)")?\s+=(\d*)x(\d*)\)""")
        // The marker as planted in the title: at the end, optionally preceded by the real title + a space.
        private val SIZE_TITLE = Regex("""(?:^|\s)wk-img-size:(\d*)x(\d*)$""")
        // A fenced-code opening/closing line: up to 3 spaces of indent then a run of >= 3 backticks or tildes.
        private val CODE_FENCE = Regex("""^ {0,3}(`{3,}|~{3,})""")

        private fun sizeReplacement(m: MatchResult): String {
            val head = m.groupValues[1]
            val title = m.groupValues[2] // "" when no title was present
            val w = m.groupValues[3]
            val h = m.groupValues[4]
            // A bare `=x` carries no dimensions; leave the original text untouched.
            if (w.isEmpty() && h.isEmpty()) return m.value
            val marker = "wk-img-size:${w}x${h}"
            val newTitle = if (title.isEmpty()) marker else "$title $marker"
            return "$head \"$newTitle\")"
        }

        /**
         * Rewrites `![alt](url =WxH)` image sizing everywhere EXCEPT inside code, so a literal example of the
         * syntax shown in a fenced block or an inline `code` span is left verbatim. Fenced blocks are tracked
         * line-by-line; inline spans are skipped within each remaining line. (Indented 4-space code blocks are
         * not detected; showing image syntax that way is rare, and misfiring there only mangles an example.)
         */
        private fun liftImageSizes(content: String): String {
            if (!content.contains("=") || !content.contains("![")) return content // cheap skip
            val out = StringBuilder(content.length + 16)
            var fence: String? = null // the active fence run (e.g. "```"), or null when outside a fenced block
            val lines = content.split("\n")
            for ((i, line) in lines.withIndex()) {
                if (i > 0) out.append('\n')
                val active = fence
                val opening = if (active == null) CODE_FENCE.find(line)?.groupValues?.get(1) else null
                when {
                    active != null -> {
                        out.append(line) // inside a fenced block: copy verbatim
                        val bare = line.trim()
                        // Closing fence: same char, at least as long, nothing else on the line.
                        if (bare.length >= active.length && bare.all { it == active[0] }) fence = null
                    }
                    opening != null -> {
                        fence = opening
                        out.append(line) // opening fence line: copy verbatim
                    }
                    else -> out.append(liftImageSizesOutsideCode(line))
                }
            }
            return out.toString()
        }

        /** Applies the image-size rewrite to a single line, skipping inline `code` spans (backtick runs). */
        private fun liftImageSizesOutsideCode(line: String): String {
            if ('`' !in line) return IMAGE_SIZE.replace(line, ::sizeReplacement)
            val out = StringBuilder(line.length)
            var i = 0
            var prose = 0 // start of the current non-code run
            while (i < line.length) {
                if (line[i] == '`') {
                    var j = i
                    while (j < line.length && line[j] == '`') j++
                    val close = indexOfBacktickRun(line, j, j - i) // matching run of the same length
                    if (close >= 0) {
                        if (prose < i) out.append(IMAGE_SIZE.replace(line.substring(prose, i), ::sizeReplacement))
                        out.append(line, i, close + (j - i)) // the code span, verbatim
                        i = close + (j - i)
                        prose = i
                        continue
                    }
                    i = j // unmatched run: treat as literal text, keep scanning
                } else {
                    i++
                }
            }
            if (prose < line.length) out.append(IMAGE_SIZE.replace(line.substring(prose), ::sizeReplacement))
            return out.toString()
        }

        /** Index of the next run of exactly [run] backticks at/after [from], or -1 (CommonMark code-span close). */
        private fun indexOfBacktickRun(s: String, from: Int, run: Int): Int {
            var i = from
            while (i < s.length) {
                if (s[i] == '`') {
                    var j = i
                    while (j < s.length && s[j] == '`') j++
                    if (j - i == run) return i
                    i = j
                } else {
                    i++
                }
            }
            return -1
        }
    }

    /**
     * Stamps `data-line="<0-based source line>"` on each rendered block, from the source spans the parser
     * records. Only enabled for the editor's live preview: the split view reads these back as scroll
     * anchors so the preview follows the line being edited instead of scrolling proportionally.
     *
     * Restricted to block-level tags so the pairs the renderer emits for one node (`<pre><code>` for a
     * fenced block) are stamped once, on the outer element. Stateless, so a single instance is reused.
     */
    private object SourceLineAttributeProvider : AttributeProvider {
        private val BLOCK_TAGS = setOf(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "li",
            "pre", "table", "thead", "tbody", "tr", "hr", "div", "section", "dl",
        )

        override fun setAttributes(node: Node, tagName: String, attributes: MutableMap<String, String>) {
            if (tagName !in BLOCK_TAGS) return
            val line = node.sourceSpans.firstOrNull()?.lineIndex ?: return
            attributes["data-line"] = line.toString()
        }
    }

    /** Splits the `wk-img-size:WxH` marker (planted by [liftImageSizes]) out of the `<img>` title into
     *  real width/height attributes, restoring any author title that preceded it (or dropping the title
     *  entirely when the marker was the whole thing). Stateless, so a single instance is reused. */
    private object ImageSizeAttributeProvider : AttributeProvider {
        override fun setAttributes(node: Node, tagName: String, attributes: MutableMap<String, String>) {
            if (tagName != "img") return
            val title = attributes["title"] ?: return
            val match = SIZE_TITLE.find(title) ?: return
            // Everything before the (whitespace-prefixed) marker is the author's real title, if any.
            val rest = title.substring(0, match.range.first)
            if (rest.isEmpty()) attributes.remove("title") else attributes["title"] = rest
            match.groupValues[1].takeIf { it.isNotEmpty() }?.let { attributes["width"] = it }
            match.groupValues[2].takeIf { it.isNotEmpty() }?.let { attributes["height"] = it }
        }
    }
}
