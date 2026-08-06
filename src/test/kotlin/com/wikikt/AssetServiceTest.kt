package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.model.AssetRef
import com.wikikt.model.normalizeAssetPath
import com.wikikt.model.slugFilename
import com.wikikt.service.AssetService
import com.wikikt.service.MigrationService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssetServiceTest {
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Test
    fun `stores file, resolves with fallback, scans references, deletes`() = runBlocking {
        val storage = Files.createTempDirectory("wikikt-assets")
        Files.createDirectories(storage.resolve("tmp"))
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-asset-test;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val assets = AssetService(database, storage)

        val temp = assets.newTempFile()
        Files.write(temp, pngBytes)
        val asset = assets.create(siteId, "en", "images/logo.png", "logo.png", "image/png", pngBytes.size.toLong(), temp, null)

        // Stored on disk and findable by (locale, path)
        assertTrue(Files.exists(assets.fileForId(asset.id)), "bytes moved into storage")
        assertFalse(Files.exists(temp), "temp consumed by the move")
        assertEquals(asset.id, assets.findByLocaleAndPath(siteId, "en", "images/logo.png")!!.id)

        // Locale fallback: en is default; a fr request falls back when enabled, not otherwise
        assertEquals(asset.id, assets.resolve(siteId, "fr", "images/logo.png", fallback = true, defaultLocale = "en")!!.id)
        assertNull(assets.resolve(siteId, "fr", "images/logo.png", fallback = false, defaultLocale = "en"))

        // Reference scan: default-omitted and explicit-default URLs collapse; code refs ignored
        val a = assets.referencedAssetPaths("![x](/images/logo.png)", "en")
        val b = assets.referencedAssetPaths("![x](/en/images/logo.png)", "en")
        assertEquals(a, b)
        assertEquals(setOf(AssetRef("en", "images/logo.png")), a)
        assertTrue(assets.referencedAssetPaths("`![x](/images/logo.png)`", "en").isEmpty(), "code-span refs not counted")
        assertTrue(AssetRef("de", "x.png") in assets.referencedAssetPaths("![a](/de/x.png)", "en"), "explicit non-default locale")
        assertTrue(AssetRef("en", "p.png") in assets.referencedAssetPaths("<img src=\"/p.png\">", "en"), "raw HTML src")
        // Percent-encoded URLs serve fine (the router decodes each path segment), so the scan decodes
        // the same way: an encoded reference must match the stored, decoded path.
        assertEquals(
            setOf(AssetRef("en", "images/café.png")),
            assets.referencedAssetPaths("![x](/images/caf%C3%A9.png)", "en"),
            "percent-encoded unicode decodes to the stored path",
        )
        assertEquals(
            setOf(AssetRef("en", "images/my-pic.png")),
            assets.referencedAssetPaths("![x](/images/my%2Dpic.png)", "en"),
            "percent-encoded ASCII decodes to the stored path",
        )
        assertEquals(
            setOf(AssetRef("en", "images/50%.png")),
            assets.referencedAssetPaths("![x](/images/50%.png)", "en"),
            "a segment that fails to decode is kept raw (matches nothing, same as at serve time)",
        )

        // Delete removes row and file
        assertTrue(assets.delete(asset.id))
        assertFalse(Files.exists(assets.fileForId(asset.id)))
        assertNull(assets.findById(asset.id))

        storage.toFile().deleteRecursively()
        Unit
    }

    @Test
    fun `replace archives versions, prunes to max, and restore brings one back`() = runBlocking {
        val storage = Files.createTempDirectory("wikikt-assets-rev")
        Files.createDirectories(storage.resolve("tmp"))
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-asset-rev-test;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val assets = AssetService(database, storage)
        fun bytes(tag: Int) = pngBytes + byteArrayOf(tag.toByte())

        val t0 = assets.newTempFile(); Files.write(t0, bytes(0))
        val asset = assets.create(siteId, "en", "img/x.png", "x.png", "image/png", 9, t0, null)

        // Three replacements with a cap of 2 versions.
        for (tag in 1..3) {
            val t = assets.newTempFile(); Files.write(t, bytes(tag))
            assets.replace(asset.id, "image/png", 9, "x.png", t, null, maxVersions = 2)
        }

        val revs = assets.revisions(asset.id)
        assertEquals(2, revs.size, "history pruned to the cap")
        kotlin.test.assertContentEquals(bytes(3), Files.readAllBytes(assets.fileForId(asset.id)), "current is the latest upload")

        // Restore the oldest kept version; its bytes become current.
        val oldestKept = revs.minByOrNull { it.versionNumber }!!
        assets.restore(asset.id, oldestKept.id, null, maxVersions = 2)
        kotlin.test.assertContentEquals(bytes(1), Files.readAllBytes(assets.fileForId(asset.id)), "restore brought the chosen version back")

        // Deleting the asset removes its revision files too.
        assets.delete(asset.id)
        assertTrue(assets.revisions(asset.id).isEmpty())

        storage.toFile().deleteRecursively()
        Unit
    }

    @Test
    fun `asset path normalizer and filename slug are strict`() {
        assertFailsWith<IllegalArgumentException> { normalizeAssetPath("../etc/passwd") }
        assertFailsWith<IllegalArgumentException> { normalizeAssetPath("a\\b") }
        assertFailsWith<IllegalArgumentException> { normalizeAssetPath("   ") }
        assertEquals("images/logo.png", normalizeAssetPath("/images//logo.png/"))
        assertEquals("my-file.png", slugFilename("My File!.PNG"))
        assertEquals("logo.jpeg", slugFilename("../../logo.jpeg"))
    }
}
