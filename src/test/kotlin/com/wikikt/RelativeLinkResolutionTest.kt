package com.wikikt

import com.wikikt.routing.resolveRelativeWikiUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * WikiJS resolves a relative link against the current page treated as a *directory* (a link `overview`
 * on page `developer` targets `developer/overview`). These cover [resolveRelativeWikiUrl], which
 * reproduces that so imported WikiJS content links correctly instead of landing a directory too high.
 */
class RelativeLinkResolutionTest {
    @Test
    fun `resolves a bare relative link against the page as a directory`() {
        // The real WikiJS cases from the imported repo: page `developer` links to its children.
        assertEquals("/en/developer/overview", resolveRelativeWikiUrl("overview", "en", "developer"))
        assertEquals("/en/developer/driver/driver-object", resolveRelativeWikiUrl("driver/driver-object", "en", "developer"))
        assertEquals("/en/docs/user-guide/getting-started", resolveRelativeWikiUrl("getting-started", "en", "docs/user-guide"))
    }

    @Test
    fun `normalizes dot and dot-dot segments, clamping at the locale root`() {
        assertEquals("/en/developer/sibling", resolveRelativeWikiUrl("./sibling", "en", "developer"))
        // `..` climbs out of the page directory.
        assertEquals("/en/developer/capability-object", resolveRelativeWikiUrl("../capability-object", "en", "developer/overview"))
        // Climbing past the root clamps there rather than escaping the locale.
        assertEquals("/en/bar", resolveRelativeWikiUrl("../../bar", "en", "foo"))
    }

    @Test
    fun `preserves query and fragment and honors the page locale`() {
        assertEquals("/en/docs/user-guide/getting-started#usage", resolveRelativeWikiUrl("getting-started#usage", "en", "docs/user-guide"))
        assertEquals("/en/docs/user-guide/getting-started?v=2", resolveRelativeWikiUrl("getting-started?v=2", "en", "docs/user-guide"))
        assertEquals("/pt-BR/guia/intro", resolveRelativeWikiUrl("intro", "pt-BR", "guia"))
    }

    @Test
    fun `leaves absolute, anchor, external, and empty URLs untouched`() {
        assertNull(resolveRelativeWikiUrl("/en/docs/user-guide/getting-started", "en", "docs"))
        assertNull(resolveRelativeWikiUrl("#section", "en", "docs"))
        assertNull(resolveRelativeWikiUrl("https://example.com", "en", "docs"))
        assertNull(resolveRelativeWikiUrl("mailto:support@example.com", "en", "apps"))
        assertNull(resolveRelativeWikiUrl("//cdn.example.com/x.png", "en", "apps"))
        assertNull(resolveRelativeWikiUrl("", "en", "apps"))
        assertNull(resolveRelativeWikiUrl("?q=1", "en", "apps"))
    }
}
