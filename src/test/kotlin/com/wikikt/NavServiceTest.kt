package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.service.MigrationService
import com.wikikt.service.NavService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavServiceTest {
    @Test
    fun `parseDefinition reads icons, headings, links and skips junk`() {
        val items = NavService.parseDefinition(
            """
            :home: Home | /
            # Documentation
            File One | /dir1/dir2/file1
            a line with no pipe and no hash
            """.trimIndent(),
        )

        assertEquals(3, items.size)
        assertEquals("home", items[0].icon)
        assertFalse(items[0].isHeader)
        assertEquals("/", items[0].target)
        assertTrue(items[1].isHeader)
        assertEquals("Documentation", items[1].label)
        assertNull(items[1].icon)
        assertFalse(items[2].isHeader)
        assertEquals("File One", items[2].label)
    }

    @Test
    fun `itemsForPath picks the longest matching scope and falls back to default`() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-nav-test;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val nav = NavService(database)
        nav.createMenu(siteId, "", NavService.parseDefinition("Home | /"))
        nav.createMenu(siteId, "dir1", NavService.parseDefinition("Dir One | /dir1"))
        nav.createMenu(siteId, "dir1/dir2", NavService.parseDefinition("Dir Two | /dir1/dir2/file1"))

        assertEquals("Dir Two", nav.itemsForPath(siteId, "dir1/dir2/file1").first().label)
        assertEquals("Dir One", nav.itemsForPath(siteId, "dir1/other").first().label)
        assertEquals("Home", nav.itemsForPath(siteId, "help/start").first().label)
    }
}
