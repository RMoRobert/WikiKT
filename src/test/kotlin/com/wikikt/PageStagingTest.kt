package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.ContentFormat
import com.wikikt.db.DatabaseFactory
import com.wikikt.model.CreatePageRequest
import com.wikikt.model.UpdatePageRequest
import com.wikikt.service.MigrationService
import com.wikikt.service.PageService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageStagingTest {
    private suspend fun newService(name: String): Pair<PageService, UInt> {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-staging-$name;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        return PageService(database) to siteId
    }

    @Test
    fun `staged versions promote on schedule and history captures title plus description`() = runBlocking {
        val (pages, siteId) = newService("promote")
        val page = pages.create(
            siteId,
            CreatePageRequest(locale = "en", path = "guide/intro", title = "Title A", description = "Desc A", content = "Body A"),
            updatedBy = null,
        )
        pages.upsertStaged(page.id, "Title B", "Desc B", "Body B", ContentFormat.MARKDOWN, publishAt = 1_000L, by = null)

        // Not yet due → live unchanged.
        assertEquals(0, pages.promoteScheduledStaged(500L))
        assertEquals("Title A", pages.findById(page.id)!!.title)

        // Due → live swaps and the pre-promotion version (incl. description) is in history.
        assertEquals(1, pages.promoteScheduledStaged(2_000L))
        val live = pages.findById(page.id)!!
        assertEquals("Title B", live.title)
        assertEquals("Desc B", live.description)
        assertEquals("Body B", live.content)
        assertNull(pages.stagedFor(page.id), "staged row consumed")
        val rev1 = pages.revisions(page.id).single()
        assertEquals("Title A", rev1.title)
        assertEquals("Desc A", rev1.description)
        assertEquals("Body A", rev1.content)

        // Staging a null description clears it on promote (the partial-update bug this avoids).
        pages.upsertStaged(page.id, "Title C", null, "Body C", ContentFormat.MARKDOWN, publishAt = null, by = null)
        assertTrue(pages.promoteStaged(page.id, null))
        assertNull(pages.findById(page.id)!!.description)

        // Restoring revision #1 brings back its title AND description.
        assertTrue(pages.restoreRevision(page.id, 1, null))
        val restored = pages.findById(page.id)!!
        assertEquals("Title A", restored.title)
        assertEquals("Desc A", restored.description)
        Unit
    }

    @Test
    fun `unpublishing discards staged, and a discarded row is not promoted`() = runBlocking {
        val (pages, siteId) = newService("invariants")
        val live = pages.create(siteId, CreatePageRequest(locale = "en", path = "docs/a", title = "A", content = "x"), null)
        pages.upsertStaged(live.id, "A2", null, "x2", ContentFormat.MARKDOWN, publishAt = null, by = null)
        pages.update(live.id, UpdatePageRequest(published = false), updatedBy = null)
        assertNull(pages.stagedFor(live.id), "unpublishing a live page drops its staged version")

        val other = pages.create(siteId, CreatePageRequest(locale = "en", path = "docs/b", title = "B", content = "y"), null)
        pages.upsertStaged(other.id, "B2", null, "y2", ContentFormat.MARKDOWN, publishAt = 1_000L, by = null)
        pages.discardStaged(other.id)
        assertEquals(0, pages.promoteScheduledStaged(2_000L), "discarded staged is not promoted")
        assertTrue(pages.revisions(other.id).isEmpty(), "no spurious revision from a no-op promote")
        assertEquals("B", pages.findById(other.id)!!.title)
        Unit
    }
}
