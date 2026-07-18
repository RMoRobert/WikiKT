package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.model.CreatePageRequest
import com.wikikt.model.UpdatePageRequest
import com.wikikt.service.AssetService
import com.wikikt.service.MigrationService
import com.wikikt.service.PageService
import com.wikikt.service.SiteService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryRetentionTest {
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private class Env(name: String) {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-$name;DB_CLOSE_DELAY=-1", username = "sa", password = "",
                ),
            ),
        )
        val storage: Path = Files.createTempDirectory("wikikt-$name-assets").also { Files.createDirectories(it.resolve("tmp")) }
        val pages = PageService(database)
        val assets = AssetService(database, storage)
        val sites = SiteService(database)
        val siteId: UInt = runBlocking { MigrationService(database).migrate(); sites.create("Test", null, true).id }
    }

    @Test
    fun `page history is pruned to the wired per-site limit`() = runBlocking<Unit> {
        val env = Env("hist-page-prune")
        env.pages.pageRevisionLimit = { 3 } // keep 3 prior versions
        val page = env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "en", path = "p", title = "v0", content = "c0", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )
        // Six edits → six snapshots of the prior content, but only the newest 3 are kept.
        for (i in 1..6) env.pages.update(page.id, UpdatePageRequest(content = "c$i"), updatedBy = null)

        val revs = env.pages.revisions(page.id)
        assertEquals(3, revs.size, "trimmed to the limit")
        // The kept revisions are the most recent snapshots (the pre-update content c3, c4, c5), and
        // revision numbers stayed unique/monotonic despite pruning (no count-based collision).
        assertEquals(listOf("c5", "c4", "c3"), revs.map { it.content })
        assertEquals(revs.map { it.revisionNumber }, revs.map { it.revisionNumber }.distinct())
    }

    @Test
    fun `unlimited when no limit is wired`() = runBlocking<Unit> {
        val env = Env("hist-page-unbounded")
        val page = env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "en", path = "p", title = "v0", content = "c0", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )
        for (i in 1..5) env.pages.update(page.id, UpdatePageRequest(content = "c$i"), updatedBy = null)
        assertEquals(5, env.pages.revisions(page.id).size, "no provider → history unbounded")
    }

    @Test
    fun `purge removes page and asset revisions older than the cutoff`() = runBlocking<Unit> {
        val env = Env("hist-purge")
        val page = env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "en", path = "p", title = "v0", content = "c0", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )
        repeat(3) { env.pages.update(page.id, UpdatePageRequest(content = "c${it + 1}"), updatedBy = null) }
        assertEquals(3, env.pages.revisions(page.id).size)

        val temp = env.assets.newTempFile(); Files.write(temp, pngBytes)
        val asset = env.assets.create(env.siteId, "en", "a.png", "a.png", "image/png", pngBytes.size.toLong(), temp, null)
        repeat(2) {
            val t = env.assets.newTempFile(); Files.write(t, pngBytes + byteArrayOf(it.toByte()))
            env.assets.replace(asset.id, "image/png", 9L, "a.png", t, null, maxVersions = 50)
        }
        val revBefore = env.assets.revisions(asset.id)
        assertEquals(2, revBefore.size)
        val revFiles = revBefore.map { env.assets.revFileForId(it.id) }
        assertTrue(revFiles.all { Files.exists(it) }, "revision bytes on disk before purge")

        // "All time" purge (Long.MAX_VALUE cutoff) clears every revision but keeps live content/bytes.
        val purgedPages = env.pages.purgeRevisionsOlderThan(env.siteId, Long.MAX_VALUE)
        val purgedAssets = env.assets.purgeRevisionsOlderThan(env.siteId, Long.MAX_VALUE)
        assertEquals(3, purgedPages)
        assertEquals(2, purgedAssets)
        assertTrue(env.pages.revisions(page.id).isEmpty())
        assertTrue(env.assets.revisions(asset.id).isEmpty())
        assertTrue(revFiles.none { Files.exists(it) }, "revision bytes removed from disk")
        // Live content survives the purge.
        assertEquals("c3", env.pages.findById(page.id)!!.content)
        assertTrue(Files.exists(env.assets.fileForId(asset.id)), "current asset bytes untouched")
    }
}
