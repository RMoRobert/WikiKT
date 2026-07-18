package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The header language switcher (globe) lists only locales in which the current page actually exists,
 * and is hidden when there's just one. Exercised end-to-end through the rendered page HTML.
 */
class LocaleSwitcherTest {
    private fun ApplicationTestBuilder.configureApp(dbName: String) {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///$dbName;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }
    }

    @Test
    fun `globe lists only locales where the page exists, and hides when only one`() = testApplication {
        configureApp("wikikt-locale-switcher")
        val admin = createClient { install(HttpCookies) }

        val csrf = assertNotNull(
            admin.post("/u/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"test"}""")
            }.headers["X-CSRF-Token"],
        )

        // Enable Portuguese as a second content locale (the settings form submits one value per box).
        val enable = admin.post("/a/settings/locale") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("siteLocales", "pt") }))
        }
        assertTrue(enable.status == HttpStatusCode.OK || enable.status == HttpStatusCode.Found)

        suspend fun createPage(locale: String, path: String) {
            val res = admin.post("/u/v1/pages") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody("""{"locale":"$locale","path":"$path","title":"T","content":"body"}""")
            }
            assertEquals(HttpStatusCode.Created, res.status, "creating $locale/$path")
        }

        // A page that exists in BOTH locales, and one that exists only in English.
        createPage("en", "guide/intro")
        createPage("pt", "guide/intro")
        createPage("en", "guide/en-only")

        // The bilingual page shows the globe with both languages, each linking to its locale path.
        val bilingual = admin.get("/en/guide/intro").bodyAsText()
        assertTrue("mdi-web" in bilingual, "the globe should appear for a translated page")
        assertTrue("English" in bilingual, "the current locale is listed by name")
        assertTrue("Português" in bilingual, "the Portuguese translation is listed by endonym")
        assertTrue("href=\"/pt/guide/intro\"" in bilingual, "the Portuguese entry links to the /pt path")

        // The English-only page (pt enabled but not translated) hides the globe entirely.
        val single = admin.get("/en/guide/en-only").bodyAsText()
        assertFalse("mdi-web" in single, "the globe is hidden when the page has no other translations")
    }

    @Test
    fun `globe is hidden entirely when only one locale is enabled`() = testApplication {
        configureApp("wikikt-locale-single")
        val admin = createClient { install(HttpCookies) }
        val csrf = assertNotNull(
            admin.post("/u/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"test"}""")
            }.headers["X-CSRF-Token"],
        )

        // Only the default locale is enabled (no siteLocales configured).
        val res = admin.post("/u/v1/pages") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"locale":"en","path":"solo/page","title":"T","content":"body"}""")
        }
        assertEquals(HttpStatusCode.Created, res.status)

        val page = admin.get("/en/solo/page").bodyAsText()
        assertFalse("mdi-web" in page, "the globe must not appear on a single-locale site")
    }
}
