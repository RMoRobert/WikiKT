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

class TagRoutingTest {
    @Test
    fun `tag view lists pages with a tag, and page tags link to it`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-tag-test;DB_CLOSE_DELAY=-1",
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
            setBody(
                """{"locale":"en","path":"guide/tagged","title":"Tagged Guide","content":"hi",""" +
                    """"published":true,"tags":["faq","setup"]}""",
            )
        }

        // The page's tags render as links to the tag view.
        client.get("/en/guide/tagged").bodyAsText().let { html ->
            assertTrue(html.contains("href=\"/t/faq\""), "tag links to its tag view")
        }

        // The tag view lists the tagged page.
        val res = client.get("/t/faq")
        assertEquals(HttpStatusCode.OK, res.status)
        res.bodyAsText().let { html ->
            assertTrue(html.contains("Pages tagged"), "tag view heading")
            assertTrue(html.contains("Tagged Guide"), "tagged page listed")
            assertTrue(html.contains("href=\"/en/guide/tagged\""), "links to the page")
        }

        // A tag with no pages shows the empty state, not the page.
        client.get("/t/nonexistent").bodyAsText().let { html ->
            assertTrue(html.contains("No pages are tagged"), "empty state shown")
            assertFalse(html.contains("Tagged Guide"), "unrelated page not listed")
        }
    }
}
