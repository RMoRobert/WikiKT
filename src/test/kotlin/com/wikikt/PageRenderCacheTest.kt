package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.markdown.MarkdownRenderer
import com.wikikt.model.CreatePageRequest
import com.wikikt.service.FragmentService
import com.wikikt.service.InfoboxService
import com.wikikt.service.MigrationService
import com.wikikt.service.PageRenderService
import com.wikikt.service.PageService
import com.wikikt.service.SettingsService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageRenderCacheTest {
    private class Fixture(name: String) {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///$name;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        val pages = PageService(database)
        val fragments = FragmentService(database)
        val settings = SettingsService(database)
        val markdown = MarkdownRenderer()
        val renderCache = PageRenderService(database, pages, fragments, markdown, settings, InfoboxService(database, markdown, settings), "en")
    }

    @Test
    fun `serves stored html until the render epoch changes`() = runBlocking {
        val f = Fixture("wikikt-rendercache-epoch")
        MigrationService(f.database).migrate()
        val siteId = com.wikikt.service.SiteService(f.database).create("Test site", null, isCatchAll = true).id
        val page = f.pages.create(
            siteId,
            CreatePageRequest(locale = "en", path = "p", title = "P", content = "see https://ex.com/x", published = true),
            null,
        )

        // Lazy fill (idea #2): first view renders + stores. Autolink is on by default.
        val first = f.renderCache.getOrRender(f.pages.findById(page.id)!!).body
        assertTrue(first.contains("<a href=\"https://ex.com/x\""), "autolink on by default: $first")

        // Change a render setting WITHOUT bumping the epoch → the stored render is still served.
        f.settings.setBool(siteId, SettingsService.RENDER_AUTOLINK, false)
        assertEquals(first, f.renderCache.getOrRender(f.pages.findById(page.id)!!).body, "served from cache (no epoch bump)")

        // Bump the epoch (what the settings page does) → the row is stale and re-rendered.
        f.settings.bumpRenderEpoch(siteId)
        val fresh = f.renderCache.getOrRender(f.pages.findById(page.id)!!).body
        assertFalse(fresh.contains("<a href"), "re-rendered with autolink off after epoch bump: $fresh")
    }

    @Test
    fun `stale row is re-rendered when the page content version changes`() = runBlocking {
        val f = Fixture("wikikt-rendercache-content")
        MigrationService(f.database).migrate()
        val siteId = com.wikikt.service.SiteService(f.database).create("Test site", null, isCatchAll = true).id
        val page = f.pages.create(
            siteId,
            CreatePageRequest(locale = "en", path = "p", title = "P", content = "original", published = true),
            null,
        )
        assertTrue(f.renderCache.getOrRender(f.pages.findById(page.id)!!).body.contains("original"))

        // Update content (no onContentChanged wired here → no write-through); getOrRender must notice the
        // changed updatedAt and re-render rather than serving the stale row.
        f.pages.update(page.id, com.wikikt.model.UpdatePageRequest(content = "updated body"), updatedBy = null)
        assertTrue(f.renderCache.getOrRender(f.pages.findById(page.id)!!).body.contains("updated body"), "re-rendered after content change")
    }

    @Test
    fun `relative markdown links resolve against the page directory, WikiJS-style`() = runBlocking {
        val f = Fixture("wikikt-rendercache-relative")
        MigrationService(f.database).migrate()
        val siteId = com.wikikt.service.SiteService(f.database).create("Test site", null, isCatchAll = true).id
        val page = f.pages.create(
            siteId,
            CreatePageRequest(
                locale = "en",
                path = "docs/user-guide",
                title = "User Guide",
                // A bare relative link and a root-absolute one; only the relative one should be rewritten.
                content = "[Getting Started](getting-started) and [Home](/en/home) and [Docs](https://ex.com)",
                published = true,
            ),
            null,
        )

        val html = f.renderCache.getOrRender(f.pages.findById(page.id)!!).body
        assertTrue(html.contains("href=\"/en/docs/user-guide/getting-started\""), "relative link resolved to the page directory: $html")
        assertTrue(html.contains("href=\"/en/home\""), "root-absolute link left untouched: $html")
        assertTrue(html.contains("href=\"https://ex.com\""), "external link left untouched: $html")
    }

    @Test
    fun `internal links to missing pages get the is-new-page class, existing pages do not`() = runBlocking {
        val f = Fixture("wikikt-redlink")
        MigrationService(f.database).migrate()
        val siteId = com.wikikt.service.SiteService(f.database).create("Test site", null, isCatchAll = true).id
        f.pages.create(siteId, CreatePageRequest(locale = "en", path = "exists", title = "Exists", content = "x", published = true), null)
        val page = f.pages.create(
            siteId,
            CreatePageRequest(
                locale = "en",
                path = "hub",
                title = "Hub",
                content = "[here](/en/exists) and [there](/en/missing) and [ext](https://example.com)",
                published = true,
            ),
            null,
        )
        val html = f.renderCache.getOrRender(f.pages.findById(page.id)!!).body
        assertTrue(html.contains("href=\"/en/exists\"") && !linkHasClass(html, "/en/exists", "is-new-page"), "existing target not red: $html")
        assertTrue(linkHasClass(html, "/en/missing", "is-new-page"), "missing target is red: $html")
        assertFalse(linkHasClass(html, "https://example.com", "is-new-page"), "external link untouched: $html")
    }

    @Test
    fun `creating a target flips a linking page's red link to blue`() = runBlocking {
        val f = Fixture("wikikt-redlink-flip")
        MigrationService(f.database).migrate()
        val siteId = com.wikikt.service.SiteService(f.database).create("Test site", null, isCatchAll = true).id
        // Wire the existence-changed hook exactly as AppContext does, so create/delete re-renders linkers.
        f.pages.onPageExistenceChanged = { changedSiteId, locale, path -> f.renderCache.invalidateBacklinks(changedSiteId, locale, path) }

        val hub = f.pages.create(
            siteId,
            CreatePageRequest(locale = "en", path = "hub", title = "Hub", content = "[go](/en/target)", published = true),
            null,
        )
        // Initially the target doesn't exist → red, and it's cached.
        assertTrue(linkHasClass(f.renderCache.getOrRender(f.pages.findById(hub.id)!!).body, "/en/target", "is-new-page"), "red before target exists")

        // Create the target. The hook invalidates the hub's cached render.
        f.pages.create(siteId, CreatePageRequest(locale = "en", path = "target", title = "Target", content = "hi", published = true), null)
        assertFalse(linkHasClass(f.renderCache.getOrRender(f.pages.findById(hub.id)!!).body, "/en/target", "is-new-page"), "blue after target created")

        // Delete the target again → the link goes back to red on the hub's next render.
        f.pages.delete(f.pages.findByLocaleAndPath(siteId, "en", "target")!!.id)
        assertTrue(linkHasClass(f.renderCache.getOrRender(f.pages.findById(hub.id)!!).body, "/en/target", "is-new-page"), "red again after target deleted")
    }

    /** Whether the (first) <a> whose href is [href] carries CSS class [cls] in the rendered [html]. */
    private fun linkHasClass(html: String, href: String, cls: String): Boolean {
        val a = org.jsoup.Jsoup.parseBodyFragment(html).select("a[href=\"$href\"]").firstOrNull() ?: return false
        return a.hasClass(cls)
    }

    @Test
    fun `fragment change invalidates the dependent page`() = runBlocking {
        val f = Fixture("wikikt-rendercache-fragment")
        MigrationService(f.database).migrate()
        val siteId = com.wikikt.service.SiteService(f.database).create("Test site", null, isCatchAll = true).id
        val frag = f.fragments.create(siteId, "en", "note", "Note", "ORIGINAL", null)
        val page = f.pages.create(
            siteId,
            CreatePageRequest(locale = "en", path = "p", title = "P", content = "{{fragment:note}}", published = true),
            null,
        )
        assertTrue(f.renderCache.getOrRender(f.pages.findById(page.id)!!).body.contains("ORIGINAL"))

        // Change the fragment. Until invalidated, the page still serves the cached expansion.
        f.fragments.update(frag.id, "en", "note", "Note", "UPDATED", null)
        assertTrue(f.renderCache.getOrRender(f.pages.findById(page.id)!!).body.contains("ORIGINAL"), "still cached before invalidation")

        // The fragment fan-out drops the dependent page's row → next view re-expands.
        f.renderCache.invalidateForFragmentKeys(siteId, setOf("note"))
        assertTrue(f.renderCache.getOrRender(f.pages.findById(page.id)!!).body.contains("UPDATED"), "refreshed after fragment invalidation")
    }
}
