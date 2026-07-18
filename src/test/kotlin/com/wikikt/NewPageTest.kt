package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewPageTest {
    @Test
    fun `duplicate copies content, blank new redirects to editor, anonymous is blocked`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-newpage-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val anon = createClient { followRedirects = false }
        assertEquals(
            HttpStatusCode.Forbidden,
            anon.post("/new") { setBody(FormDataContent(Parameters.build { append("path", "x") })) }.status,
        )

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!
        client.createSamplePage(csrf)

        // Duplicate the sample page into a new path; the copy is created with the source's content.
        val dup = client.post("/new") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("from", SAMPLE_PAGE_PATH)
                        append("fromLocale", "en")
                        append("locale", "en")
                        append("path", "$SAMPLE_PAGE_PATH-copy")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Found, dup.status)
        assertTrue(dup.headers["Location"]!!.contains("/e/en/$SAMPLE_PAGE_PATH-copy"))

        val copy = client.get("/u/v1/pages/by-path?path=$SAMPLE_PAGE_PATH-copy")
        assertEquals(HttpStatusCode.OK, copy.status)
        assertTrue(copy.bodyAsText().contains(SAMPLE_PAGE_TITLE), "copy should carry the source title/content")

        // Blank new page (no source): sends the user to the editor for the chosen path.
        val blank = client.post("/new") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("locale", "en")
                        append("path", "tutorial/intro")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Found, blank.status)
        assertTrue(blank.headers["Location"]!!.contains("/e/en/tutorial/intro"))
    }
}
