package com.wikikt

import io.ktor.client.HttpClient
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
 * Where the Material Design Icons webfont comes from (`wikikt.ui.iconFontSource`). Like the emoji
 * font this is deliberately *not* tied to `wikikt.ui.assetSource`: both webfonts are large enough
 * that the CDN is the better default even on an install that serves Bootstrap locally.
 *
 * Asserted on an anonymous page rather than an admin one — head-deps.hbs is included by every
 * template, and the icons are chrome that unauthenticated visitors see too.
 */
class IconFontSourceTest {
    private val cdnHref = "https://cdn.jsdelivr.net/npm/@mdi/font@7.4.47/css/materialdesignicons.min.css"
    private val localHref = "/static/vendor/mdi/materialdesignicons.min.css"

    @Test
    fun `defaults to the CDN copy, with subresource integrity`() = testApplication {
        environment { config = testConfig("icon-font-default") }
        application { configure() }

        val html = createClient { followRedirects = true }.get("/").bodyAsText()
        assertTrue(html.contains("""href="$cdnHref""""), "jsDelivr copy linked")
        assertTrue(html.contains("integrity=\"sha384-HphS8cQyN+eYiJ5PMbzShG6qZdRtvHPVLPkYb8JwMkmNgaIxrFVDhQe3jIbq3EZ2\""), "SRI hash kept")
        assertFalse(html.contains(localHref), "the bundled copy is not also linked")
    }

    @Test
    fun `iconFontSource local serves the bundled copy and nothing third-party`() = testApplication {
        environment { config = testConfig("icon-font-local").apply { put("wikikt.ui.iconFontSource", "local") } }
        application { configure() }
        val client = createClient { followRedirects = true }

        val html = client.get("/").bodyAsText()
        assertTrue(html.contains("""href="$localHref""""), "bundled stylesheet linked")
        assertFalse(html.contains("cdn.jsdelivr.net/npm/@mdi"), "no jsDelivr request for icons")

        // The stylesheet and the woff2 it points at must both actually be served, or every icon is tofu.
        val css = client.get(localHref)
        assertEquals(HttpStatusCode.OK, css.status, "vendored CSS is served")
        val cssText = css.bodyAsText()
        assertTrue(
            cssText.contains("""src:url("materialdesignicons-webfont.woff2?v=7.4.47") format("woff2")"""),
            "@font-face was rewritten to the same-directory woff2, not upstream's ../fonts/ eot+woff+ttf list",
        )
        assertFalse(cssText.contains("../fonts/"), "no leftover reference to the un-vendored sibling formats")
        assertTrue(cssText.contains(".mdi-home::before"), "the icon class rules survived the rewrite")
        assertEquals(
            HttpStatusCode.OK,
            client.get("/static/vendor/mdi/materialdesignicons-webfont.woff2").status,
            "the woff2 resolves at the path the rewritten CSS asks for",
        )
    }

    @Test
    fun `the icon font host is independent of the general asset source`() = testApplication {
        // Both directions, so the two can't be quietly recoupled: local Bootstrap keeps the CDN icon
        // font, and a local icon font doesn't drag Bootstrap off the CDN.
        environment { config = testConfig("icon-font-independent").apply { put("wikikt.ui.assetSource", "local") } }
        application { configure() }

        val html = createClient { followRedirects = true }.get("/").bodyAsText()
        assertTrue(html.contains("/static/vendor/bootstrap/"), "Bootstrap is served locally, as configured")
        assertTrue(html.contains(cdnHref), "the icon font still comes from the CDN")
    }

    @Test
    fun `an unrecognized value falls back to the CDN rather than a broken local path`() = testApplication {
        environment { config = testConfig("icon-font-typo").apply { put("wikikt.ui.iconFontSource", "loacl") } }
        application { configure() }

        val html = createClient { followRedirects = true }.get("/").bodyAsText()
        assertTrue(html.contains(cdnHref), "a typo'd source is treated as cdn, the safe default")
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
