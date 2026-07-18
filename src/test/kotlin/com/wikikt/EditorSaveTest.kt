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
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorSaveTest {
    @Test
    fun `editor save moves a live page by path and rejects collisions`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-editorsave-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        suspend fun mkPage(path: String) = client.post("/u/v1/pages") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"locale":"en","path":"$path","title":"P","content":"hello","published":true}""")
        }
        mkPage("guide/intro")
        mkPage("guide/taken")

        // Move guide/intro -> guide/renamed via the editor (live page, Update now).
        val moved = client.post("/e/guide/intro") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf); append("title", "P"); append("content", "hello")
                        append("contentFormat", "MARKDOWN"); append("applyMode", "now")
                        append("locale", "en"); append("path", "guide/renamed")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Found, moved.status)
        assertTrue(moved.headers["Location"]!!.endsWith("/guide/renamed"))
        assertEquals(HttpStatusCode.NotFound, client.get("/guide/intro").status, "old path no longer resolves")
        assertEquals(HttpStatusCode.OK, client.get("/en/guide/renamed").status)

        // Moving onto a taken path re-renders the editor with an error (no 500).
        val collision = client.post("/e/guide/renamed") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf); append("title", "P"); append("content", "hello")
                        append("contentFormat", "MARKDOWN"); append("applyMode", "now")
                        append("locale", "en"); append("path", "guide/taken")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, collision.status)
        assertTrue(collision.bodyAsText().contains("already exists"))

        // A staged save with a changed path is rejected (can't move while staging).
        val stagedMove = client.post("/e/guide/renamed") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf); append("title", "P"); append("content", "hello v2")
                        append("contentFormat", "MARKDOWN"); append("applyMode", "staged")
                        append("locale", "en"); append("path", "guide/elsewhere")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, stagedMove.status)
        assertTrue(stagedMove.bodyAsText().contains("Update live now"))

        // A brand-new page can be created at a form-supplied path.
        val created = client.post("/e/guide/placeholder") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf); append("title", "New"); append("content", "body")
                        append("contentFormat", "MARKDOWN"); append("locale", "en"); append("path", "docs/created")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Found, created.status)
        assertTrue(created.headers["Location"]!!.endsWith("/docs/created"))
        assertEquals(HttpStatusCode.OK, client.get("/en/docs/created").status)
    }
}
