package com.wikikt.markdown

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.PostProcessor

/**
 * Expands `:name:` emoji shortcodes (e.g. `:smile:` -> 😄) using the markdown-it-emoji name->unicode
 * map (MIT-licensed; the JS library isn't needed — only its data, bundled at `/emoji.json`). Only
 * *known* names are replaced; an unknown `:x:` is left as literal text. MDI icons use the `mdi-` prefix
 * ([IconShortcodePostProcessor]) and run first, so there's no collision. Operates on [Text] nodes only,
 * so shortcodes in code spans/blocks are untouched, and the output is plain text (no sanitizer impact).
 */
class EmojiPostProcessor : PostProcessor {
    override fun process(node: Node): Node {
        node.accept(Visitor())
        return node
    }

    private class Visitor : AbstractVisitor() {
        override fun visit(text: Text) {
            val literal = text.literal
            if (literal.indexOf(':') < 0) return
            val matches = PATTERN.findAll(literal).filter { EMOJI.containsKey(it.groupValues[1]) }.toList()
            if (matches.isEmpty()) return

            var cursor = 0
            for (match in matches) {
                val plain = literal.substring(cursor, match.range.first)
                if (plain.isNotEmpty()) text.insertBefore(Text(plain))
                text.insertBefore(Text(EMOJI.getValue(match.groupValues[1])))
                cursor = match.range.last + 1
            }
            val tail = literal.substring(cursor)
            if (tail.isNotEmpty()) text.insertBefore(Text(tail))
            text.unlink()
        }
    }

    companion object {
        // Shortcode characters per markdown-it-emoji: lowercase letters, digits, _ + - (e.g. :+1:, :100:).
        private val PATTERN = Regex(":([a-z0-9_+-]+):")

        /** name -> emoji, loaded once from the bundled markdown-it-emoji data (full set, ~1900 entries). */
        private val EMOJI: Map<String, String> = run {
            val stream = EmojiPostProcessor::class.java.getResourceAsStream("/emoji.json")
            if (stream == null) {
                emptyMap()
            } else {
                val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), text)
            }
        }
    }
}
