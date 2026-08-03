package com.wikikt

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
 * Where the Mermaid diagram library comes from (`wikikt.ui.mermaidSource`), and that the loader is
 * wired into the page at all. Like the two webfonts this is deliberately *not* tied to
 * `wikikt.ui.assetSource`: at ~3.5 MB it is the largest thing WikiKT can ask a browser for.
 *
 * What is asserted here is only the *loader* — page-mermaid.js and the URL handed to it. Mermaid
 * itself is fetched by that script, at which point there is a diagram on the page, so no test here can
 * see it; the local case does check the vendored bundle is actually served, because a `local` install
 * that 404s there has no diagrams at all.
 */
class MermaidSourceTest {
    private val cdnSrc = "https://cdn.jsdelivr.net/npm/mermaid@11.16.0/dist/mermaid.min.js"
    private val localSrc = "/static/vendor/mermaid/mermaid.min.js"

    @Test
    fun `defaults to the CDN copy, with subresource integrity`() = testApplication {
        environment { config = testConfig("mermaid-default") }
        application { configure() }

        val html = createClient { followRedirects = true }.get("/").bodyAsText()
        assertTrue(html.contains("/static/page-mermaid.js"), "the diagram loader is on the page")
        assertTrue(html.contains("""data-mermaid-src="$cdnSrc""""), "jsDelivr copy configured")
        assertTrue(
            html.contains("""data-mermaid-integrity="sha384-T/0lMUdJpd2S1ZHtRiofG3htU3xPCrFVeAQ1UUE2TJwlEJSV5NUwn30kP28n238E""""),
            "SRI hash kept — page-mermaid.js only sets crossOrigin when it has one",
        )
        assertFalse(html.contains(localSrc), "the bundled copy is not also referenced")
    }

    @Test
    fun `mermaidSource local serves the bundled copy and nothing third-party`() = testApplication {
        environment { config = testConfig("mermaid-local").apply { put("wikikt.ui.mermaidSource", "local") } }
        application { configure() }
        val client = createClient { followRedirects = true }

        val html = client.get("/").bodyAsText()
        assertTrue(html.contains("""data-mermaid-src="$localSrc?v="""), "bundled copy configured (with the cache-busting token)")
        assertFalse(html.contains("cdn.jsdelivr.net/npm/mermaid"), "no jsDelivr request for diagrams")
        assertFalse(html.contains("data-mermaid-integrity"), "no SRI on a same-origin script — it would also force CORS")

        val js = client.get(localSrc)
        assertEquals(HttpStatusCode.OK, js.status, "the vendored bundle is served")
        assertTrue(js.bodyAsText().contains("globalThis[\"mermaid\"]"), "and it is the build that exports the global the loader waits for")
    }

    @Test
    fun `the Mermaid source is independent of the general asset source`() = testApplication {
        // Both directions, so the two can't be quietly recoupled: local Bootstrap keeps Mermaid on the
        // CDN, and (in the test above) a local Mermaid doesn't drag Bootstrap off it.
        environment { config = testConfig("mermaid-independent").apply { put("wikikt.ui.assetSource", "local") } }
        application { configure() }

        val html = createClient { followRedirects = true }.get("/").bodyAsText()
        assertTrue(html.contains("/static/vendor/bootstrap/"), "Bootstrap is served locally, as configured")
        assertTrue(html.contains(cdnSrc), "Mermaid still comes from the CDN")
    }

    @Test
    fun `an unrecognized value falls back to the CDN rather than a broken local path`() = testApplication {
        environment { config = testConfig("mermaid-typo").apply { put("wikikt.ui.mermaidSource", "loacl") } }
        application { configure() }

        val html = createClient { followRedirects = true }.get("/").bodyAsText()
        assertTrue(html.contains(cdnSrc), "a typo'd source is treated as cdn, the safe default")
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
