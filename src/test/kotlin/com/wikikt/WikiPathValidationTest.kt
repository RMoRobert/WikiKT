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
        // Letters in any script, and ZWNJ, which Persian words need (U+200C between می and خواهم).
        validateWikiPath("guide/日本-x.png", allowExtension = true)
        validateWikiPath("docs/naïve", allowExtension = false)
        validateWikiPath("docs/\u0645\u06cc\u200c\u062e\u0648\u0627\u0647\u0645", allowExtension = false)
        // A Devanagari conjunct shaped by a ZWJ after the virama (\u0915\u094d\u200d\u0937), and emoji ZWJ sequences (\ud83d\udc69\u200d\ud83d\udcbb, and
        // \u2764\ufe0f\u200d\ud83d\udd25, whose ZWJ follows a variation selector).
        validateWikiPath("docs/\u0915\u094d\u200d\u0937", allowExtension = false)
        validateWikiPath("docs/\ud83d\udc69\u200d\ud83d\udcbb", allowExtension = false)
        validateWikiPath("docs/\u2764\ufe0f\u200d\ud83d\udd25", allowExtension = false)
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
        // Unicode spaces and invisible characters (`\s` alone only knows ASCII spaces): NBSP, ideographic
        // space, line separator, zero-width space, BOM, right-to-left override, Hangul filler.
        for (c in listOf("\u00a0", "\u3000", "\u2028", "\u200b", "\ufeff", "\u202e", "\u3164")) {
            assertFailsWith<IllegalArgumentException>("U+%04X should be rejected".format(c[0].code)) {
                validateWikiPath("docs/a${c}b", allowExtension = false)
            }
        }
        // ZWNJ/ZWJ where they draw nothing: between Latin or CJK letters, next to a digit or a hyphen, at
        // either end of a segment, bridging an Arabic letter and a Latin one, doubled between Persian
        // letters, or a ZWNJ between emoji.
        for (p in listOf(
            "docs/admin‌guide", "docs/日本‌語", "docs/ab‍1", "docs/a-‌-b",
            "docs/‌abc", "docs/abc‍", "docs/می‌x",
            "docs/می‌‌خ", "docs/👩‌💻",
        )) {
            assertFailsWith<IllegalArgumentException>("'$p' should be rejected") { validateWikiPath(p, allowExtension = false) }
        }
    }
}
