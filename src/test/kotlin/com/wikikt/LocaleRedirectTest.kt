package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class LocaleRedirectTest {
    private fun ApplicationTestBuilder.localeTestEnv(db: String) {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///$db;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }
    }

    @Test
    fun `by default an unprefixed path is served as-is, with no forced locale redirect`() = testApplication {
        localeTestEnv("wikikt-localeredirect-default")
        val admin = createClient { install(HttpCookies) }
        admin.createSamplePage(admin.loginAsAdmin())
        val client = createClient { followRedirects = false }

        // Force-locale-prefix is off by default: a page requested without a locale is served
        // directly (the locale is inferred as the default), not redirected to /en/….
        val bare = client.get("/$SAMPLE_PAGE_PATH")
        assertEquals(HttpStatusCode.OK, bare.status)

        // The canonical /en/ URL still serves directly too.
        assertEquals(HttpStatusCode.OK, client.get("/en/$SAMPLE_PAGE_PATH").status)

        // The root lands on the unprefixed home page; a bare locale root still resolves to that locale's home.
        assertEquals("/home", client.get("/").headers["Location"])
        assertEquals("/en/home", client.get("/en").headers["Location"])
        assertEquals("/pt/home", client.get("/pt").headers["Location"])

        // A path with no page (and no asset) is a 404 — not a redirect loop.
        assertEquals(HttpStatusCode.NotFound, client.get("/no/such/page").status)
    }

    @Test
    fun `with Force locale prefix on, unprefixed paths 301-redirect to the primary locale`() = testApplication {
        localeTestEnv("wikikt-localeredirect-forced")
        val admin = createClient { install(HttpCookies); followRedirects = false }

        val csrf = admin.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!
        admin.createSamplePage(csrf)

        // Turn on Force locale prefix from the Locale settings page.
        admin.post("/a/settings/locale") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("localeForcePrefix", "1") }))
        }

        // Now an existing page requested without a locale → 301 to the canonical /en/ URL.
        val bare = admin.get("/$SAMPLE_PAGE_PATH")
        assertEquals(HttpStatusCode.MovedPermanently, bare.status)
        assertEquals("/en/$SAMPLE_PAGE_PATH", bare.headers["Location"])

        // The same for edit and history URLs.
        assertEquals("/e/en/$SAMPLE_PAGE_PATH", admin.get("/e/$SAMPLE_PAGE_PATH").headers["Location"])
        assertEquals("/h/en/$SAMPLE_PAGE_PATH", admin.get("/h/$SAMPLE_PAGE_PATH").headers["Location"])

        // The canonical URL serves directly (no redirect).
        assertEquals(HttpStatusCode.OK, admin.get("/en/$SAMPLE_PAGE_PATH").status)

        // Home now redirects to the reserved /{locale}/home landing page.
        assertEquals("/en/home", admin.get("/").headers["Location"])
        assertEquals("/en/home", admin.get("/en").headers["Location"])
    }
}
