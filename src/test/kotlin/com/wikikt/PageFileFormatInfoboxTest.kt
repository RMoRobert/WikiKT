package com.wikikt

import com.wikikt.db.ContentFormat
import com.wikikt.model.PageRecord
import com.wikikt.service.PageFileFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageFileFormatInfoboxTest {
    private fun page(infobox: String?, html: Boolean = false, metaRobots: String? = null) = PageRecord(
        id = 1u, siteId = 1u, locale = "en", path = "apps/x", title = "X",
        description = "A description", metaRobots = metaRobots, content = "# Body\n\ntext",
        contentFormat = if (html) ContentFormat.HTML else ContentFormat.MARKDOWN,
        published = true, publishAt = null, createdAt = 0L, updatedAt = 0L, updatedBy = null,
        tags = listOf("a", "b"), infobox = infobox,
    )

    @Test
    fun `metaRobots round-trips through the front-matter`() {
        val file = PageFileFormat.pageFileBody(page(infobox = null, metaRobots = "noindex,nofollow"))
        assertTrue(file.contains("\nmetaRobots: noindex,nofollow"), "front-matter carries metaRobots:\n$file")
        assertEquals("noindex,nofollow", PageFileFormat.parsePageFile(file, html = false).metaRobots)
    }

    @Test
    fun `no metaRobots writes no line and parses to null`() {
        val file = PageFileFormat.pageFileBody(page(infobox = null, metaRobots = null))
        assertFalse(file.contains("metaRobots:"), file)
        assertEquals(null, PageFileFormat.parsePageFile(file, html = false).metaRobots)
    }

    @Test
    fun `infobox round-trips through export then import (all field types)`() {
        val json = """{"platform_version":"2.4.0","deployment":["Local","Cloud"],"transport":"Polling","official":true,"notes":"Supports **push** updates."}"""
        val file = PageFileFormat.pageFileBody(page(json))
        assertTrue(file.contains("\ninfobox:\n"), "front-matter carries a nested infobox block:\n$file")
        val parsed = PageFileFormat.parsePageFile(file, html = false)
        assertEquals(json, parsed.infobox, "infobox JSON is byte-stable across the round-trip")
        assertEquals("# Body\n\ntext", parsed.content)
    }

    @Test
    fun `no infobox writes no block and parses to null`() {
        val file = PageFileFormat.pageFileBody(page(null))
        assertFalse(file.contains("infobox:"), file)
        assertEquals(null, PageFileFormat.parsePageFile(file, html = false).infobox)
    }

    @Test
    fun `hand-authored infobox block imports (bare scalars, flow array, boolean)`() {
        val raw = "---\ntitle: X\ninfobox:\n  platform_version: 5.1\n  deployment: [Local, Cloud]\n  official: true\n---\n\n# Body"
        val parsed = PageFileFormat.parsePageFile(raw, html = false)
        assertEquals(
            """{"platform_version":"5.1","deployment":["Local","Cloud"],"official":true}""",
            parsed.infobox,
        )
        assertEquals("# Body", parsed.content)
    }

    @Test
    fun `infobox round-trips inside an HTML comment block`() {
        val json = """{"platform_version":"5.1","official":false}"""
        val file = PageFileFormat.pageFileBody(page(json, html = true))
        val parsed = PageFileFormat.parsePageFile(file, html = true)
        assertEquals(json, parsed.infobox)
    }

    @Test
    fun `multi-template infobox (two-level nesting by slug) round-trips`() {
        val json = """{"app_info":{"platform_version":"2.4.0","official":true},"person":{"role":"Maintainer"}}"""
        val file = PageFileFormat.pageFileBody(page(json))
        assertTrue(
            file.contains("\ninfobox:\n  app_info:\n    platform_version:") &&
                file.contains("\n  person:\n    role:"),
            "front-matter nests each template's fields under its slug:\n$file",
        )
        val parsed = PageFileFormat.parsePageFile(file, html = false)
        assertEquals(json, parsed.infobox, "multi-template infobox JSON is byte-stable across the round-trip")
    }

    @Test
    fun `hand-authored multi-template infobox block imports`() {
        val raw = "---\ntitle: X\ninfobox:\n  app_info:\n    platform_version: 5.1\n  person:\n    role: Maintainer\n---\n\n# Body"
        val parsed = PageFileFormat.parsePageFile(raw, html = false)
        assertEquals(
            """{"app_info":{"platform_version":"5.1"},"person":{"role":"Maintainer"}}""",
            parsed.infobox,
        )
    }
}
