package com.wikikt.service

/**
 * Masks Markdown code spans and fenced blocks behind placeholder tokens so reference scanners
 * (fragment keys) don't match references that are only being *documented* in code. Used by
 * FragmentService; the asset scans instead walk the CommonMark AST (see MarkdownRefScanner),
 * which skips code by construction.
 */
object ContentMasking {
    private val FENCED = Regex("(?s)(`{3,}).*?\\1")
    private val INLINE = Regex("`[^`\\n]+`")

    /** Returns the masked text plus the list of original spans (index → original), for restoration. */
    fun mask(text: String): Pair<String, List<String>> {
        val spans = mutableListOf<String>()
        fun maskWith(input: String, regex: Regex): String = regex.replace(input) { match ->
            val token = " MASK${spans.size} "
            spans.add(match.value)
            token
        }
        return maskWith(maskWith(text, FENCED), INLINE) to spans
    }

    /** Reverses [mask], substituting each token back with its original span. */
    fun restore(text: String, spans: List<String>): String {
        var out = text
        spans.forEachIndexed { index, original -> out = out.replace(" MASK$index ", original) }
        return out
    }

    /** Convenience for scanners that only need code stripped, not restored. */
    fun maskedText(text: String): String = mask(text).first
}
