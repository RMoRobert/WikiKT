package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
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

class PreviewTest {
    @Test
    fun `preview renders markdown for an authenticated editor and rejects anonymous`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-preview-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        // Anonymous preview is forbidden.
        val anon = client.post("/preview") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("content=%23%20Hi")
        }
        assertEquals(HttpStatusCode.Forbidden, anon.status)

        // Logged-in editor gets rendered HTML back.
        val editor = createClient { install(HttpCookies) }
        val login = editor.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)

        val preview = editor.post("/preview") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("contentFormat=MARKDOWN&content=" + "# Hello\n\n> Note\n{.is-info}".let {
                java.net.URLEncoder.encode(it, "UTF-8")
            })
        }
        assertEquals(HttpStatusCode.OK, preview.status)
        val html = preview.bodyAsText()
        assertTrue(html.contains("<h2>Hello</h2>"), "markdown renders with headings demoted one level: $html")
        assertTrue(html.contains("class=\"is-info\""), "should apply callouts: $html")
    }
}
