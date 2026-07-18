package com.wikikt.service

import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Guarded use of user-authored (admin-only) regular expressions for page rules. `java.util.regex`
 * can backtrack catastrophically (ReDoS), so matching runs against a [BudgetedCharSequence] that
 * aborts after a fixed number of character reads. Callers decide the fail direction (a DENY rule
 * should fail closed, an ALLOW rule fail open). Patterns are admin-authored (behind canManageGroups),
 * so this is pragmatic hardening rather than a hard guarantee; a future move to RE2J would make
 * linear-time matching guaranteed and let us drop the budget.
 */
object SafeRegex {
    const val MAX_PATTERN_LENGTH = 500
    private const val STEP_BUDGET = 200_000

    private val compiled = ConcurrentHashMap<String, Pattern>()

    /** Thrown when matching exceeds [STEP_BUDGET] reads — i.e. a likely catastrophic pattern. */
    class BudgetExceeded : RuntimeException("Regex evaluation budget exceeded")

    /** Returns a human-readable error if [pattern] is unusable as a rule, or null if it is fine. */
    fun validate(pattern: String): String? {
        if (pattern.isBlank()) return "Pattern is required."
        if (pattern.length > MAX_PATTERN_LENGTH) return "Pattern is too long (max $MAX_PATTERN_LENGTH characters)."
        return try {
            Pattern.compile(pattern)
            null
        } catch (e: PatternSyntaxException) {
            "Invalid regular expression: ${e.description}"
        }
    }

    /**
     * True if [pattern] matches anywhere in [input] (test/`.test()` semantics, like Wiki.js).
     * @throws BudgetExceeded if evaluation runs away (caller chooses how to treat it).
     */
    fun matches(pattern: String, input: String): Boolean {
        val p = compiled.getOrPut(pattern) { Pattern.compile(pattern) }
        return p.matcher(BudgetedCharSequence(input, STEP_BUDGET)).find()
    }

    /**
     * Length of the literal, anchored prefix of [pattern] — used as a specificity proxy so a long
     * but loose regex does not outrank a precise path prefix. Strips a leading `^`, then counts
     * literal characters until the first regex metacharacter. `^beta/faq`→8, `^beta/.*`→5, `beta|x`→0.
     */
    fun anchoredPrefixLength(pattern: String): Int {
        // Only a pattern anchored to the start (^) has a path-pinned prefix; otherwise specificity is 0.
        if (!pattern.startsWith("^")) return 0
        var n = 0
        for (c in pattern.substring(1)) {
            if (c in META) break
            n++
        }
        return n
    }

    private val META = ".[](){}*+?|\\^\$".toSet()

    /** A CharSequence that throws [BudgetExceeded] once reads exceed [budget], capping backtracking. */
    private class BudgetedCharSequence(private val s: CharSequence, private val budget: Int) : CharSequence {
        private var ops = 0
        override val length: Int get() = s.length
        override fun get(index: Int): Char {
            if (++ops > budget) throw BudgetExceeded()
            return s[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            BudgetedCharSequence(s.subSequence(startIndex, endIndex), budget)
        override fun toString(): String = s.toString()
    }
}
