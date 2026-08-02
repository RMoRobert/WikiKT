package com.wikikt

import com.wikikt.routing.resolveRelativeWikiUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * WikiJS resolves a relative link against the current page treated as a *directory* (a link `file1`
 * on page `dir1` targets `dir1/file1`). These cover [resolveRelativeWikiUrl], which reproduces that
 * so imported WikiJS content links correctly instead of landing a directory too high.
 */
class RelativeLinkResolutionTest {
    @Test
    fun `resolves a bare relative link against the page as a directory`() {
        // The WikiJS shape: page `dir1` links to its children.
        assertEquals("/en/dir1/file1", resolveRelativeWikiUrl("file1", "en", "dir1"))
        assertEquals("/en/dir1/dir2/file2", resolveRelativeWikiUrl("dir2/file2", "en", "dir1"))
        assertEquals("/en/dir1/dir2/file1", resolveRelativeWikiUrl("file1", "en", "dir1/dir2"))
    }

    @Test
    fun `normalizes dot and dot-dot segments, clamping at the locale root`() {
        assertEquals("/en/dir1/file2", resolveRelativeWikiUrl("./file2", "en", "dir1"))
        // `..` climbs out of the page directory.
        assertEquals("/en/dir1/file2", resolveRelativeWikiUrl("../file2", "en", "dir1/file1"))
        // Climbing past the root clamps there rather than escaping the locale.
        assertEquals("/en/file2", resolveRelativeWikiUrl("../../file2", "en", "dir1"))
    }

    @Test
    fun `preserves query and fragment and honors the page locale`() {
        assertEquals("/en/dir1/dir2/file1#usage", resolveRelativeWikiUrl("file1#usage", "en", "dir1/dir2"))
        assertEquals("/en/dir1/dir2/file1?v=2", resolveRelativeWikiUrl("file1?v=2", "en", "dir1/dir2"))
        assertEquals("/pt-BR/dir1/file1", resolveRelativeWikiUrl("file1", "pt-BR", "dir1"))
    }

    @Test
    fun `leaves absolute, anchor, external, and empty URLs untouched`() {
        assertNull(resolveRelativeWikiUrl("/en/dir1/dir2/file1", "en", "dir1"))
        assertNull(resolveRelativeWikiUrl("#section", "en", "dir1"))
        assertNull(resolveRelativeWikiUrl("https://example.com", "en", "dir1"))
        assertNull(resolveRelativeWikiUrl("mailto:support@example.com", "en", "dir1"))
        assertNull(resolveRelativeWikiUrl("//cdn.example.com/x.png", "en", "dir1"))
        assertNull(resolveRelativeWikiUrl("", "en", "dir1"))
        assertNull(resolveRelativeWikiUrl("?q=1", "en", "dir1"))
    }
}
