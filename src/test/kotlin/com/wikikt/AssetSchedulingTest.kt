package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.service.AssetService
import com.wikikt.service.MigrationService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssetSchedulingTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private fun pngWith(tag: Int) = png + byteArrayOf(tag.toByte())

    private suspend fun setup(name: String): Triple<AssetService, java.nio.file.Path, UInt> {
        val storage = Files.createTempDirectory("wikikt-sched-$name")
        Files.createDirectories(storage.resolve("tmp"))
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-assetsched-$name;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        return Triple(AssetService(database, storage), storage, siteId)
    }

    @Test
    fun `scheduled replacement installs on time, archives, and cleans pending`() = runBlocking {
        val (assets, storage, siteId) = setup("ok")
        val t0 = assets.newTempFile(); Files.write(t0, pngWith(10))
        val asset = assets.create(siteId, "en", "img/x.png", "x.png", "image/png", 9, t0, null)

        val pendingTemp = assets.newTempFile(); Files.write(pendingTemp, pngWith(20))
        assets.schedule(asset.id, "image/png", 9, "x.png", pendingTemp, publishAt = 1_000L, by = null)
        assertTrue(Files.exists(assets.pendingFileForId(asset.id)), "pending stored in its own subtree")
        assertFalse(Files.exists(pendingTemp))

        assertEquals(0, assets.promoteScheduledReplacements(500L, setOf("image/png")) { _ -> 5 }, "not due")
        assertEquals(1, assets.promoteScheduledReplacements(2_000L, setOf("image/png")) { _ -> 5 }, "due → installed")
        assertContentEquals(pngWith(20), Files.readAllBytes(assets.fileForId(asset.id)))
        assertEquals(1, assets.revisions(asset.id).size, "old file archived as a version")
        assertNull(assets.pendingFor(asset.id))
        assertFalse(Files.exists(assets.pendingFileForId(asset.id)))

        storage.toFile().deleteRecursively()
        Unit
    }

    @Test
    fun `corrupted or disallowed pending is dropped, and discard cleans up`() = runBlocking {
        val (assets, storage, siteId) = setup("bad")
        val t0 = assets.newTempFile(); Files.write(t0, pngWith(1))
        val asset = assets.create(siteId, "en", "img/y.png", "y.png", "image/png", 9, t0, null)

        // Pending bytes are not a valid image → re-validation at promotion drops it (no retry loop).
        val bad = assets.newTempFile(); Files.write(bad, "<html>not an image".toByteArray())
        assets.schedule(asset.id, "image/png", 18, "y.png", bad, publishAt = 1_000L, by = null)
        assertEquals(0, assets.promoteScheduledReplacements(2_000L, setOf("image/png")) { _ -> 5 })
        assertNull(assets.pendingFor(asset.id), "invalid pending dropped")
        assertFalse(Files.exists(assets.pendingFileForId(asset.id)))

        // Discard removes a pending row + file.
        val good = assets.newTempFile(); Files.write(good, pngWith(2))
        assets.schedule(asset.id, "image/png", 9, "y.png", good, publishAt = 1_000L, by = null)
        assertTrue(assets.discardPending(asset.id))
        assertNull(assets.pendingFor(asset.id))
        assertFalse(Files.exists(assets.pendingFileForId(asset.id)))

        storage.toFile().deleteRecursively()
        Unit
    }
}
