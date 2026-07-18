package com.wikikt.markdown

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.HtmlInline
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.PostProcessor

/**
 * Expands `:mdi-icon-name:` shortcodes in Markdown into Material Design Icons font markup, e.g.
 * `:mdi-shield-check-outline:` -> `<i class="mdi mdi-shield-check-outline" aria-hidden="true"></i>`.
 *
 * The required `mdi-` prefix keeps icons distinct from plain `:emoji:` shortcodes (so a future emoji
 * feature can own the prefix-less form without colliding). It only rewrites parsed [Text] nodes, so
 * shortcodes in code spans/blocks are left as-is. Names are constrained to MDI's `[a-z0-9-]` form (so
 * the emitted class is always safe), and the output still passes through [HtmlSanitizer]. An unknown
 * name renders as an empty glyph rather than anything dangerous.
 */
class IconShortcodePostProcessor : PostProcessor {
    override fun process(node: Node): Node {
        node.accept(Visitor())
        return node
    }

    private class Visitor : AbstractVisitor() {
        override fun visit(text: Text) {
            val literal = text.literal
            if (literal.indexOf(':') < 0) return
            val matches = ICON_PATTERN.findAll(literal).toList()
            if (matches.isEmpty()) return

            var cursor = 0
            for (match in matches) {
                val plain = literal.substring(cursor, match.range.first)
                if (plain.isNotEmpty()) text.insertBefore(Text(plain))
                val icon = HtmlInline()
                icon.literal = "<i class=\"mdi mdi-${match.groupValues[1]}\" aria-hidden=\"true\"></i>"
                // (group 1 is the name AFTER the required `mdi-` prefix)
                text.insertBefore(icon)
                cursor = match.range.last + 1
            }
            val tail = literal.substring(cursor)
            if (tail.isNotEmpty()) text.insertBefore(Text(tail))
            text.unlink()
        }
    }

    companion object {
        // Requires the `mdi-` prefix; captures the icon name after it (e.g. ":mdi-home:" -> "home").
        private val ICON_PATTERN = Regex(":mdi-([a-z0-9]+(?:-[a-z0-9]+)*):")
    }
}
