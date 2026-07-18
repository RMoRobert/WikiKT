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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchRoutesTest {
    @Test
    fun `search api returns json hits and html page renders results`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-searchroutes-test;DB_CLOSE_DELAY=-1",
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

        client.post("/u/v1/pages") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"locale":"en","path":"guide/network","title":"Network Setup","content":"How to configure network devices.","published":true}""")
        }

        // JSON API: returns a hit with a url and snippet, no full content key.
        val api = client.get("/u/v1/search?q=network&locale=en")
        assertEquals(HttpStatusCode.OK, api.status)
        val body = api.bodyAsText()
        assertTrue(body.contains("Network Setup"), "title in JSON: $body")
        assertTrue(body.contains("\"url\""), "url field present")
        assertTrue(body.contains("/guide/network"), "canonical url present")

        // Short query → empty JSON array.
        assertEquals("[]", client.get("/u/v1/search?q=x").bodyAsText())

        // HTML results page renders the hit.
        val page = client.get("/s?q=network&locale=en")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("Network") && html.contains("Setup"), "result title on page: $html")
        assertTrue(html.contains("result(s) for"), "summary line present")

        // Empty-state page (no query) still renders.
        val empty = client.get("/s")
        assertEquals(HttpStatusCode.OK, empty.status)
        assertTrue(empty.bodyAsText().contains("search box"), "empty-state hint present")
    }

    @Test
    fun `search excludes pages the user cannot view`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-searchperm-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val admin = createClient { install(HttpCookies); followRedirects = false }
        val csrf = admin.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        // A draft (unpublished) page is editor-visible but not part of normal search.
        admin.post("/u/v1/pages") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"locale":"en","path":"guide/secret","title":"Secret Network Draft","content":"hidden","published":false}""")
        }

        // Anonymous client must not see the unpublished page in search results.
        val anon = createClient { followRedirects = false }
        val body = anon.get("/u/v1/search?q=network&locale=en").bodyAsText()
        assertFalse(body.contains("Secret Network Draft"), "draft not surfaced to anon: $body")
    }
}
