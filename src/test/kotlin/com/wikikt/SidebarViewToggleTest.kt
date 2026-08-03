package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sidebar's Main Menu ⇄ Browse view picker (`both` nav mode): a menu button whose icon and label
 * mirror the current view, server-rendered from the `wk-nav-view` cookie so the trigger, the menu's
 * checked item and the visible pane all agree on the first paint rather than after wk-nav-browser.js runs.
 */
class SidebarViewToggleTest {
    @Test
    fun `the view picker mirrors the current view and follows the wk-nav-view cookie`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-viewtoggle-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val pageUrl = "/en/$SAMPLE_PAGE_PATH"

        val csrf = admin.loginAsAdmin()
        admin.createSamplePage(csrf)
        admin.post("/a/navigation/mode") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("mode", "both")
                        append("showEditMenuLink", "1")
                        append("showHome", "1")
                    },
                ),
            )
        }

        // The trigger <button>, and one menu item's <button>; matched whitespace- and attribute-order
        // independently so reflowing the template can't break these assertions.
        fun trigger(html: String): String =
            Regex("""<button\b[^>]*\bdata-nav-view-trigger\b[^>]*>.*?</button>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.value ?: error("no view-picker trigger")
        fun item(html: String, view: String): String =
            Regex("""<button\b[^>]*\bdata-nav-view="$view"[^>]*>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.value ?: error("no menu item for view '$view'")

        // Default (no cookie): the curated menu is live, so the trigger wears ITS glyph and name and the
        // menu marks that item — the whole point of mirroring the selection rather than naming the widget.
        val static = admin.get(pageUrl).bodyAsText()
        assertTrue(trigger(static).contains("mdi-view-list-outline"), "trigger shows the Main Menu glyph")
        assertFalse(trigger(static).contains("file-tree"), "and not the Browse glyph")
        assertTrue(trigger(static).contains("""aria-label="Navigation view: Main Menu""""), "trigger names the live view")
        assertTrue(trigger(static).contains("Main Menu</span>"), "trigger's label is the live view")
        assertTrue(item(static, "static").contains("active"), "the Main Menu item is checked")
        assertFalse(item(static, "tree").contains("active"), "the Browse item is not")

        // The glyph that stood for "the control" instead of the state, and the one that collides with the
        // compact header's hamburger, must both stay out of here.
        assertFalse(static.contains("mdi-tune-variant"), "no stand-in-for-the-control glyph")
        assertFalse(static.contains("mdi-format-list-bulleted"), "no hamburger-alike glyph")

        // Cookie set to tree: trigger glyph, trigger name, checked item and active pane all flip together.
        val tree = admin.get(pageUrl) { header("Cookie", "wk-nav-view=tree") }.bodyAsText()
        assertTrue(trigger(tree).contains("mdi-file-tree-outline"), "trigger shows the Browse glyph")
        assertFalse(trigger(tree).contains("view-list"), "and not the Main Menu glyph")
        assertTrue(trigger(tree).contains("""aria-label="Navigation view: Browse""""), "trigger names Browse")
        assertTrue(item(tree, "tree").contains("active"), "the Browse item is checked")
        assertFalse(item(tree, "static").contains("active"), "the Main Menu item is not")
        assertTrue(tree.contains("""wk-nav-pane--tree is-active"""), "the tree pane is the active one")

        // Every view the trigger can display needs a menu item carrying the same glyph, or the JS swap
        // (data-nav-icon) and the server render would disagree the first time you switch.
        assertTrue(item(static, "static").contains("""data-nav-icon="view-list-outline""""), "static item glyph")
        assertTrue(item(static, "tree").contains("""data-nav-icon="file-tree-outline""""), "tree item glyph")
    }
}
