package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.model.CreatePageRequest
import com.wikikt.service.MigrationService
import com.wikikt.service.PageService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageMoveTest {
    @Test
    fun `move changes locale and path, rejects collisions`() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-move-test;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val pages = PageService(database)

        val page = pages.create(siteId, CreatePageRequest(locale = "en", path = "guide/old", title = "A", content = "x"), null)
        pages.create(siteId, CreatePageRequest(locale = "en", path = "guide/taken", title = "B", content = "y"), null)

        // Move to a free path.
        assertTrue(pages.move(page.id, "en", "guide/new", by = null))
        assertNull(pages.findByLocaleAndPath(siteId, "en", "guide/old"), "old path no longer resolves (no redirect)")
        assertEquals(page.id, pages.findByLocaleAndPath(siteId, "en", "guide/new")!!.id)

        // Move onto a taken path fails and leaves the page where it was.
        assertFalse(pages.move(page.id, "en", "guide/taken", by = null))
        assertEquals(page.id, pages.findByLocaleAndPath(siteId, "en", "guide/new")!!.id)

        // Locale change is also a move.
        assertTrue(pages.move(page.id, "fr", "guide/new", by = null))
        assertEquals(page.id, pages.findByLocaleAndPath(siteId, "fr", "guide/new")!!.id)
        assertNull(pages.findByLocaleAndPath(siteId, "en", "guide/new"))
        Unit
    }
}
