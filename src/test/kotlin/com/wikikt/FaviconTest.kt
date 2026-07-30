package com.wikikt

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FaviconTest {
    @Test
    fun `pages link a favicon and the default is served at the root`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-favicon-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        // Every page head links a favicon; with nothing configured it points at the bundled default.
        client.get("/login").bodyAsText().let { html ->
            assertTrue(html.contains("""<link rel="icon" href="/favicon.svg""""), "default favicon linked in <head>")
        }

        // The default favicon is actually served at the conventional root path, with an ETag —
        // it's referenced on every page without a version token, so refetches must 304.
        val res = client.get("/favicon.svg")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.headers["Content-Type"].orEmpty().contains("svg"), "served as an SVG")
        assertTrue(res.bodyAsText().contains("<svg"), "is the SVG document")
        val etag = res.headers["ETag"]
        assertTrue(!etag.isNullOrBlank(), "bundled favicon carries an ETag")
        val conditional = client.get("/favicon.svg") { header("If-None-Match", etag!!) }
        assertEquals(HttpStatusCode.NotModified, conditional.status, "matching If-None-Match yields a 304")
    }

    @Test
    fun `the default logo is shown in the header and served at the root`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-logo-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        // With no logo configured, the header shows the bundled default beside the site name.
        client.get("/login").bodyAsText().let { html ->
            assertTrue(html.contains("""<img class="brand-logo" src="/logo.svg""""), "default logo shown in header")
        }

        val res = client.get("/logo.svg")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.headers["Content-Type"].orEmpty().contains("svg"), "served as an SVG")
        assertTrue(res.bodyAsText().contains("<svg"), "is the SVG document")
    }
}
