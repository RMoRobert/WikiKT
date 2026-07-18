package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrandingTest {
    @Test
    fun `site name and brand color are configurable with safe defaults`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-branding-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }

        // Default: the login page (public, uses the header) shows the default product name.
        client.get("/login").bodyAsText().let { html ->
            assertTrue(html.contains("WikiKT"), "default site name shown")
            assertTrue(html.contains("<title>Login | WikiKT</title>"), "default name in <title>")
        }

        val csrf = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        // Configure branding: the site name lives on General, the logo and brand color on Appearance.
        client.post("/a/settings") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("siteName", "Acme Wiki") }))
        }
        client.post("/a/settings/appearance") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("siteLogoUrl", "")
                        append("siteBrandColor", "#aa0000")
                    },
                ),
            )
        }

        // Verify on an authenticated page that uses the header (the dashboard); /login redirects once logged in.
        client.get("/a").bodyAsText().let { html ->
            assertTrue(html.contains("Acme Wiki"), "custom site name shown")
            assertTrue(html.contains("<title>Admin | Acme Wiki</title>"), "custom name in <title>")
            assertTrue(html.contains("--bs-primary:#aa0000"), "brand color injected into :root")
            // The default name must not appear as the site name (header/title). It legitimately remains
            // in the footer's "Powered by WikiKT", so scope the check to the title rather than the whole page.
            assertFalse(html.contains("<title>Admin | WikiKT</title>"), "default name no longer the site name")
        }

        // An invalid brand color is rejected (not injected as junk CSS).
        client.post("/a/settings/appearance") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("siteLogoUrl", "")
                        append("siteBrandColor", "javascript:evil")
                    },
                ),
            )
        }
        client.get("/a").bodyAsText().let { html ->
            assertFalse(html.contains("javascript:evil"), "invalid brand color not persisted")
            assertFalse(html.contains("<style>:root{--bs-primary"), "no brand-color style when unset/invalid")
        }
    }
}
