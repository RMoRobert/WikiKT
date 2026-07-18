package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.model.CreatePageRequest
import com.wikikt.model.UpdatePageRequest
import com.wikikt.service.MigrationService
import com.wikikt.service.PageService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageMetadataTest {
    @Test
    fun `pages carry a description and normalized tags`() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-pagemeta-test;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val pages = PageService(database)

        val created = pages.create(
            siteId,
            CreatePageRequest(
                locale = "en",
                path = "guide/intro",
                title = "Intro",
                description = "A short guide.",
                content = "hello",
                tags = listOf("Setup", " Beginner ", "setup", ""),
            ),
            updatedBy = null,
        )
        // Tags are lowercased, trimmed, de-duplicated, blanks dropped.
        assertEquals(setOf("setup", "beginner"), created.tags.toSet())
        assertEquals("A short guide.", created.description)

        val fetched = pages.findById(created.id)!!
        assertEquals("A short guide.", fetched.description)
        assertEquals(listOf("beginner", "setup"), fetched.tags, "tags come back sorted")

        // list() batch-loads tags too
        assertEquals(setOf("setup", "beginner"), pages.list(siteId).single { it.id == created.id }.tags.toSet())

        // Update replaces tags and changes description
        pages.update(created.id, UpdatePageRequest(description = "Updated.", tags = listOf("rules")), updatedBy = null)
        val afterUpdate = pages.findById(created.id)!!
        assertEquals("Updated.", afterUpdate.description)
        assertEquals(listOf("rules"), afterUpdate.tags)

        // A blank description clears it; omitting tags (null) leaves them unchanged
        pages.update(created.id, UpdatePageRequest(description = ""), updatedBy = null)
        val cleared = pages.findById(created.id)!!
        assertNull(cleared.description, "blank description is stored as null")
        assertEquals(listOf("rules"), cleared.tags, "null tags leaves existing tags untouched")

        // Per-page meta robots override: set, then a blank value clears it back to "inherit site default".
        assertNull(cleared.metaRobots, "no override by default")
        pages.update(created.id, UpdatePageRequest(metaRobots = "noindex,nofollow"), updatedBy = null)
        assertEquals("noindex,nofollow", pages.findById(created.id)!!.metaRobots)
        pages.update(created.id, UpdatePageRequest(metaRobots = ""), updatedBy = null)
        assertNull(pages.findById(created.id)!!.metaRobots, "blank robots clears the override")

        // Delete removes the page (and its tag rows)
        assertTrue(pages.delete(created.id))
        assertNull(pages.findById(created.id))
    }
}
