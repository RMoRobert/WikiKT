package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the editor screen's front-end libraries come from. EasyMDE rides on the general
 * `wikikt.ui.assetSource` (like Bootstrap) setting.
 *
 * Asserted on the editor page specifically, because page/edit.hbs skips the shared head/footer
 * partials and carries its own copies of the Bootstrap and EasyMDE tags, so the switch has to be
 * honored in two places, and the other asset-source tests (which load `/`) can't see this one.
 */
class EditorAssetSourceTest {
    private val cdnCss = Regex("""https://cdn\.jsdelivr\.net/npm/easymde@\d+\.\d+\.\d+/dist/easymde\.min\.css""")
    private val cdnJs = Regex("""https://cdn\.jsdelivr\.net/npm/easymde@\d+\.\d+\.\d+/dist/easymde\.min\.js""")
    private val localCss = "/static/vendor/easymde/easymde.min.css"
    private val localJs = "/static/vendor/easymde/easymde.min.js"

    @Test
    fun `defaults to the CDN copies, with subresource integrity`() = testApplication {
        environment { config = testConfig("easymde-default") }
        application { configure() }
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()
        client.createSamplePage(csrf)

        val html = client.get("/e/en/$SAMPLE_PAGE_PATH").bodyAsText()
        assertTrue(cdnCss.containsMatchIn(html), "editor stylesheet from jsDelivr")
        assertTrue(cdnJs.containsMatchIn(html), "editor script from jsDelivr")
        assertTrue(
            sriOn("easymde.min.css").containsMatchIn(html) && sriOn("easymde.min.js").containsMatchIn(html),
            "SRI hashes kept on both tags (VendoredAssetIntegrityTest checks the values)",
        )
        assertFalse(html.contains("/static/vendor/easymde/"), "the bundled copies are not also referenced")
    }

    @Test
    fun `assetSource local serves the bundled copies and nothing third-party`() = testApplication {
        environment { config = testConfig("easymde-local").apply { put("wikikt.ui.assetSource", "local") } }
        application { configure() }
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()
        client.createSamplePage(csrf)

        val html = client.get("/e/en/$SAMPLE_PAGE_PATH").bodyAsText()
        assertTrue(html.contains("$localCss?v="), "bundled stylesheet linked (with the cache-busting token)")
        assertTrue(html.contains("$localJs?v="), "bundled script linked (with the cache-busting token)")
        assertFalse(html.contains("cdn.jsdelivr.net/npm/easymde"), "no jsDelivr request for the editor")
        assertFalse(html.contains("cdn.jsdelivr.net/npm/bootstrap"), "nor for Bootstrap on this page (Mermaid keeps its own knob, so a blanket CDN check would be wrong)")
        assertTrue(
            html.contains("/static/vendor/bootstrap/bootstrap.bundle.min.js"),
            "the editor's own Bootstrap tag follows the same setting",
        )

        // Both files must actually be served, or a local install has no working editor.
        assertEquals(HttpStatusCode.OK, client.get(localCss).status, "vendored CSS is served")
        val js = client.get(localJs)
        assertEquals(HttpStatusCode.OK, js.status, "vendored JS is served")
        assertTrue(js.bodyAsText().contains("EasyMDE"), "and it is the build that exports the global page-edit.js waits for")
    }

    private fun testConfig(dbName: String) = MapApplicationConfig(
        "wikikt.defaultLocale" to "en",
        "wikikt.defaultAdmin.username" to "admin",
        "wikikt.defaultAdmin.password" to "test",
        "wikikt.database.type" to "h2",
        "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-$dbName-test;DB_CLOSE_DELAY=-1",
        "wikikt.database.h2.username" to "sa",
        "wikikt.database.h2.password" to "",
    )
}
