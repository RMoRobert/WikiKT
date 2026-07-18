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

class ScheduledPublishTest {
    @Test
    fun `publishScheduled publishes only due drafts and clears their schedule`() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-schedule-test;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val pages = PageService(database)
        val now = 1_000_000_000L

        pages.create(siteId, CreatePageRequest("en", "due", "Due", content = "x", published = false, publishAt = now - 1_000), updatedBy = null)
        pages.create(siteId, CreatePageRequest("en", "future", "Future", content = "x", published = false, publishAt = now + 1_000_000), updatedBy = null)
        pages.create(siteId, CreatePageRequest("en", "no-schedule", "None", content = "x", published = false, publishAt = null), updatedBy = null)

        val published = pages.publishScheduled(now)
        assertEquals(1, published, "only the due draft should be published")

        val due = pages.findByLocaleAndPath(siteId, "en", "due")!!
        assertTrue(due.published)
        assertNull(due.publishAt, "schedule should be cleared after publishing")
        assertFalse(pages.findByLocaleAndPath(siteId, "en", "future")!!.published)
        assertFalse(pages.findByLocaleAndPath(siteId, "en", "no-schedule")!!.published)
    }
}
