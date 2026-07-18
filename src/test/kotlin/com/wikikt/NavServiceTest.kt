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
            User Guide | /docs/user-guide/getting-started
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
        assertEquals("User Guide", items[2].label)
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
        nav.createMenu(siteId, "docs", NavService.parseDefinition("Docs | /docs"))
        nav.createMenu(siteId, "docs/user-guide", NavService.parseDefinition("Guide | /docs/user-guide/getting-started"))

        assertEquals("Guide", nav.itemsForPath(siteId, "docs/user-guide/getting-started").first().label)
        assertEquals("Docs", nav.itemsForPath(siteId, "docs/other").first().label)
        assertEquals("Home", nav.itemsForPath(siteId, "help/start").first().label)
    }
}
