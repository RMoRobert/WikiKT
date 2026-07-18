package com.wikikt.markdown

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.HtmlInline
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.PostProcessor

/**
 * Expands subscript `~x~` and superscript `^x^` in parsed [Text] nodes into `<sub>`/`<sup>`.
 * Only operates on Text nodes, so shortcodes in code spans/blocks are left alone. The inner token is
 * emitted as a (escaped) Text node, and the tags pass through [HtmlSanitizer] (which allows sub/sup).
 *
 * Strikethrough is configured to require two tildes (see MarkdownRenderer), so a single `~` reaches
 * here as literal text; `~~strike~~` stays strikethrough. The token may not contain whitespace or the
 * delimiter character(e.g. `H~2~O`, `19^th^`).
 */
class SubSupPostProcessor : PostProcessor {
    override fun process(node: Node): Node {
        node.accept(Visitor())
        return node
    }

    private class Visitor : AbstractVisitor() {
        override fun visit(text: Text) {
            val literal = text.literal
            if (literal.indexOf('~') < 0 && literal.indexOf('^') < 0) return
            val matches = PATTERN.findAll(literal).toList()
            if (matches.isEmpty()) return

            var cursor = 0
            for (match in matches) {
                val plain = literal.substring(cursor, match.range.first)
                if (plain.isNotEmpty()) text.insertBefore(Text(plain))
                val sub = match.groupValues[1].isNotEmpty()
                val token = if (sub) match.groupValues[1] else match.groupValues[2]
                val tag = if (sub) "sub" else "sup"
                val open = HtmlInline(); open.literal = "<$tag>"
                text.insertBefore(open)
                text.insertBefore(Text(token))
                val close = HtmlInline(); close.literal = "</$tag>"
                text.insertBefore(close)
                cursor = match.range.last + 1
            }
            val tail = literal.substring(cursor)
            if (tail.isNotEmpty()) text.insertBefore(Text(tail))
            text.unlink()
        }
    }

    companion object {
        // ~token~ (subscript, group 1) or ^token^ (superscript, group 2). No whitespace/delimiter inside.
        private val PATTERN = Regex("~([^~\\s]+)~|\\^([^\\^\\s]+)\\^")
    }
}
