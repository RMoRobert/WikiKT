package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.markdown.MarkdownRenderer
import com.wikikt.model.CreatePageRequest
import com.wikikt.model.InfoboxFieldDef
import com.wikikt.service.AssetService
import com.wikikt.service.FragmentService
import com.wikikt.service.InfoboxService
import com.wikikt.service.MigrationService
import com.wikikt.service.PageService
import com.wikikt.service.SettingsService
import com.wikikt.service.SiteService
import com.wikikt.service.WikiJsExportService
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The WikiJS-compatible content export. The assertions here pin the two things a migration back to
 * WikiJS 2.x depends on: the archive is *only* the tree its disk importer understands, and every
 * WikiKT-only construct in a page body has been resolved into something WikiJS renders.
 */
class WikiJsExportTest {
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
        val storage: Path = Files.createTempDirectory("wikikt-$name-assets")
            .also { Files.createDirectories(it.resolve("tmp")) }
        val pages = PageService(database)
        val assets = AssetService(database, storage)
        val fragments = FragmentService(database)
        val settings = SettingsService(database)
        val infobox = InfoboxService(database, MarkdownRenderer(), settings)
        val sites = SiteService(database)
        val export = WikiJsExportService(pages, assets, fragments, infobox, defaultLocale = "en")

        val siteId: UInt = runBlocking {
            MigrationService(database).migrate()
            sites.create("Test site", null, isCatchAll = true).id
        }

