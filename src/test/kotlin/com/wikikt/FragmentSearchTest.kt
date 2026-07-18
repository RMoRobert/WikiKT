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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FragmentSearchTest {
    /** Wires PageService + FragmentService + SearchIndexService exactly as AppContext does. */
    private data class Wiring(
        val siteId: UInt,
        val pages: PageService,
        val fragments: FragmentService,
        val searchIndex: SearchIndexService,
    )

    private fun wire(name: String): Wiring {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///$name;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        val siteId = runBlocking {
            MigrationService(database).migrate()
            com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        }
        val pages = PageService(database)
        val fragments = FragmentService(database)
        val searchIndex = SearchIndexService(pages, fragments, defaultLocale = "en")
        pages.onContentChanged = { id -> searchIndex.reindex(id) }
        fragments.onFragmentsChanged = { changedSiteId, _ -> searchIndex.reindexAll(changedSiteId) }
        return Wiring(siteId, pages, fragments, searchIndex)
    }

    @Test
    fun `text inside a transcluded fragment matches the page, and tracks fragment edits`() = runBlocking {
        val (siteId, pages, fragments, _) = wire("wikikt-fragsearch-test")

        fragments.create(siteId, locale = "en", key = "warranty", title = "Warranty", content = "Devices are covered for 24 months.", updatedBy = null)
        // Page body references the fragment; its own text does NOT contain "24 months".
        pages.create(
            siteId,
            CreatePageRequest(locale = "en", path = "guide/hub", title = "Hub Guide", content = "Specs below.\n\n{{fragment:warranty}}", published = true),
            null,
        )

        // The page is found by text that only exists inside the fragment.
        val byFragment = pages.search(siteId, "24 months", locale = "en", limit = 50)
        assertTrue(byFragment.any { it.page.path == "guide/hub" }, "page found via fragment content")
        // And the snippet is drawn from the expanded text, so it contains the match.
        assertTrue(byFragment.first { it.page.path == "guide/hub" }.searchText.contains("24 months"))

        // Editing the fragment re-indexes dependent pages: new text matches, old text doesn't.
        val frag = fragments.list(siteId).first { it.key == "warranty" }
        fragments.update(frag.id, "en", "warranty", "Warranty", "Devices are covered for 36 months.", null)

        assertTrue(pages.search(siteId, "36 months", locale = "en").any { it.page.path == "guide/hub" }, "new fragment text matches")
        assertFalse(pages.search(siteId, "24 months", locale = "en").any { it.page.path == "guide/hub" }, "stale fragment text no longer matches")
        Unit
    }

    @Test
    fun `narrowed reindex follows nested fragment references`() = runBlocking {
        val (siteId, pages, fragments, _) = wire("wikikt-fragsearch-nested-test")

        // page -> {{fragment:outer}} -> {{fragment:inner}} -> "alpha term"
        fragments.create(siteId, locale = "en", key = "inner", title = "Inner", content = "alpha term", updatedBy = null)
        fragments.create(siteId, locale = "en", key = "outer", title = "Outer", content = "Wrapper: {{fragment:inner}}", updatedBy = null)
        pages.create(
            siteId,
            CreatePageRequest(locale = "en", path = "guide/nested", title = "Nested", content = "See: {{fragment:outer}}", published = true),
            null,
        )
        assertTrue(pages.search(siteId, "alpha term", locale = "en").any { it.page.path == "guide/nested" }, "found via two-level transclusion")

        // Editing the INNER fragment must reindex the page even though it only references OUTER —
        // the affected-key closure walks outer→inner.
        val inner = fragments.list(siteId).first { it.key == "inner" }
        fragments.update(inner.id, "en", "inner", "Inner", "beta term", null)

        assertTrue(pages.search(siteId, "beta term", locale = "en").any { it.page.path == "guide/nested" }, "nested edit propagated")
        assertFalse(pages.search(siteId, "alpha term", locale = "en").any { it.page.path == "guide/nested" }, "stale nested text gone")
        Unit
    }
}
