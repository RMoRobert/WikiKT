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

class TableOfContentsTest {
    @Test
    fun `the table of contents is site-configurable and can be disabled`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-toc-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val anon = createClient { install(HttpCookies); followRedirects = false }
        val admin = createClient { install(HttpCookies) }
        val pageUrl = "/en/$SAMPLE_PAGE_PATH"

        val csrf = admin.loginAsAdmin()
        admin.createSamplePage(csrf)

        // Default: the TOC sits on the right (the list itself is filled in client-side).
        anon.get(pageUrl).bodyAsText().let { html ->
            assertTrue(html.contains("""id="pageToc""""), "TOC container present by default")
            assertTrue(html.contains("page-aside--right"), "default side")
            // The "page details" box (last modified by/on) is always present in the side column.
            assertTrue(html.contains("Last modified by") && html.contains("Last modified on"), "page details box present")
        }

        suspend fun saveToc(mode: String) = admin.post("/a/settings/appearance") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("tocMode", mode)
                    },
                ),
            )
        }

        // Switch sides.
        saveToc("left")
        anon.get(pageUrl).bodyAsText().let { html ->
            assertTrue(html.contains("page-aside--left"), "left applied")
            assertFalse(html.contains("page-aside--right"), "old side dropped")
        }

        // Disable site-wide: the TOC list is gone, but the page-details box remains in the column,
        // which falls back to the default side.
        saveToc("off")
        anon.get(pageUrl).bodyAsText().let { html ->
            assertFalse(html.contains("""id="pageToc""""), "TOC removed when disabled")
            assertTrue(html.contains("page-aside--right"), "column falls back to the default side")
            assertTrue(html.contains("Last modified on"), "page details box stays when the TOC is disabled")
        }
    }
}
