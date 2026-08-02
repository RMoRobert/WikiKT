package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.ContentFormat
import com.wikikt.db.DatabaseFactory
import com.wikikt.model.CreatePageRequest
import com.wikikt.model.PageRecord
import com.wikikt.model.UpdatePageRequest
import com.wikikt.service.AssetService
import com.wikikt.service.ContentImporter
import com.wikikt.service.GitSyncService
import com.wikikt.service.PageFileFormat
import com.wikikt.service.GitSyncSettings
import com.wikikt.service.MigrationService
import com.wikikt.service.PageService
import com.wikikt.service.SettingsService
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitSyncServiceTest {
    private val pngBytes = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private fun page(
        contentFormat: ContentFormat = ContentFormat.MARKDOWN,
        title: String = "File One",
        description: String? = "Introduction Guide",
        tags: List<String> = listOf("documentation", "guide"),
    ) = PageRecord(
        id = 1u, siteId = 1u, locale = "en", path = "dir1/dir2/file1", title = title,
        description = description, content = "# Body\n\nText.", contentFormat = contentFormat,
        published = true, publishAt = null, createdAt = 1_700_000_000_000, updatedAt = 1_700_000_100_000,
        updatedBy = null, tags = tags,
    )

    @Test
    fun `isAllowedRepoUrl accepts real transports and rejects command-executing pseudo-URLs`() {
        // Legitimate remotes.
        assertTrue(GitSyncService.isAllowedRepoUrl("https://github.com/org/repo.git"))
        assertTrue(GitSyncService.isAllowedRepoUrl("http://example.com/repo.git"))
        assertTrue(GitSyncService.isAllowedRepoUrl("ssh://git@example.com/org/repo.git"))
        assertTrue(GitSyncService.isAllowedRepoUrl("git@github.com:org/repo.git")) // scp-form
        assertTrue(GitSyncService.isAllowedRepoUrl("")) // blank = not configured
        // The RCE vector and other command/local transports must be refused.
        assertFalse(GitSyncService.isAllowedRepoUrl("ext::sh -c \"id > /tmp/pwned\""))
        assertFalse(GitSyncService.isAllowedRepoUrl("ext::sh"))
        assertFalse(GitSyncService.isAllowedRepoUrl("fd::17"))
        assertFalse(GitSyncService.isAllowedRepoUrl("file:///etc/passwd"))
        assertFalse(GitSyncService.isAllowedRepoUrl("-oProxyCommand=evil"))
    }

    @Test
    fun `markdown pages map to locale-prefixed md files with WikiJS front-matter`() {
        assertEquals("en/dir1/dir2/file1.md", PageFileFormat.pageFilePath(page()))
        val body = PageFileFormat.pageFileBody(page())
        val expected = """
            ---
            title: File One
            description: Introduction Guide
            published: true
            date: 2023-11-14T22:15:00Z
            tags: documentation, guide
            editor: markdown
            dateCreated: 2023-11-14T22:13:20Z
            ---

            # Body

            Text.
        """.trimIndent()
        assertEquals(expected, body)
    }

    @Test
    fun `html pages use html extension and comment-wrapped metadata`() {
        val p = page(contentFormat = ContentFormat.HTML)
        assertEquals("en/dir1/dir2/file1.html", PageFileFormat.pageFilePath(p))
        val body = PageFileFormat.pageFileBody(p)
        assertTrue(body.startsWith("<!--\ntitle: File One\n"))
        assertTrue(body.contains("editor: code"))
        assertTrue(body.contains("\n-->\n\n# Body"))
    }

    @Test
    fun `front-matter round-trips through export and parse`() {
        for (fmt in listOf(ContentFormat.MARKDOWN, ContentFormat.HTML)) {
            val p = page(contentFormat = fmt, title = "Q: \"tricky\" title", description = "")
            val parsed = PageFileFormat.parsePageFile(PageFileFormat.pageFileBody(p), html = fmt == ContentFormat.HTML)
            assertEquals(p.title, parsed.title, "title round-trip ($fmt)")
            assertEquals("", parsed.description)
            assertEquals(true, parsed.published)
            assertEquals(p.tags, parsed.tags)
            assertEquals(p.content, parsed.content, "body round-trip ($fmt)")
        }
        // Files without front-matter import as body-only.
        val bare = PageFileFormat.parsePageFile("just some text", html = false)
        assertNull(bare.title)
        assertEquals("just some text", bare.content)
        // YAML flow-style tags are accepted too.
        val flow = PageFileFormat.parsePageFile("---\ntitle: T\ntags: [a, b]\n---\n\nx", html = false)
        assertEquals(listOf("a", "b"), flow.tags)
    }

    @Test
    fun `yaml scalars are quoted only when they could change meaning`() {
        assertEquals("Plain title", PageFileFormat.yamlScalar("Plain title"))
        assertEquals("\"Q: what?\"", PageFileFormat.yamlScalar("Q: what?"))
        assertEquals("\"#1 pick\"", PageFileFormat.yamlScalar("#1 pick"))
        assertEquals("\"say \\\"hi\\\"\"", PageFileFormat.yamlScalar("say \"hi\""))
        assertEquals("\"\"", PageFileFormat.yamlScalar(""))
        assertEquals("say \"hi\"", PageFileFormat.unquote("\"say \\\"hi\\\"\""))
        assertEquals("a\\b", PageFileFormat.unquote("\"a\\\\b\""))
    }

    @Test
    fun `https token is embedded as userinfo, ssh urls pass through`() {
        val env = TestEnv("gitsync-url")
        fun s(url: String, user: String = "rob", token: String = "t0k/en") =
            GitSyncSettings("push", url, "main", user, token, "n", "e", 0)
        assertEquals(
            "https://rob:t0k%2Fen@github.com/x/y.git",
            env.service.authenticatedUrl(s("https://github.com/x/y.git")),
        )
        assertEquals("https://git:tok@h/x.git", env.service.authenticatedUrl(s("https://h/x.git", user = "", token = "tok")))
        assertEquals("git@github.com:x/y.git", env.service.authenticatedUrl(s("git@github.com:x/y.git")))
        assertEquals("https://a@h/x.git", env.service.authenticatedUrl(s("https://a@h/x.git")), "existing userinfo untouched")
    }

    @Test
    fun `push mode exports pages and assets, is idempotent, propagates edits and deletes`() = runBlocking {
        if (!gitAvailable()) return@runBlocking
        val env = TestEnv("gitsync-push")
        env.configure(mode = "push")

        val created = env.pages.create(
            env.siteId,
            CreatePageRequest(
                locale = "en", path = "home", title = "Home", description = "Front page",
                content = "Welcome", contentFormat = "MARKDOWN", published = true, tags = listOf("start"),
            ),
            updatedBy = null,
        )
        val temp = env.assets.newTempFile()
        Files.write(temp, pngBytes)
        env.assets.create(env.siteId, "en", "images/logo.png", "logo.png", "image/png", pngBytes.size.toLong(), temp, null)

        val first = env.service.syncNow(env.siteId)
        assertTrue(first.ok, "first sync should succeed: ${first.message}")
        assertEquals(
            listOf("home.md", "images/logo.png"),
            env.remoteFiles(),
            "default-locale content is written at the repo root, WikiJS-style",
        )
        val pushedPage = env.remoteShow("home.md")
        assertTrue(pushedPage.startsWith("---\ntitle: Home\n"), "front-matter present")
        assertTrue(pushedPage.contains("tags: start"))
        assertTrue(pushedPage.trimEnd().endsWith("Welcome"))
        assertEquals("true", env.settings.get(env.siteId, SettingsService.GIT_SYNC_LAST_OK))

        // No changes → still ok, no new commit.
        val headBefore = env.remoteHead()
        assertTrue(env.service.syncNow(env.siteId).ok)
        assertEquals(headBefore, env.remoteHead(), "no-op sync adds no commit")

        // Edit + delete propagate: update the page, remove the asset — batched into ONE commit.
        env.pages.update(created.id, UpdatePageRequest(content = "Welcome v2"), updatedBy = null)
        env.assets.delete(env.assets.list(env.siteId).single().id)
        val third = env.service.syncNow(env.siteId)
        assertTrue(third.ok, third.message)
        assertEquals(listOf("home.md"), env.remoteFiles(), "deleted asset removed from the repo")
        assertTrue(env.remoteShow("home.md").contains("Welcome v2"))
        assertEquals(1, env.remoteCommitCountSince(headBefore), "batched changes land as one commit")
    }

    @Test
    fun `pull mode imports, updates with revision history, and applies repo deletions`() = runBlocking {
        if (!gitAvailable()) return@runBlocking
        val env = TestEnv("gitsync-pull")
        env.configure(mode = "pull")

        // Seed the remote from a separate working clone.
        env.workWrite("en/guide.md", "---\ntitle: The Guide\ndescription: Imported\npublished: true\ntags: howto\n---\n\nHello from git")
        env.workWriteBytes("en/pics/one.png", pngBytes)
        env.workCommitPush("add guide + image")

        val first = env.service.syncNow(env.siteId)
        assertTrue(first.ok, first.message)
        val imported = env.pages.findByLocaleAndPath(env.siteId, "en", "guide")
        assertNotNull(imported, "page imported from repo")
        assertEquals("The Guide", imported.title)
        assertEquals("Imported", imported.description)
        assertEquals(listOf("howto"), imported.tags)
        assertEquals("Hello from git", imported.content)
        assertNotNull(env.assets.findByLocaleAndPath(env.siteId, "en", "pics/one.png"), "asset imported from repo")

        // A wiki-side edit survives a pull with no repo changes (diff-based, no clobber).
        env.pages.update(imported.id, UpdatePageRequest(content = "wiki edit wins"), updatedBy = null)
        assertTrue(env.service.syncNow(env.siteId).ok)
        assertEquals("wiki edit wins", env.pages.findByLocaleAndPath(env.siteId, "en", "guide")!!.content)

        // A repo-side edit is applied — and the previous content lands in revision history.
        env.workWrite("en/guide.md", "---\ntitle: The Guide\ndescription: Imported\npublished: true\ntags: howto\n---\n\nUpdated in git")
        env.workCommitPush("update guide")
        assertTrue(env.service.syncNow(env.siteId).ok)
        val updated = env.pages.findByLocaleAndPath(env.siteId, "en", "guide")!!
        assertEquals("Updated in git", updated.content)
        assertTrue(env.pages.revisions(updated.id).any { it.content == "wiki edit wins" }, "prior content kept as a revision")

        // A repo-side deletion deletes the page.
        env.workDelete("en/guide.md")
        env.workCommitPush("remove guide")
        assertTrue(env.service.syncNow(env.siteId).ok)
        assertNull(env.pages.findByLocaleAndPath(env.siteId, "en", "guide"), "repo deletion applied to the wiki")
        assertNotNull(env.assets.findByLocaleAndPath(env.siteId, "en", "pics/one.png"), "untouched asset kept")

        // Force import upserts everything but never deletes.
        val recreated = env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "en", path = "local-only", title = "Local", content = "local", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )
        val force = env.service.importEverything(env.siteId)
        assertTrue(force.ok, force.message)
        assertNotNull(env.pages.findById(recreated.id), "force import never deletes wiki content")
    }

    @Test
    fun `pull maps repo-root files to the default locale and normalizes lowercase locale folders`() = runBlocking {
        if (!gitAvailable()) return@runBlocking
        val env = TestEnv("gitsync-wikijs-layout")
        env.configure(mode = "pull")

        // A WikiJS git export: default-locale (English) content at the repo root with no locale prefix,
        // other locales under a lowercase `{locale}` folder, assets alongside the pages.
        env.workWrite("home.md", "---\ntitle: Home\n---\n\nEnglish home")
        env.workWrite("dir1/file1.md", "---\ntitle: File One\n---\n\nEnglish file1 guide")
        env.workWriteBytes("logo.png", pngBytes)
        env.workWrite("pt-br/home.md", "---\ntitle: Início\n---\n\nPágina inicial")
        env.workWriteBytes("pt-br/pics/one.png", pngBytes)
        env.workCommitPush("WikiJS-style export")

        assertTrue(env.service.syncNow(env.siteId).ok)

        // Root files land in the default locale, keeping their full path.
        assertEquals("English home", env.pages.findByLocaleAndPath(env.siteId, "en", "home")?.content)
        assertEquals("English file1 guide", env.pages.findByLocaleAndPath(env.siteId, "en", "dir1/file1")?.content)
        assertNotNull(env.assets.findByLocaleAndPath(env.siteId, "en", "logo.png"), "root asset imported to default locale")

        // The lowercase `pt-br` folder is recognized as the `pt-BR` locale and stripped from the path.
        assertEquals("Página inicial", env.pages.findByLocaleAndPath(env.siteId, "pt-BR", "home")?.content)
        assertNotNull(env.assets.findByLocaleAndPath(env.siteId, "pt-BR", "pics/one.png"), "locale-folder asset imported")
        assertNull(env.pages.findByLocaleAndPath(env.siteId, "en", "pt-br/home"), "locale folder not swept into default locale")
    }

    @Test
    fun `export puts the default locale at the root and namespaces other locales`() = runBlocking {
        if (!gitAvailable()) return@runBlocking
        val env = TestEnv("gitsync-export-layout")
        env.configure(mode = "push")

        env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "en", path = "home", title = "Home", content = "en home", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )
        env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "pt-BR", path = "home", title = "Início", content = "pt home", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )
        val tmp = env.assets.newTempFile()
        Files.write(tmp, pngBytes)
        env.assets.create(env.siteId, "pt-BR", "pics/one.png", "one.png", "image/png", pngBytes.size.toLong(), tmp, null)

        assertTrue(env.service.syncNow(env.siteId).ok)
        // Default locale (en) unprefixed at the root; pt-BR namespaced under its own folder.
        assertEquals(listOf("home.md", "pt-BR/home.md", "pt-BR/pics/one.png"), env.remoteFiles())
    }

    @Test
    fun `bidirectional pulls repo changes then pushes wiki changes`() = runBlocking {
        if (!gitAvailable()) return@runBlocking
        val env = TestEnv("gitsync-bidi")
        env.configure(mode = "bidirectional")

        // Seed a root-level (default-locale) repo page, WikiJS-style.
        env.workWrite("from-git.md", "---\ntitle: From Git\n---\n\nrepo content")
        env.workCommitPush("seed repo page")
        env.pages.create(
            env.siteId,
            CreatePageRequest(locale = "en", path = "from-wiki", title = "From Wiki", content = "wiki content", contentFormat = "MARKDOWN"),
            updatedBy = null,
        )

        val result = env.service.syncNow(env.siteId)
        assertTrue(result.ok, result.message)
        assertNotNull(env.pages.findByLocaleAndPath(env.siteId, "en", "from-git"), "repo page imported")
        // Push writes the default locale back to the root, so the imported page stays put (no churn
        // between the root and an `en/` folder) and the wiki page joins it at the root.
        assertEquals(listOf("from-git.md", "from-wiki.md"), env.remoteFiles(), "wiki page pushed, default locale at root")
        // The export re-writes from-git.md with full front-matter; content must survive the round trip.
        assertTrue(env.remoteShow("from-git.md").trimEnd().endsWith("repo content"))
        assertFalse(env.remoteShow("from-wiki.md").contains("repo content"))
    }

    // --- Test environment: H2 in-memory DB + real services + a local bare repo as the remote ---

    private inner class TestEnv(name: String) {
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
        val settings = SettingsService(database)
        val sites = com.wikikt.service.SiteService(database)
        val remote: Path = Files.createTempDirectory("wikikt-$name-remote")
        val clone: Path = Files.createTempDirectory("wikikt-$name-clone")
        val work: Path = Files.createTempDirectory("wikikt-$name-work")
        val importer = ContentImporter(pages, assets, maxAssetVersionsFor = { _ -> 5 }, allowedAssetMimeTypes = setOf("image/png"))
        val service = GitSyncService(settings, sites, pages, assets, clone, importer, defaultLocale = "en")

        val siteId: UInt = runBlocking {
            MigrationService(database).migrate()
            sites.create("Test site", null, isCatchAll = true).id
        }

        init {
            exec(remote, "git", "init", "--bare", "--quiet", ".")
            exec(work, "git", "init", "--quiet", ".")
            exec(work, "git", "remote", "add", "origin", remote.toString())
        }

        suspend fun configure(mode: String) {
            settings.set(siteId, SettingsService.GIT_SYNC_MODE, mode)
            settings.set(siteId, SettingsService.GIT_SYNC_REPO_URL, remote.toString())
            settings.set(siteId, SettingsService.GIT_SYNC_BRANCH, "main")
        }

        fun workWrite(rel: String, content: String) {
            val f = work.resolve(rel)
            Files.createDirectories(f.parent)
            Files.writeString(f, content)
        }

        fun workWriteBytes(rel: String, bytes: ByteArray) {
            val f = work.resolve(rel)
            Files.createDirectories(f.parent)
            Files.write(f, bytes)
        }

        fun workDelete(rel: String) {
            Files.delete(work.resolve(rel))
        }

        fun workCommitPush(message: String) {
            exec(work, "git", "add", "-A")
            exec(work, "git", "-c", "user.name=Test", "-c", "user.email=test@test", "commit", "--quiet", "-m", message)
            exec(work, "git", "push", "--quiet", "origin", "HEAD:main")
        }

        fun remoteFiles(): List<String> =
            exec(remote, "git", "ls-tree", "-r", "--name-only", "main").trim().lines().filter { it.isNotBlank() }.sorted()

        fun remoteShow(path: String): String = exec(remote, "git", "show", "main:$path")

        fun remoteHead(): String = exec(remote, "git", "rev-parse", "main").trim()

        fun remoteCommitCountSince(commit: String): Int =
            exec(remote, "git", "rev-list", "--count", "$commit..main").trim().toInt()
    }

    private fun gitAvailable(): Boolean =
        runCatching { exec(Path.of("."), "git", "--version") }.isSuccess.also {
            if (!it) println("git binary not available; skipping integration test")
        }

    /** Runs a command in [dir], returning stdout; throws on non-zero exit. */
    private fun exec(dir: Path, vararg cmd: String): String {
        val process = ProcessBuilder(*cmd).directory(dir.toFile()).redirectErrorStream(false).start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $err" }
        return out
    }
}
