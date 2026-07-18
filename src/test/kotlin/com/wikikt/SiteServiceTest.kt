package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.model.CreatePageRequest
import com.wikikt.model.NavItemInput
import com.wikikt.service.AssetService
import com.wikikt.service.ContentImporter
import com.wikikt.service.FragmentService
import com.wikikt.service.GitSyncService
import com.wikikt.service.MigrationService
import com.wikikt.service.NavService
import com.wikikt.service.PageService
import com.wikikt.service.SettingsService
import com.wikikt.service.SiteDeleteResult
import com.wikikt.service.SiteService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SiteServiceTest {
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private class Env(name: String) {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-$name;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        val storage: Path = Files.createTempDirectory("wikikt-$name-assets").also { Files.createDirectories(it.resolve("tmp")) }
        val pages = PageService(database)
        val assets = AssetService(database, storage)
        val fragments = FragmentService(database)
        val nav = NavService(database)
        val settings = SettingsService(database)
        val gitSyncDir: Path = Files.createTempDirectory("wikikt-$name-gitsync")
        val sites = SiteService(database)
        val importer = ContentImporter(pages, assets, maxAssetVersionsFor = { _ -> 5 }, allowedAssetMimeTypes = setOf("image/png"))
        val gitSync = GitSyncService(settings, sites, pages, assets, gitSyncDir, importer, defaultLocale = "en")

        init {
            sites.wireCascade(pages, assets, fragments, nav, settings, gitSync)
            runBlocking { MigrationService(database).migrate() }
        }

        /** Stand in for a site's local clone: a dir with a file, as a real sync would leave behind. */
        fun seedCloneDir(siteId: UInt): Path {
            val dir = gitSyncDir.resolve("site-$siteId")
            Files.createDirectories(dir.resolve(".git"))
            Files.writeString(dir.resolve(".git").resolve("config"), "url = https://user:secret-token@host/repo.git")
            return dir
        }
    }

    @Test
    fun `deleting a site cascades away all of its content and files`() = runBlocking<Unit> {
        val env = Env("site-cascade")
        val keep = env.sites.create("Main", null, isCatchAll = true).id
        val doomed = env.sites.create("Docs", "docs.example.com", isCatchAll = false).id

        // Content in the site to be deleted.
        val page = env.pages.create(
            doomed,
            CreatePageRequest(locale = "en", path = "home", title = "Doomed", content = "gone soon", contentFormat = "MARKDOWN", tags = listOf("t")),
            updatedBy = null,
        )
        val temp = env.assets.newTempFile()
        Files.write(temp, pngBytes)
        val asset = env.assets.create(doomed, "en", "img/logo.png", "logo.png", "image/png", pngBytes.size.toLong(), temp, null)
        val assetFile = env.assets.fileForId(asset.id)
        assertTrue(Files.exists(assetFile), "asset bytes written before delete")
        env.fragments.create(doomed, "en", "note", "Note", "frag body", updatedBy = null)
        env.nav.createMenu(doomed, "", listOf(NavItemInput(isHeader = false, label = "Home", icon = null, target = "/en/home")))
        env.settings.set(doomed, SettingsService.SITE_NAME, "Docs")
        val doomedClone = env.seedCloneDir(doomed) // local git-sync clone, credential and all

        // Content in the site that must survive.
        env.pages.create(
            keep,
            CreatePageRequest(locale = "en", path = "home", title = "Keeper", content = "stays", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )
        val keepClone = env.seedCloneDir(keep)

        assertEquals(SiteDeleteResult.DELETED, env.sites.delete(doomed))

        // Site and every kind of content it owned is gone, including on-disk bytes.
        assertNull(env.sites.byId(doomed))
        assertTrue(env.pages.list(doomed).isEmpty())
        assertNull(env.pages.findById(page.id))
        assertTrue(env.assets.list(doomed).isEmpty())
        assertNull(env.assets.findById(asset.id))
        assertTrue(!Files.exists(assetFile), "asset bytes removed from disk")
        assertTrue(env.fragments.list(doomed).isEmpty())
        assertTrue(env.nav.listMenus(doomed).isEmpty())
        assertNull(env.settings.get(doomed, SettingsService.SITE_NAME))
        assertTrue(!Files.exists(doomedClone), "git-sync clone (and its embedded credential) removed from disk")

        // The other site is untouched — content and its own clone both survive.
        assertEquals("stays", env.pages.findByLocaleAndPath(keep, "en", "home")!!.content)
        assertTrue(Files.exists(keepClone), "the surviving site's clone is left alone")
    }

    @Test
    fun `the catch-all site cannot be deleted`() = runBlocking<Unit> {
        val env = Env("site-catchall")
        val main = env.sites.create("Main", null, isCatchAll = true).id
        assertEquals(SiteDeleteResult.IS_CATCHALL, env.sites.delete(main))
        assertEquals(SiteDeleteResult.NOT_FOUND, env.sites.delete(9999u))
    }
}
