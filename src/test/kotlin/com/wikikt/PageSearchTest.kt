package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.model.CreatePageRequest
import com.wikikt.service.FragmentService
import com.wikikt.service.MigrationService
import com.wikikt.service.PageService
import com.wikikt.service.SearchIndexService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PageSearchTest {
    private fun db(name: String) = DatabaseFactory.connect(
        DatabaseConfig(
            type = DatabaseType.H2,
            connection = DatabaseConnectionConfig(
                r2dbcUrl = "r2dbc:h2:mem:///$name;DB_CLOSE_DELAY=-1",
                username = "sa",
                password = "",
            ),
        ),
    )

    // Search reads only the PageSearchIndexTable column, so pages must be indexed on write — wire the
    // same reindex-on-content-change callback AppContext installs in production.
    private fun wireSearchIndex(database: org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase, pages: PageService) {
        val searchIndex = SearchIndexService(pages, FragmentService(database), "en")
        pages.onContentChanged = { pageId -> searchIndex.reindex(pageId) }
    }

    @Test
    fun `search matches title, content, description and respects locale and published`() = runBlocking {
        val database = db("wikikt-search-test")
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val pages = PageService(database)
        wireSearchIndex(database, pages)

        pages.create(siteId, CreatePageRequest(locale = "en", path = "guide/network", title = "Network Setup", content = "Configure your network devices here.", published = true), null)
        pages.create(siteId, CreatePageRequest(locale = "en", path = "guide/wireless", title = "Wireless Basics", description = "Network configuration for wireless devices", content = "Different protocol.", published = true), null)
        pages.create(siteId, CreatePageRequest(locale = "en", path = "guide/draft", title = "Draft About Network", content = "secret network notes", published = false), null)
        pages.create(siteId, CreatePageRequest(locale = "fr", path = "guide/network-fr", title = "Configuration en français", content = "Configurez votre réseau.", published = true), null)

        // Locale-scoped, published-only: matches title hit + description hit, NOT the draft, NOT the fr page.
        val en = pages.search(siteId, "network", locale = "en", limit = 50)
        val enPaths = en.map { it.page.path }.toSet()
        assertTrue("guide/network" in enPaths, "title match")
        assertTrue("guide/wireless" in enPaths, "description match")
        assertFalse("guide/draft" in enPaths, "unpublished excluded")
        assertFalse("guide/network-fr" in enPaths, "other locale excluded when locale-scoped")

        // Title hit ranks before description-only hit.
        assertEquals("guide/network", en.first().page.path, "title match ranks first")

        // All-locales search (locale = null) includes the French page.
        val all = pages.search(siteId, "network", locale = null, limit = 50)
        assertTrue("guide/network-fr" in all.map { it.page.path }.toSet(), "fr page found across all locales")

        // Short query returns nothing.
        assertTrue(pages.search(siteId, "q", locale = "en").isEmpty(), "below min length")
        Unit
    }

    @Test
    fun `like wildcards in the query are treated literally`() = runBlocking {
        val database = db("wikikt-search-wildcard-test")
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val pages = PageService(database)
        wireSearchIndex(database, pages)
        pages.create(siteId, CreatePageRequest(locale = "en", path = "guide/percent", title = "Battery at 50% capacity", content = "x", published = true), null)
        pages.create(siteId, CreatePageRequest(locale = "en", path = "guide/other", title = "Unrelated", content = "y", published = true), null)

        // "50%" must match only the literal-percent page, not act as a wildcard matching everything.
        val hits = pages.search(siteId, "50%", locale = "en", limit = 50)
        assertEquals(listOf("guide/percent"), hits.map { it.page.path })
        Unit
    }

    @Test
    fun `search matches the path segment and a rename reindexes`() = runBlocking {
        val database = db("wikikt-search-path-test")
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val pages = PageService(database)
        wireSearchIndex(database, pages)

        val page = pages.create(siteId, CreatePageRequest(locale = "en", path = "runbooks/backup", title = "Nightly Job", content = "body text", published = true), null)
        // "backup" appears only in the path — neither the title nor the body — so this exercises that
        // the path is folded into the searchable document.
        assertEquals(listOf("runbooks/backup"), pages.search(siteId, "backup", locale = "en").map { it.page.path }, "path match")

        // A same-locale rename must refresh the index row: the new path is searchable, the old is not.
        pages.move(page.id, "en", "runbooks/restore", null)
        assertEquals(listOf("runbooks/restore"), pages.search(siteId, "restore", locale = "en").map { it.page.path }, "new path searchable after rename")
        assertTrue(pages.search(siteId, "backup", locale = "en").isEmpty(), "old path no longer indexed after rename")
        Unit
    }

    @Test
    fun `snippet centers on the match with ellipses`() {
        val content = "Intro paragraph. ".repeat(10) + "The special keyword appears here. " + "Trailing text. ".repeat(10)
        val snip = PageService.searchSnippet(content, "special keyword", maxLen = 80)
        assertTrue(snip.contains("special keyword"), "snippet contains the match: $snip")
        assertTrue(snip.startsWith("…") && snip.endsWith("…"), "snippet padded with ellipses: $snip")
        // maxLen is approximate: ~±maxLen/2 around the match, plus the match itself and ellipses.
        assertTrue(snip.length <= 80 + "special keyword".length + 12, "snippet roughly bounded: ${snip.length}")
    }
}