        suspend fun zip(options: WikiJsExportService.Options = WikiJsExportService.Options()): Map<String, ByteArray> {
            val file = Files.createTempFile("wikikt-wikijs-test", ".zip")
            Files.newOutputStream(file).use { export.write(siteId, it, options) }
            return ZipFile(file.toFile()).use { z ->
                z.entries().toList().associate { it.name to z.getInputStream(it).readBytes() }
            }
        }
    }

    private fun Map<String, ByteArray>.text(name: String): String =
        String(assertNotNull(this[name], "archive has no entry '$name' (has: ${keys.sorted()})"), StandardCharsets.UTF_8)

    @Test
    fun `archive holds only the tree WikiJS imports, pages locale-prefixed`() = runBlocking<Unit> {
        val env = Env("wikijs-layout")
        env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "en", path = "guides/setup", title = "Setup", content = "Step one."),
            updatedBy = null,
        )
        env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "de", path = "hallo", title = "Hallo", content = "<p>Welt</p>", contentFormat = "HTML"),
            updatedBy = null,
        )
        val temp = env.assets.newTempFile()
        Files.write(temp, pngBytes)
        env.assets.create(env.siteId, "en", "img/logo.png", "logo.png", "image/png", pngBytes.size.toLong(), temp, null)

        val entries = env.zip()
        // No manifest.json, no fragments.json, no content/ or assets/ prefix: WikiJS's importer treats
        // every non-page file it walks as an asset, so anything extra would import as junk.
        assertEquals(listOf("de/hallo.html", "en/guides/setup.md", "en/img/logo.png"), entries.keys.sorted())
        assertTrue(entries.getValue("en/img/logo.png").contentEquals(pngBytes))
    }

    @Test
    fun `front-matter carries exactly the WikiJS keys`() = runBlocking<Unit> {
        val env = Env("wikijs-frontmatter")
        env.pages.create(
            env.siteId,
            CreatePageRequest(
                locale = "en", path = "guides/setup", title = "Setup: the basics", description = "How to",
                content = "Body.", tags = listOf("guide", "howto"),
                // A WikiKT-only key: it must not leak into a compatibility export.
                metaRobots = "noindex,nofollow",
            ),
            updatedBy = null,
        )
        val file = env.zip().text("en/guides/setup.md")
        val meta = file.substringAfter("---\n").substringBefore("\n---\n")
        assertEquals(
            listOf("title", "description", "published", "date", "tags", "editor", "dateCreated"),
            meta.lines().map { it.substringBefore(':') },
        )
        // A colon in a title would produce invalid YAML unquoted — WikiJS parses the block with js-yaml,
        // and a parse failure there silently drops every field.
        assertTrue(meta.contains("""title: "Setup: the basics""""), meta)
        // Tags stay ONE comma-joined string: WikiJS's importer calls .split(', ') on the parsed value,
        // and a YAML list would throw and drop the whole page.
        assertTrue(meta.contains("\ntags: guide, howto"), meta)
        assertTrue(meta.contains("\neditor: markdown"), meta)
        assertFalse(meta.contains("metaRobots"), meta)
        assertEquals("Body.", file.substringAfter("\n---\n\n"))
    }

    @Test
    fun `a tagless page writes a bare tags key`() = runBlocking<Unit> {
        val env = Env("wikijs-notags")
        env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "en", path = "plain", title = "Plain", content = "x"),
            updatedBy = null,
        )
        // `tags:` parses to null, which WikiJS reads as "no tags in this file". `tags: ""` would instead
        // import a single empty tag.
        assertTrue(env.zip().text("en/plain.md").contains("\ntags: \n"), "expected a bare tags key")
    }

    @Test
    fun `html pages get the comment front-matter block`() = runBlocking<Unit> {
        val env = Env("wikijs-html")
        env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "en", path = "raw", title = "Raw", content = "<p>Hi</p>", contentFormat = "HTML"),
            updatedBy = null,
        )
        val file = env.zip().text("en/raw.html")
        assertTrue(file.startsWith("<!--\ntitle: Raw\n"), file)
        assertTrue(file.contains("\neditor: code\n"), file)
        assertEquals("<p>Hi</p>", file.substringAfter("\n-->\n\n"))
    }

    @Test
    fun `fragments are expanded literally and icons become icon markup`() = runBlocking<Unit> {
        val env = Env("wikijs-fragments")
        env.fragments.create(env.siteId, "en", "support", "Support", "Email **support@example.com**.", updatedBy = null)
        env.pages.create(
            env.siteId,
            CreatePageRequest(
                locale = "en", path = "guides/help", title = "Help",
                content = "Intro.\n\n{{fragment:support}}\n\n:mdi-home: Home.\n\n" +
                    "Literal: `{{fragment:support}}` and `:mdi-home:`.",
            ),
            updatedBy = null,
        )
        val body = env.zip().text("en/guides/help.md").substringAfter("\n---\n\n")
        assertTrue(body.contains("Email **support@example.com**."), "fragment expanded inline:\n$body")
        assertFalse(body.contains("\n{{fragment:support}}"), "no unexpanded transclusion survives:\n$body")
        assertTrue(body.contains("""<i class="mdi mdi-home" aria-hidden="true"></i> Home."""), body)
        // Code spans are documentation, not markup: both forms survive untouched inside them.
        assertTrue(body.contains("Literal: `{{fragment:support}}` and `:mdi-home:`."), body)
    }

    @Test
    fun `asset links are pinned to the locale that serves them and {alt} is resolved`() = runBlocking<Unit> {
        val env = Env("wikijs-assets")
        val temp = env.assets.newTempFile()
        Files.write(temp, pngBytes)
        val asset = env.assets.create(env.siteId, "en", "img/logo.png", "logo.png", "image/png", pngBytes.size.toLong(), temp, null)
        env.assets.updateMeta(asset.id, altText = "The [company] logo", description = null)
        // A German page referencing the locale-relative path: WikiKT serves the `en` bytes via the
        // default-locale fallback, so the export must point at where it actually put that file.
        env.pages.create(
            env.siteId,
            CreatePageRequest(
                locale = "de", path = "willkommen", title = "Willkommen",
                content = "![{alt}](/img/logo.png)\n\n[Datei](/de/img/logo.png)\n\n" +
                    "<img src=\"/img/logo.png\" alt=\"{alt}\">\n\n[Seite](/de/andere-seite)",
            ),
            updatedBy = null,
        )
        val body = env.zip().text("de/willkommen.md").substringAfter("\n---\n\n")
        assertTrue(body.contains("![The \\[company\\] logo](/en/img/logo.png)"), body)
        assertTrue(body.contains("[Datei](/en/img/logo.png)"), body)
        assertTrue(body.contains("""<img src="/en/img/logo.png" alt="The [company] logo">"""), body)
        // A link to a page is not an asset reference and must be left exactly as authored.
        assertTrue(body.contains("[Seite](/de/andere-seite)"), body)
    }

    @Test
    fun `infobox modes - table, front-matter, omit`() = runBlocking<Unit> {
        val env = Env("wikijs-infobox")
        val templateId = env.infobox.createTemplate(
            env.siteId, slug = "app", name = "Application", description = null,
            fields = listOf(
                InfoboxFieldDef(name = "vendor", label = "Vendor"),
                InfoboxFieldDef(name = "official", label = "Official", type = "boolean"),
                InfoboxFieldDef.heading("Deployment"),
                InfoboxFieldDef(name = "targets", label = "Targets", type = "multi"),
                InfoboxFieldDef(name = "notes", label = "Notes"),
            ),
        )
        env.infobox.createPathRule(env.siteId, "apps/*", templateId)
        val json = """{"app":{"vendor":"Acme | Co","official":true,"targets":["Local","Cloud"]}}"""
        env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "en", path = "apps/widget", title = "Widget", content = "Prose.", infobox = json),
            updatedBy = null,
        )

        val tableFile = env.zip().text("en/apps/widget.md")
        // Table mode keeps the machine-readable copy too: folding the data in for a WikiJS reader
        // shouldn't cost the block that lets WikiKT read the infobox back.
        assertTrue(tableFile.contains("\ninfobox:\n  app:\n    vendor:"), tableFile)
        val table = tableFile.substringAfter("\n---\n\n")
        assertEquals(
            """
            ### Application

            | Field | Value |
            | --- | --- |
            | Vendor | Acme \| Co |
            | Official | Yes |
            | **Deployment** | |
            | Targets | Local, Cloud |

            Prose.
            """.trimIndent(),
            table,
        )
        // "notes" was never filled in, so it contributes no row.
        assertFalse(table.contains("Notes"), table)

        // Omit is the only mode that drops the data — no table, no front-matter block.
        val omitted = env.zip(WikiJsExportService.Options(infoboxMode = WikiJsExportService.InfoboxMode.OMIT))
            .text("en/apps/widget.md")
        assertFalse(omitted.contains("infobox:"), omitted)
        assertEquals("Prose.", omitted.substringAfter("\n---\n\n"))

        // Front-matter only: same block as table mode, but nothing added to the body.
        val kept = env.zip(WikiJsExportService.Options(infoboxMode = WikiJsExportService.InfoboxMode.FRONT_MATTER))
            .text("en/apps/widget.md")
        assertTrue(kept.contains("\ninfobox:\n  app:\n    vendor:"), kept)
        assertEquals("Prose.", kept.substringAfter("\n---\n\n"))
    }

    @Test
    fun `an empty section heading never lands over nothing`() = runBlocking<Unit> {
        val env = Env("wikijs-infobox-empty")
        val templateId = env.infobox.createTemplate(
            env.siteId, slug = "app", name = "Application", description = null,
            fields = listOf(
                InfoboxFieldDef(name = "vendor", label = "Vendor"),
                InfoboxFieldDef.heading("Deployment"),
                InfoboxFieldDef(name = "targets", label = "Targets", type = "multi"),
            ),
        )
        env.infobox.createPathRule(env.siteId, "apps/*", templateId)
        env.pages.create(
            env.siteId,
            CreatePageRequest(
                locale = "en", path = "apps/widget", title = "Widget", content = "Prose.",
                infobox = """{"app":{"vendor":"Acme"}}""",
            ),
            updatedBy = null,
        )
        val body = env.zip().text("en/apps/widget.md").substringAfter("\n---\n\n")
        assertFalse(body.contains("Deployment"), body)
        assertTrue(body.contains("| Vendor | Acme |"), body)
    }

    @Test
    fun `unpublished pages can be left out`() = runBlocking<Unit> {
        val env = Env("wikijs-unpublished")
        env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "en", path = "live-page", title = "Live", content = "x"),
            updatedBy = null,
        )
        env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "en", path = "draft-page", title = "Draft", content = "y", published = false),
            updatedBy = null,
        )
        assertEquals(listOf("en/draft-page.md", "en/live-page.md"), env.zip().keys.sorted())
        // WikiJS's importer ignores the published flag, so excluding them is the only way to keep a
        // draft from going live over there.
        val published = env.zip(WikiJsExportService.Options(includeUnpublished = false))
        assertEquals(listOf("en/live-page.md"), published.keys.sorted())
    }

    @Test
    fun `the admin form downloads a zip and rejects a missing CSRF token`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-wikijs-route-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val csrf = admin.loginAsAdmin()
        admin.createSamplePage(csrf)

        val page = admin.get("/a/storage").bodyAsText()
        assertTrue(page.contains("""action="/a/backup/export/wikijs""""), "the storage page offers the export")
        // Assert the option VALUES, not their labels: the wording is the admin's to edit, but these are
        // what the form posts back and InfoboxMode.from() parses.
        for (mode in WikiJsExportService.InfoboxMode.entries) {
            assertTrue(page.contains("""value="${mode.name.lowercase()}""""), "infobox mode ${mode.name} is offered")
        }

        val export = admin.post("/a/backup/export/wikijs") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("infoboxMode", "table") }))
        }
        assertEquals(HttpStatusCode.OK, export.status)
        assertTrue(
            export.headers[HttpHeaders.ContentDisposition].orEmpty().contains("wikikt-wikijs-export-"),
            "downloads with an export filename: ${export.headers[HttpHeaders.ContentDisposition]}",
        )
        val zip = Files.createTempFile("wikikt-wikijs-route", ".zip")
        Files.write(zip, export.readRawBytes())
        val names = ZipFile(zip.toFile()).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.contains("en/$SAMPLE_PAGE_PATH.md"), "archive holds the sample page: $names")

        val noCsrf = admin.post("/a/backup/export/wikijs") {
            setBody(FormDataContent(Parameters.build { append("infoboxMode", "table") }))
        }
        assertEquals(HttpStatusCode.Forbidden, noCsrf.status)
    }
}
