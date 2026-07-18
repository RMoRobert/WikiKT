package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.model.CreatePageRequest
import com.wikikt.model.NavItemInput
import com.wikikt.service.AssetService
import com.wikikt.service.BackupService
import com.wikikt.service.ContentImporter
import com.wikikt.service.FragmentService
import com.wikikt.service.MigrationService
import com.wikikt.service.NavService
import com.wikikt.service.PageService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import com.wikikt.service.BudgetedInputStream
import com.wikikt.service.DecompressionBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackupServiceTest {
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
        val users = com.wikikt.service.UserService(database)
        val groups = com.wikikt.service.GroupService(database)
        val apiKeys = com.wikikt.service.ApiKeyService(database)
        val settings = com.wikikt.service.SettingsService(database)
        val searchIndex = com.wikikt.service.SearchIndexService(pages, fragments, "en")
        val importer = ContentImporter(pages, assets, maxAssetVersionsFor = { _ -> 5 }, allowedAssetMimeTypes = setOf("image/png"))
        val sites = com.wikikt.service.SiteService(database)
        val backup = BackupService(database, sites, pages, assets, fragments, nav, importer, settings, searchIndex, storage)

        val siteId: UInt = runBlocking {
            MigrationService(database).migrate()
            sites.create("Test site", null, isCatchAll = true).id
        }
    }

    @Test
    fun `decompression budget aborts once the read exceeds the cap`() {
        // A zip bomb decompresses far beyond the archive's compressed size; the budget charges every
        // decompressed byte and throws once the cap is passed, so the inflate never completes.
        val budget = DecompressionBudget(maxBytes = 100)
        val stream = BudgetedInputStream("x".repeat(10_000).byteInputStream(), budget)
        assertFailsWith<IllegalArgumentException> { stream.use { it.readBytes() } }
    }

    @Test
    fun `decompression budget passes reads within the cap`() {
        val budget = DecompressionBudget(maxBytes = 10_000)
        val bytes = BudgetedInputStream("hello".byteInputStream(), budget).use { it.readBytes() }
        assertEquals("hello", String(bytes))
    }

    @Test
    fun `content backup round-trips into a fresh wiki`() = runBlocking<Unit> {
        val source = Env("backup-src")
        source.pages.create(
            source.siteId,
            CreatePageRequest(
                locale = "en", path = "guides/setup", title = "Setup Guide", description = "How to set up",
                content = "Step one.", contentFormat = "MARKDOWN", published = true, tags = listOf("guide"),
                // metaRobots rides in the front-matter (a WikiKT extension), so content backup carries it;
                // customCss/customJs are DB-only and intentionally NOT part of a content-scope backup.
                metaRobots = "noindex,nofollow",
            ),
            updatedBy = null,
        )
        source.pages.create(
            source.siteId,
            CreatePageRequest(
                locale = "de", path = "hallo", title = "Hallo", content = "<p>Welt</p>", contentFormat = "HTML",
            ),
            updatedBy = null,
        )
        val temp = source.assets.newTempFile()
        Files.write(temp, pngBytes)
        source.assets.create(source.siteId, "en", "img/logo.png", "logo.png", "image/png", pngBytes.size.toLong(), temp, null)
        source.fragments.create(source.siteId, "en", "footer-note", "Footer note", "shared text", updatedBy = null)
        source.nav.createMenu(source.siteId, "", listOf(NavItemInput(isHeader = false, label = "Home", icon = "home", target = "/en/home")))

        // Write the backup zip.
        val zipPath = Files.createTempFile("wikikt-backup-test", ".zip")
        Files.newOutputStream(zipPath).use { source.backup.writeContentBackup(source.siteId, it) }

        // The archive is laid out as documented.
        val names = ZipFile(zipPath.toFile()).use { z -> z.entries().toList().map { it.name }.sorted() }
        assertEquals(
            listOf(
                "assets/en/img/logo.png",
                "content/de/hallo.html",
                "content/en/guides/setup.md",
                "fragments.json",
                "manifest.json",
                "navigation.json",
            ),
            names,
        )

        // Restore into a completely fresh wiki.
        val target = Env("backup-dst")
        val summary = target.backup.restoreContent(target.siteId, zipPath)
        assertEquals(2, summary.pagesImported, summary.message())
        assertEquals(1, summary.assetsImported)
        assertEquals(1, summary.fragmentsImported)
        assertEquals(1, summary.menusImported)

        val page = target.pages.findByLocaleAndPath(target.siteId, "en", "guides/setup")
        assertNotNull(page)
        assertEquals("Setup Guide", page.title)
        assertEquals("How to set up", page.description)
        assertEquals(listOf("guide"), page.tags)
        assertEquals("Step one.", page.content)
        assertEquals("noindex,nofollow", page.metaRobots, "metaRobots round-trips via content-backup front-matter")
        val html = target.pages.findByLocaleAndPath(target.siteId, "de", "hallo")
        assertNotNull(html)
        assertEquals(com.wikikt.db.ContentFormat.HTML, html.contentFormat)
        assertEquals("<p>Welt</p>", html.content)
        val asset = target.assets.findByLocaleAndPath(target.siteId, "en", "img/logo.png")
        assertNotNull(asset)
        assertTrue(Files.readAllBytes(target.assets.fileForId(asset.id)).contentEquals(pngBytes))
        assertEquals("shared text", target.fragments.list(target.siteId).single().content)
        assertEquals(1, target.nav.listMenus(target.siteId).size)

        // Restoring the same backup again is a no-op (no revision spam, nothing re-imported).
        val again = target.backup.restoreContent(target.siteId, zipPath)
        assertEquals(0, again.pagesImported, again.message())
        assertEquals(0, again.assetsImported)
        assertEquals(0, again.fragmentsImported)
        assertEquals(0, again.menusImported, "unchanged menus are not re-imported")
        assertTrue(target.pages.revisions(page.id).isEmpty(), "unchanged restore creates no revisions")

        // Restore never deletes: content that exists only in the target survives.
        val localOnly = target.pages.create(
            target.siteId,
            CreatePageRequest(locale = "en", path = "local-only", title = "Local", content = "x", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )
        target.backup.restoreContent(target.siteId, zipPath)
        assertNotNull(target.pages.findById(localOnly.id), "restore must not delete existing content")
    }

    @Test
    fun `full backup restores an identical site onto a fresh database`() = runBlocking<Unit> {
        val source = Env("full-src")
        // Populate every kind of state: user + group + membership, authored page with a revision,
        // per-group ACL, asset with a replaced version (archived bytes), fragment, nav, setting, API key.
        val group = source.groups.create(com.wikikt.model.CreateGroupRequest(name = "editors", permissions = setOf(com.wikikt.service.AccessResolver.Perm.MANAGE_NAVIGATION)))
        val author = source.users.create(
            com.wikikt.model.CreateUserRequest("alice", "Str0ngPass!word", "alice@example.com", listOf(group.id.toString())),
        )
        val page = source.pages.create(
            source.siteId,
            CreatePageRequest(
                locale = "en", path = "docs/guide", title = "Guide v1", content = "first", contentFormat = "MARKDOWN",
                tags = listOf("docs"), viewAcl = com.wikikt.model.PageAclDto(groupIds = listOf(group.id.toString())),
                // DB-only page attributes (not in the WikiJS front-matter) — a full backup must still carry them.
                metaRobots = "noindex,nofollow", customCss = ".x{color:red}", customJs = "<script>ran()</script>",
            ),
            updatedBy = author.id,
        )
        source.pages.update(page.id, com.wikikt.model.UpdatePageRequest(title = "Guide v2", content = "second"), updatedBy = author.id)
        val t1 = source.assets.newTempFile()
        Files.write(t1, pngBytes)
        val asset = source.assets.create(source.siteId, "en", "img/pic.png", "pic.png", "image/png", pngBytes.size.toLong(), t1, author.id)
        val newBytes = pngBytes + byteArrayOf(1, 2, 3)
        val t2 = source.assets.newTempFile()
        Files.write(t2, newBytes)
        source.assets.replace(asset.id, "image/png", newBytes.size.toLong(), "pic.png", t2, author.id, maxVersions = 5)
        source.fragments.create(source.siteId, "en", "note", "Note", "frag body", updatedBy = author.id)
        source.nav.createMenu(source.siteId, "", listOf(NavItemInput(isHeader = false, label = "Home", icon = null, target = "/en/home")))
        source.settings.set(source.siteId, com.wikikt.service.SettingsService.SITE_NAME, "Backed Up Wiki")
        source.apiKeys.create(author.id, "ci-key", ttlMillis = 86_400_000L)

        val zipPath = Files.createTempFile("wikikt-full-test", ".zip")
        Files.newOutputStream(zipPath).use { source.backup.writeFullBackup(source.siteId, it) }

        // Restore into a wiki that has DIFFERENT pre-existing data — it must be replaced.
        val target = Env("full-dst")
        target.pages.create(
            target.siteId,
            CreatePageRequest(locale = "en", path = "stale", title = "Stale", content = "old", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )
        target.settings.set(target.siteId, com.wikikt.service.SettingsService.SITE_NAME, "Stale Wiki")

        // Full restore requires the explicit confirmation flag.
        val refused = runCatching { target.backup.restore(target.siteId, zipPath, allowFull = false) }
        assertTrue(refused.isFailure, "full restore without confirmation must be refused")

        val message = target.backup.restore(target.siteId, zipPath, allowFull = true)
        assertTrue(message.contains("Full restore complete"), message)

        // The full restore replaced the sites table with fresh ids; resolve the restored site.
        target.sites.invalidateCache()
        val restoredSiteId = target.sites.catchAll()!!.id

        // Pre-existing data replaced.
        assertEquals(null, target.pages.findByLocaleAndPath(restoredSiteId, "en", "stale"), "stale page replaced")
        assertEquals("Backed Up Wiki", target.settings.get(restoredSiteId, com.wikikt.service.SettingsService.SITE_NAME))

        // Users, groups, membership, attribution.
        val alice = target.users.list().single { it.username == "alice" }
        assertEquals("alice@example.com", alice.email)
        val editors = target.groups.list().single { it.name == "editors" }
        assertTrue(target.groups.userIdsInGroup(editors.id).contains(alice.id), "membership restored")
        val restoredPage = target.pages.findByLocaleAndPath(restoredSiteId, "en", "docs/guide")
        assertNotNull(restoredPage)
        assertEquals("Guide v2", restoredPage.title)
        assertEquals("second", restoredPage.content)
        assertEquals(listOf("docs"), restoredPage.tags)
        // DB-only attributes survive a full backup (these are NOT in the front-matter; the DB dump carries them).
        assertEquals("noindex,nofollow", restoredPage.metaRobots, "metaRobots survives full backup")
        assertEquals(".x{color:red}", restoredPage.customCss, "customCss survives full backup")
        assertEquals("<script>ran()</script>", restoredPage.customJs, "customJs survives full backup")
        assertEquals(alice.id, restoredPage.updatedBy, "attribution preserved via id remap")
        assertEquals(setOf(editors.id), target.pages.viewAcl(restoredPage.id).groupIds, "ACL remapped")
        // Revision history restored.
        val revs = target.pages.revisions(restoredPage.id)
        assertEquals(1, revs.size)
        assertEquals("first", revs.single().content)
        // Asset bytes (current + archived revision file).
        val restoredAsset = target.assets.findByLocaleAndPath(restoredSiteId, "en", "img/pic.png")
        assertNotNull(restoredAsset)
        assertTrue(Files.readAllBytes(target.assets.fileForId(restoredAsset.id)).contentEquals(newBytes), "current bytes")
        val revRecord = target.assets.revisions(restoredAsset.id).single()
        assertTrue(Files.readAllBytes(target.assets.revFileForId(revRecord.id)).contentEquals(pngBytes), "archived bytes")
        // Fragment, nav, API key.
        assertEquals("frag body", target.fragments.list(restoredSiteId).single().content)
        assertEquals(1, target.nav.listMenus(restoredSiteId).size)
        assertEquals("ci-key", target.apiKeys.list().single().name)
        // Search works against the restored content (index was rebuilt).
        assertTrue(target.pages.search(restoredSiteId, "second", locale = null).isNotEmpty(), "search index rebuilt")
        // Auto-increment ids keep working after the remapped inserts.
        val fresh = target.pages.create(
            restoredSiteId,
            CreatePageRequest(locale = "en", path = "after-restore", title = "New", content = "x", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )
        assertNotNull(target.pages.findById(fresh.id), "new inserts work after restore")
    }

    @Test
    fun `full backup captures every site and restores them independently`() = runBlocking<Unit> {
        val source = Env("full-multi-src")
        val siteA = source.siteId // the catch-all created by Env
        val siteB = source.sites.create("Docs", "docs.example.com", isCatchAll = false).id

        source.pages.create(
            siteA,
            CreatePageRequest(locale = "en", path = "home", title = "A home", content = "alpha", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )
        source.pages.create(
            siteB,
            CreatePageRequest(locale = "en", path = "home", title = "B home", content = "bravo", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )
        // Same locale+path on both sites, but different bytes — proves asset files are keyed by row id,
        // so one site's bytes can't clobber the other's on restore.
        val bytesA = pngBytes
        val bytesB = pngBytes + byteArrayOf(9, 9, 9)
        val ta = source.assets.newTempFile(); Files.write(ta, bytesA)
        source.assets.create(siteA, "en", "img/logo.png", "logo.png", "image/png", bytesA.size.toLong(), ta, null)
        val tb = source.assets.newTempFile(); Files.write(tb, bytesB)
        source.assets.create(siteB, "en", "img/logo.png", "logo.png", "image/png", bytesB.size.toLong(), tb, null)
        source.settings.set(siteB, com.wikikt.service.SettingsService.SITE_NAME, "Docs Site")

        // Take the full backup while "managing" site A; it must still capture site B in full.
        val zip = Files.createTempFile("wikikt-multisite", ".zip")
        Files.newOutputStream(zip).use { source.backup.writeFullBackup(siteA, it) }

        val target = Env("full-multi-dst")
        target.backup.restore(target.siteId, zip, allowFull = true)
        target.sites.invalidateCache()

        assertEquals(2, target.sites.all().size, "both sites restored")
        val rA = target.sites.catchAll()!!.id
        val rB = target.sites.byHostname("docs.example.com")!!.id

        assertEquals("alpha", target.pages.findByLocaleAndPath(rA, "en", "home")!!.content)
        assertEquals("bravo", target.pages.findByLocaleAndPath(rB, "en", "home")!!.content)
        assertEquals("Docs Site", target.settings.get(rB, com.wikikt.service.SettingsService.SITE_NAME))

        val aAsset = target.assets.findByLocaleAndPath(rA, "en", "img/logo.png")!!
        val bAsset = target.assets.findByLocaleAndPath(rB, "en", "img/logo.png")!!
        assertTrue(Files.readAllBytes(target.assets.fileForId(aAsset.id)).contentEquals(bytesA), "site A bytes")
        assertTrue(Files.readAllBytes(target.assets.fileForId(bAsset.id)).contentEquals(bytesB), "site B bytes not clobbered")
    }

    @Test
    fun `an unencrypted full backup omits plaintext credentials entirely`() = runBlocking<Unit> {
        val s = com.wikikt.service.SettingsService
        val source = Env("secrets-omit-src")
        source.settings.set(source.siteId, s.SITE_NAME, "Kept Wiki")
        source.settings.set(source.siteId, s.MAIL_SMTP_PASSWORD, "smtp-secret-abc")
        source.settings.set(source.siteId, s.GIT_SYNC_TOKEN, "ghp_tok_xyz")

        val zip = Files.createTempFile("wikikt-secrets-omit", ".zip")
        Files.newOutputStream(zip).use { source.backup.writeFullBackup(source.siteId, it, secretsPassword = null) }

        // Not encrypted (it's a plain ZIP), and the credentials appear nowhere in the archive bytes.
        assertTrue(!com.wikikt.service.BackupCrypto.isEncryptedBackup(zip), "no password → unencrypted backup")
        val raw = String(Files.readAllBytes(zip), Charsets.ISO_8859_1)
        assertTrue("smtp-secret-abc" !in raw, "SMTP password must not be in the backup")
        assertTrue("ghp_tok_xyz" !in raw, "git token must not be in the backup")

        // Restore (no password needed): the credentials come back unset; other settings restore normally.
        val target = Env("secrets-omit-dst")
        target.backup.restore(target.siteId, zip, allowFull = true)
        target.sites.invalidateCache()
        val rId = target.sites.catchAll()!!.id
        assertEquals("Kept Wiki", target.settings.get(rId, s.SITE_NAME))
        assertTrue(target.settings.get(rId, s.MAIL_SMTP_PASSWORD).isNullOrEmpty(), "SMTP password not restored")
        assertTrue(target.settings.get(rId, s.GIT_SYNC_TOKEN).isNullOrEmpty(), "git token not restored")
    }

    @Test
    fun `a password encrypts the whole backup and it restores only with the right password`() = runBlocking<Unit> {
        val s = com.wikikt.service.SettingsService
        val source = Env("secrets-enc-src")
        source.pages.create(
            source.siteId,
            CreatePageRequest(locale = "en", path = "secret-page", title = "TopSecretTitle", content = "classified-body", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )
        source.settings.set(source.siteId, s.MAIL_SMTP_PASSWORD, "smtp-secret-abc")
        source.settings.set(source.siteId, s.GIT_SYNC_TOKEN, "ghp_tok_xyz")

        val zip = Files.createTempFile("wikikt-secrets-enc", ".zip")
        Files.newOutputStream(zip).use { source.backup.writeFullBackup(source.siteId, it, secretsPassword = "correct horse") }

        // The whole container is encrypted: nothing — content or credentials — is readable in the clear.
        assertTrue(com.wikikt.service.BackupCrypto.isEncryptedBackup(zip), "a password → encrypted backup")
        val raw = String(Files.readAllBytes(zip), Charsets.ISO_8859_1)
        assertTrue("smtp-secret-abc" !in raw, "SMTP password stays encrypted")
        assertTrue("ghp_tok_xyz" !in raw, "git token stays encrypted")
        assertTrue("TopSecretTitle" !in raw && "classified-body" !in raw, "page content is encrypted too")
        assertTrue("manifest" !in raw, "even the archive structure is opaque")

        // No password → refused outright (can't even be read).
        val noPass = Env("secrets-enc-nopass")
        val refused = runCatching { noPass.backup.restore(noPass.siteId, zip, allowFull = true) }
        assertTrue(refused.isFailure && refused.exceptionOrNull()!!.message!!.contains("encrypted", ignoreCase = true), "no password refused")

        // Wrong password → refused, nothing restored.
        val wrong = Env("secrets-enc-wrong")
        val wrongResult = runCatching { wrong.backup.restore(wrong.siteId, zip, allowFull = true, secretsPassword = "nope") }
        assertTrue(wrongResult.isFailure && wrongResult.exceptionOrNull()!!.message!!.contains("decrypt", ignoreCase = true), "wrong password refused")

        // Correct password → the whole backup, credentials included, restores.
        val right = Env("secrets-enc-right")
        val rightMsg = right.backup.restore(right.siteId, zip, allowFull = true, secretsPassword = "correct horse")
        assertTrue(rightMsg.contains("Full restore complete"), rightMsg)
        right.sites.invalidateCache()
        val rId = right.sites.catchAll()!!.id
        assertEquals("classified-body", right.pages.findByLocaleAndPath(rId, "en", "secret-page")!!.content)
        assertEquals("smtp-secret-abc", right.settings.get(rId, s.MAIL_SMTP_PASSWORD))
        assertEquals("ghp_tok_xyz", right.settings.get(rId, s.GIT_SYNC_TOKEN))
    }

    @Test
    fun `full restore refuses a schema version mismatch`() = runBlocking<Unit> {
        val env = Env("full-schema")
        val zipPath = Files.createTempFile("wikikt-badschema", ".zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write(
                """{"format":"wikikt-backup","scope":"full","wikiktVersion":"x","schemaVersion":999,"createdAt":0}"""
                    .toByteArray(),
            )
            zip.closeEntry()
        }
        val result = runCatching { env.backup.restore(env.siteId, zipPath, allowFull = true) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("schema version"), "clear schema mismatch error")
    }

    @Test
    fun `restore rejects archives that are not wikikt backups`() = runBlocking<Unit> {
        val env = Env("backup-bad")
        val zipPath = Files.createTempFile("wikikt-notabackup", ".zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("random.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        val result = runCatching { env.backup.restoreContent(env.siteId, zipPath) }
        assertTrue(result.isFailure, "archive without a manifest must be rejected")
        assertTrue(result.exceptionOrNull()!!.message!!.contains("manifest"), "clear error message")
    }
}
