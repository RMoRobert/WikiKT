package com.wikikt.markdown

import org.commonmark.Extension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Emphasis
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor

/**
 * Emphasis parsing inside link text.
 *
 * The CommonMark spec — and commonmark-java, and the reference implementation with it — leaves a
 * construct like `[Title *desc *phrase!**](/x)` as literal asterisks: the trailing `**` sits against
 * the closing `]` and, being preceded by punctuation (`!`), counts as *able to open*, so the spec's
 * "rule of three" refuses to pair it with the odd-length openers and the whole run stays literal.
 * To support nested emphasis inside links (as in the `{.links-list}` "title *description*" convention
 * where the description is a trailing `<em>` and an italic phrase inside it is a nested `<em>`), we
 * re-parse link text to recover that nesting when the initial parse left it as literal delimiters.
 *
 * The exact same text renders correctly OUTSIDE a link (there is no `]` boundary to flip the flanking),
 * so we recover markdown-it's result by re-parsing a link's text on its own — but only when the in-link
 * parse actually failed, i.e. every child is still a plain [Text] node and one of them carries a raw
 * `*`/`_` delimiter, AND parsing that text standalone resolves it into emphasis. That failure signature
 * never matches a spec-conformant link (which already holds [Emphasis] nodes, or no delimiters at all),
 * so nothing else changes.
 *
 * Registered before the icon/emoji post-processors so it sees the link's original text (`:book:` etc.
 * still literal); those expansions then run over the spliced-in nodes as usual.
 */
class LinkEmphasisPostProcessor(extensions: List<Extension>) : PostProcessor {
    // Minimal parser: same inline extensions, deliberately NO post-processors (so re-parsing can't
    // recurse back into this one — and link text can't contain another link to re-trigger it anyway).
    private val inlineParser: Parser = Parser.builder().extensions(extensions).build()

    override fun process(node: Node): Node {
        node.accept(Visitor(inlineParser))
        return node
    }

    private class Visitor(private val parser: Parser) : AbstractVisitor() {
        override fun visit(link: Link) {
            // Only the "emphasis parsing gave up" shape: all children plain Text, at least one holding a
            // delimiter. A link that already parsed emphasis has an Emphasis child and bails immediately.
            val sb = StringBuilder()
            var hasDelimiter = false
            var child = link.firstChild
            while (child != null) {
                if (child !is Text) return
                if ('*' in child.literal || '_' in child.literal) hasDelimiter = true
                sb.append(child.literal)
                child = child.next
            }
            if (!hasDelimiter) return

            // Re-parse the reconstructed text as a standalone block. Link text is single-line, so a
            // single paragraph is expected; anything else means it isn't the case we target.
            val doc = parser.parse(sb.toString())
            val para = doc.firstChild as? Paragraph ?: return
            if (para.next != null) return
            if (!containsEmphasis(para)) return // standalone parse also left it literal -> no improvement

            // Swap the link's literal children for the correctly-nested inline nodes.
            while (link.firstChild != null) link.firstChild.unlink()
            var moved = para.firstChild
            while (moved != null) {
                val next = moved.next
                link.appendChild(moved)
                moved = next
            }
        }

        private fun containsEmphasis(node: Node): Boolean {
            var found = false
            node.accept(object : AbstractVisitor() {
                override fun visit(emphasis: Emphasis) { found = true }
                override fun visit(strong: StrongEmphasis) { found = true }
            })
            return found
        }
    }
}
