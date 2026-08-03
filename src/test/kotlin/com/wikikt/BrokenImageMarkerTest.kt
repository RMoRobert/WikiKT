package com.wikikt

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An image whose src no longer resolves should look like a missing image, not like a sentence: the
 * placeholder is CSS (`.wk-img-broken` in site.css) applied by broken-images.js, which is the only
 * part a server-side test can see. Whether an image actually failed is a browser fact, so what's
 * guarded here is that the script is served and reaches the page — the failure mode being a rename
 * or a template edit that quietly drops it, leaving broken images invisible again.
 */
class BrokenImageMarkerTest {
    @Test
    fun `the broken-image marker is served and loaded on the page view`() = testApplication {
        environment { config = testConfig("broken-images") }
        application { configure() }
        val client = createClient { followRedirects = true }

        val html = client.get("/").bodyAsText()
        assertTrue(html.contains("/static/broken-images.js"), "the marker script is on the page view")

        val js = client.get("/static/broken-images.js")
        assertEquals(HttpStatusCode.OK, js.status, "and it is served")
        assertTrue(js.bodyAsText().contains("wk-img-broken"), "it applies the class site.css styles")
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
