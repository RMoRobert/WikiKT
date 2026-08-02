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
                page("dir1/dir2", "Dir Two"),
                page("dir1/dir2/file1", "File One"),
                page("dir3/file2", "File Two"),
            ),
        )

        // Folders (dir1, dir3) sort before the leaf page (home); alpha within each group.
        assertEquals(listOf("dir1", "dir3", "home"), roots.map { it.path })

        val dir1 = roots.first { it.path == "dir1" }
        // "dir1" has no page of its own → a container folder labelled from the humanized segment.
        assertFalse(dir1.hasPage)
        assertNull(dir1.url)
        assertEquals("Dir1", dir1.label)
        assertEquals(1, dir1.children.size)

        val dir2 = dir1.children.single()
        // "dir1/dir2" is BOTH a page and a folder (it has a child).
        assertTrue(dir2.hasPage)
        assertEquals("Dir Two", dir2.label)
        assertEquals("/en/dir1/dir2", dir2.url)
        assertEquals(1, dir2.children.size)

        val leaf = dir2.children.single()
        assertTrue(leaf.hasPage)
        assertEquals("dir1/dir2/file1", leaf.path)
        assertEquals("File One", leaf.label)
        assertEquals("/en/dir1/dir2/file1", leaf.url)
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
                page("dir1/zebra", "Zebra"),            // leaf page
                page("dir1/dir2/file1", "File One"),    // under a folder-page
                page("dir1/dir2", "Dir Two"),
                page("dir1/alpha/intro", "Introduction"),      // under a container folder "alpha"
            ),
        )
        val dir1 = roots.single()
        // Folders first (alpha, dir2 — both have children), then the leaf page (zebra).
        assertEquals(listOf("dir1/alpha", "dir1/dir2", "dir1/zebra"), dir1.children.map { it.path })
        assertFalse(dir1.children.first { it.path == "dir1/alpha" }.hasPage)
        assertTrue(dir1.children.first { it.path == "dir1/dir2" }.hasPage)
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
