package com.wikikt

import com.wikikt.service.AccessResolver
import com.wikikt.service.SafeRegex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeRegexTest {
    @Test
    fun `validate accepts good patterns and rejects bad ones`() {
        assertNull(SafeRegex.validate("^(.*/)?beta-"))
        assertNotNull(SafeRegex.validate("["), "invalid syntax is rejected")
        assertNotNull(SafeRegex.validate("a".repeat(SafeRegex.MAX_PATTERN_LENGTH + 1)), "over-length is rejected")
        assertNotNull(SafeRegex.validate(""), "blank is rejected")
    }

    @Test
    fun `matches uses test semantics`() {
        assertTrue(SafeRegex.matches("beta-", "apps/beta-feature"))
        assertFalse(SafeRegex.matches("^beta-", "apps/beta-feature"))
        assertTrue(SafeRegex.matches("^(.*/)?beta-", "apps/beta-feature"))
    }

    @Test
    fun `anchoredPrefixLength measures the literal pinned prefix`() {
        assertEquals(8, SafeRegex.anchoredPrefixLength("^beta/faq"))
        assertEquals(5, SafeRegex.anchoredPrefixLength("^beta/.*"))
        assertEquals(0, SafeRegex.anchoredPrefixLength("beta|x"))
    }

    @Test
    fun `runaway evaluation aborts and a DENY rule fails closed`() {
        // A read-heavy match (input far longer than the step budget) deterministically trips the
        // guard regardless of JVM regex optimizations — standing in for a catastrophic backtrack.
        val pattern = "a*b"
        val input = "a".repeat(250_000)

        assertFailsWith<SafeRegex.BudgetExceeded> { SafeRegex.matches(pattern, input) }

        val resource = AccessResolver.Resource(siteId = 1u, locale = "en", path = input)
        fun rule(mode: AccessResolver.Mode, p: String, match: AccessResolver.Match) =
            AccessResolver.AccessRule(mode, setOf(AccessResolver.Perm.READ_PAGES), emptySet(), emptySet(), match, p)

        // A DENY on a runaway regex still blocks, even against a broad ALLOW (fails closed).
        val denyPrincipal = AccessResolver.Principal(
            emptySet(),
            listOf(rule(AccessResolver.Mode.ALLOW, "", AccessResolver.Match.START), rule(AccessResolver.Mode.DENY, pattern, AccessResolver.Match.REGEX)),
        )
        assertFalse(AccessResolver.check(denyPrincipal, AccessResolver.Perm.READ_PAGES, resource), "DENY fails closed on regex abort")

        // An ALLOW on a runaway regex does not grant (fails open).
        val allowPrincipal = AccessResolver.Principal(emptySet(), listOf(rule(AccessResolver.Mode.ALLOW, pattern, AccessResolver.Match.REGEX)))
        assertFalse(AccessResolver.check(allowPrincipal, AccessResolver.Perm.READ_PAGES, resource), "ALLOW fails open on regex abort")
    }
}
