package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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

class RevisionHistoryTest {
    @Test
    fun `editing a page records a revision that history lists and can restore`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-rev-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }
        val client = createClient {
            install(HttpCookies)
            followRedirects = false
        }

        val login = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val csrf = login.headers["X-CSRF-Token"]!!
        client.createSamplePage(csrf)

        val byPath = client.get("/u/v1/pages/by-path?path=$SAMPLE_PAGE_PATH")
        assertEquals(HttpStatusCode.OK, byPath.status)
        val pageId = Regex("\"id\":\"(\\d+)\"").find(byPath.bodyAsText())!!.groupValues[1]

        // Update the page — this archives the original content as revision #1.
        val put = client.put("/u/v1/pages/$pageId") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"title":"$SAMPLE_PAGE_TITLE","content":"# Updated\n\nNew body."}""")
        }
        assertEquals(HttpStatusCode.OK, put.status)

        // History page lists the revision.
        val history = client.get("/h/en/$SAMPLE_PAGE_PATH")
        assertEquals(HttpStatusCode.OK, history.status)
        assertTrue(history.bodyAsText().contains("#1"), "history should list revision #1")

        // Restoring revision #1 redirects back to the page.
        val restore = client.post("/h/en/$SAMPLE_PAGE_PATH") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("rev", "1")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Found, restore.status)
    }
}
