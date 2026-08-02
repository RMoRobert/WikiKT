package com.wikikt

import com.wikikt.model.validateWikiPath
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WikiPathValidationTest {
    @Test
    fun `accepts well-formed page and asset paths`() {
        validateWikiPath("dir1/dir2/file1", allowExtension = false)
        validateWikiPath("guide/intro", allowExtension = false)
        // `home` is the landing page path — a real editable page, NOT a reserved segment.
        validateWikiPath("home", allowExtension = false)
        validateWikiPath("images/logo.png", allowExtension = true)
        validateWikiPath("logo.png", allowExtension = true) // root-level asset filename
        // `search` is no longer reserved — search moved to the single-char `/s` route.
        validateWikiPath("search", allowExtension = false)
        validateWikiPath("search/results", allowExtension = false)
    }

    @Test
    fun `rejects reserved, short, locale-like, period and unsafe segments`() {
        // reserved first segments (single-char routes + our multi-char names). `s` is search's route;
        // `r` is an unwired single char, still reserved because the whole one-letter space is held.
        for (p in listOf("a/x", "u/x", "f/x", "e/x", "s/x", "r/x", "new/x")) {
            assertFailsWith<IllegalArgumentException>("'$p' should be rejected") { validateWikiPath(p, allowExtension = false) }
        }
        // single / two-character first segment
        assertFailsWith<IllegalArgumentException> { validateWikiPath("z/x", allowExtension = false) }
        assertFailsWith<IllegalArgumentException> { validateWikiPath("ab/x", allowExtension = false) }
        // locale-code first segment
        assertFailsWith<IllegalArgumentException> { validateWikiPath("en/x", allowExtension = false) }
        assertFailsWith<IllegalArgumentException> { validateWikiPath("fr-ca/page", allowExtension = false) }
        // period in a page segment, and in a non-final asset segment
        assertFailsWith<IllegalArgumentException> { validateWikiPath("foo.bar/page", allowExtension = false) }
        assertFailsWith<IllegalArgumentException> { validateWikiPath("ver.1/logo.png", allowExtension = true) }
        // spaces / unsafe characters
        assertFailsWith<IllegalArgumentException> { validateWikiPath("foo bar/page", allowExtension = false) }
        assertFailsWith<IllegalArgumentException> { validateWikiPath("foo?x/page", allowExtension = false) }
    }
}
