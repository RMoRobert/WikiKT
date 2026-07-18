package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A site created through the admin console must come with the same starter content a first-run install
 * gets — a home page and a Home-link sidebar — so its hostname doesn't open on a "create this page" 404
 * with an empty nav. Guards the seeding hook in `handleSiteSave`.
 */
class NewSiteSeedTest {
    @Test
    fun `creating a site seeds its home page and Home nav link`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-new-site-seed-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()

        // Create a second, hostname-scoped site the way the Admin > Sites form does. The CSRF token is
        // base64 (may contain +/=) so it must be percent-encoded in the form body.
        val csrfEncoded = java.net.URLEncoder.encode(csrf, "UTF-8")
        val create = client.post("/a/sites") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Docs&hostname=docs.test&_csrf=$csrfEncoded")
        }
        assertEquals(HttpStatusCode.Found, create.status, "site create redirects on success")

        // Visiting the new site's home (resolved by Host header) serves the seeded welcome page...
        val home = client.get("/en/home") { header("Host", "docs.test") }
        assertEquals(HttpStatusCode.OK, home.status, "new site's home page exists (not a create-page 404)")
        val html = home.bodyAsText()
        assertTrue(html.contains("Customize Your Site"), "seeded home content from /seed/home.md is served")

        // ...and its sidebar carries the seeded Home link with the MDI home icon.
        assertTrue(html.contains("mdi-home"), "seeded Home nav item renders its MDI home icon")
    }
}
