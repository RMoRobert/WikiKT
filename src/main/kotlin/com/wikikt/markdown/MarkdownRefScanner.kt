package com.wikikt.markdown

import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.parser.Parser

/** One URL found in content, and whether it was written as an embed (`![]`, `src=`) or a plain link. */
data class ScannedUrl(val url: String, val embed: Boolean)

/**
 * Extracts every link/image URL from content by walking the same CommonMark AST the renderer builds,
 * rather than regexing the source. That inherits the parser's exact judgment on what actually renders:
 * nested constructs (`[![thumb](/t.png)](/full.png)`), reference-style links (`![x][ref]` plus
 * `[ref]: /url` — the resolved Image/Link node carries the definition's destination), and code of every
 * flavor (backtick and tilde fences, inline spans, indented blocks) — example syntax inside code simply
 * never becomes a Link/Image node, so no separate masking pass is needed.
 *
 * Raw-HTML nodes are still regex-scanned for src/href, since CommonMark passes their text through
 * unparsed. HTML-format pages skip the Markdown parse entirely ([scan] with `html = true`) — Markdown
 * block rules (e.g. an indented line becoming a code block) must not hide their references.
 */
object MarkdownRefScanner {
    // Link parsing must match MarkdownRenderer's extensions. Footnotes matter most: without the
    // extension, `[^note]: text` would parse as a link-reference definition and yield a phantom URL.
    // The renderer's post-processors and autolink are omitted — neither changes a destination
    // (autolink only ever creates scheme'd external links, which the reference scans discard).
    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.builder().requireTwoTildes(true).build(),
                TaskListItemsExtension.create(),
                FootnotesExtension.create(),
            ),
        )
        .build()

    // src/href attribute values inside raw HTML: double-quoted, single-quoted, or unquoted
    // (`<img src=/x.png>` is valid HTML and renders — the unquoted class stops at the characters
    // HTML forbids in unquoted values). The lookbehind keeps `data-src=` and friends from matching
    // as `src=`.
    private val HTML_URL = Regex(
        "(?<![\\w-])(src|href)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))",
        RegexOption.IGNORE_CASE,
    )

    /** Every URL in [content], in source order. Duplicates are kept; callers collect into sets. */
    fun scan(content: String, html: Boolean = false): List<ScannedUrl> {
        val out = mutableListOf<ScannedUrl>()
        if (html) {
            scanHtml(content, out)
            return out
        }
        // The same pre-parse rewrite the renderer applies, so WikiJS-style image sizing
        // (`![x](/a.png =200x100)` — invalid CommonMark as written) parses as an image here too.
        val doc = parser.parse(MarkdownRenderer.liftImageSizes(content))
        doc.accept(object : AbstractVisitor() {
            override fun visit(image: Image) {
                out.add(ScannedUrl(image.destination, embed = true))
                visitChildren(image)
            }

            override fun visit(link: Link) {
                out.add(ScannedUrl(link.destination, embed = false))
                visitChildren(link) // a link's text can hold an image: [![t](/t.png)](/full.png)
            }

            override fun visit(def: LinkReferenceDefinition) {
                // A definition may back an embed or a link; LINK is the conservative reading (an
                // extension-less target then counts as a page link, not a missing asset). Definitions
                // that ARE used also surface via their resolved Image/Link nodes with the right kind.
                out.add(ScannedUrl(def.destination, embed = false))
            }

            override fun visit(htmlBlock: HtmlBlock) = scanHtml(htmlBlock.literal, out)

            override fun visit(htmlInline: HtmlInline) = scanHtml(htmlInline.literal, out)
        })
        return out
    }

    private fun scanHtml(literal: String?, out: MutableList<ScannedUrl>) {
        for (m in HTML_URL.findAll(literal ?: return)) {
            // Exactly one of the three value groups matched; an empty quoted value ("") yields no URL.
            val raw = m.groupValues[2].ifEmpty { m.groupValues[3] }.ifEmpty { m.groupValues[4] }
            if (raw.isEmpty()) continue
            // Decode HTML entities the way the render pipeline does (jsoup parses and re-serializes
            // author HTML, and the browser decodes what's left) — `src="/caf&eacute;.png"` serves the
            // decoded path, so it must scan as it. CommonMark destinations arrive already decoded.
            val url = org.jsoup.parser.Parser.unescapeEntities(raw, true)
            out.add(ScannedUrl(url, embed = m.groupValues[1].equals("src", ignoreCase = true)))
        }
    }
}
