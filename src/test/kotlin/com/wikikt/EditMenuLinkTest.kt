package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditMenuLinkTest {
    @Test
    fun `the sidebar Edit menu link can be hidden site-wide`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-editmenu-test;DB_CLOSE_DELAY=-1",
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

        suspend fun saveNav(showEditMenuLink: Boolean) = admin.post("/a/navigation/mode") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("mode", "static")
                        if (showEditMenuLink) append("showEditMenuLink", "1")
                    },
                ),
            )
        }

        // Default: an admin sees the sidebar "Edit menu" link; an anonymous visitor never does.
        assertTrue(admin.get(pageUrl).bodyAsText().contains("wiki-nav-edit"), "link shown to admin by default")
        assertFalse(anon.get(pageUrl).bodyAsText().contains("wiki-nav-edit"), "link never shown to anonymous")

        // Hidden: saving the mode form without the checkbox turns the link off for everyone.
        saveNav(showEditMenuLink = false)
        assertFalse(admin.get(pageUrl).bodyAsText().contains("wiki-nav-edit"), "link hidden when disabled")
        assertFalse(admin.get("/a/navigation").bodyAsText().contains("""name="showEditMenuLink" id="showEditMenuLink" value="1" checked"""), "admin checkbox unchecked")

        // Re-enabled: the link comes back.
        saveNav(showEditMenuLink = true)
        assertTrue(admin.get(pageUrl).bodyAsText().contains("wiki-nav-edit"), "link restored when re-enabled")
    }
}
