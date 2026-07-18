package com.wikikt

import com.wikikt.db.ContentFormat
import com.wikikt.model.PageRecord
import com.wikikt.service.SiteNavTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure folder/page tree builder. It derives hierarchy from flat page paths, so the
 * key behaviors are: intermediate path segments with no page become labelled container folders, a real
 * page is a leaf (or a folder when it also has descendants), URLs are the canonical `/{locale}/{path}`,
 * and siblings sort folders-first then alphabetically.
 */
class SiteNavTreeTest {
    private fun page(path: String, title: String, locale: String = "en") = PageRecord(
        id = 1u, siteId = 1u, locale = locale, path = path, title = title,
        description = null, metaRobots = null, content = "", contentFormat = ContentFormat.MARKDOWN,
        published = true, publishAt = null, createdAt = 0L, updatedAt = 0L, updatedBy = null,
    )

    @Test
    fun `derives container folders and pages from flat paths`() {
        val roots = SiteNavTree.build(
            listOf(
                page("home", "Home"),
                page("docs/user-guide", "User Guide"),
                page("docs/user-guide/getting-started", "Getting Started"),
                page("guides/faq", "FAQ"),
            ),
        )

        // Folders (docs, guides) sort before the leaf page (home); alpha within each group.
        assertEquals(listOf("docs", "guides", "home"), roots.map { it.path })

        val docs = roots.first { it.path == "docs" }
        // "docs" has no page of its own → a container folder labelled from the humanized segment.
        assertFalse(docs.hasPage)
        assertNull(docs.url)
        assertEquals("Docs", docs.label)
        assertEquals(1, docs.children.size)

        val userGuide = docs.children.single()
        // "docs/user-guide" is BOTH a page and a folder (it has a child).
        assertTrue(userGuide.hasPage)
        assertEquals("User Guide", userGuide.label)
        assertEquals("/en/docs/user-guide", userGuide.url)
        assertEquals(1, userGuide.children.size)

        val leaf = userGuide.children.single()
        assertTrue(leaf.hasPage)
        assertEquals("docs/user-guide/getting-started", leaf.path)
        assertEquals("Getting Started", leaf.label)
        assertEquals("/en/docs/user-guide/getting-started", leaf.url)
        assertTrue(leaf.children.isEmpty())

        val home = roots.first { it.path == "home" }
        assertTrue(home.hasPage)
        assertEquals("/en/home", home.url)
        assertTrue(home.children.isEmpty())
    }

    @Test
    fun `sorts folders before leaf pages, each alphabetically`() {
        val roots = SiteNavTree.build(
            listOf(
                page("docs/zebra", "Zebra"),            // leaf page
                page("docs/user-guide/getting-started", "Getting Started"),  // under a folder-page
                page("docs/user-guide", "User Guide"),
                page("docs/alpha/intro", "Introduction"),      // under a container folder "alpha"
            ),
        )
        val docs = roots.single()
        // Folders first (alpha, user-guide — both have children), then the leaf page (zebra).
        assertEquals(listOf("docs/alpha", "docs/user-guide", "docs/zebra"), docs.children.map { it.path })
        assertFalse(docs.children.first { it.path == "docs/alpha" }.hasPage)
        assertTrue(docs.children.first { it.path == "docs/user-guide" }.hasPage)
    }

    @Test
    fun `empty input yields no nodes`() {
        assertTrue(SiteNavTree.build(emptyList()).isEmpty())
    }

    @Test
    fun `a section with no readable pages never appears`() {
        // The caller passes only the pages the viewer may see (PermissionService.readablePages). A folder
        // is derived purely from those paths, so a section whose pages are ALL filtered out produces no
        // node at all — its name is never emitted (not hidden client-side; simply absent).
        val visibleToViewer = listOf(
            page("public/intro", "Intro"),
            // Nothing under "secret/..." is in this list — the viewer can't read it.
        )
        val roots = SiteNavTree.build(visibleToViewer)
        assertEquals(listOf("public"), roots.map { it.path })
        assertTrue(roots.none { it.path == "secret" }, "a section with no visible pages must not appear")
    }

    @Test
    fun `a hidden folder-page with a visible child shows the humanized segment, not the hidden title`() {
        // The parent page "team/roadmap" is NOT readable (absent from the list), but its child is. The
        // intermediate node appears only as a container labelled from the URL segment the visible child
        // already exposes — the hidden page's own title is never attached to any node.
        val roots = SiteNavTree.build(listOf(page("team/roadmap/q3-notes", "Confidential Q3 Plan")))
        val roadmap = roots.single { it.path == "team" }.children.single { it.path == "team/roadmap" }
        assertFalse(roadmap.hasPage)            // no page record for it → a container only, not a link
        assertNull(roadmap.url)
        assertEquals("Roadmap", roadmap.label)  // humanized from the path segment, NOT a hidden page title
        assertEquals("Confidential Q3 Plan", roadmap.children.single().label)
    }

    @Test
    fun `page url reflects its own locale`() {
        val roots = SiteNavTree.build(listOf(page("guide", "Guide", locale = "pt-BR")))
        assertEquals("/pt-BR/guide", roots.single().url)
    }
}
