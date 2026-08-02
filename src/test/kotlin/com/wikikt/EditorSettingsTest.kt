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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorSettingsTest {
    @Test
    fun `editor view-mode global setting drives the editor, and the paths endpoint is slim`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-editorsettings-test;DB_CLOSE_DELAY=-1",
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
            setBody("""{"locale":"en","path":"guide/intro","title":"Intro","content":"secret body","published":true}""")
        }

        // The slim paths endpoint exposes path/title/locale but NOT page content.
        val paths = client.get("/u/v1/pages/paths").bodyAsText()
        assertTrue(paths.contains("guide/intro"), "paths list includes the page path")
        assertTrue(paths.contains("Intro"), "paths list includes the title")
        assertFalse(paths.contains("secret body"), "paths list must not ship page content")

        // Editor reflects the global default (fully formatted initially).
        val before = client.get("/e/en/guide/intro").bodyAsText()
        assertTrue(before.contains("""data-editor-view="formatted""""), "formatted view by default")

        // Admin switches the default to plain text.
        val saved = client.post("/a/settings") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("editorViewMode", "plain") }))
        }
        assertEquals(HttpStatusCode.OK, saved.status)
        assertTrue(saved.bodyAsText().contains("Settings saved"))

        val after = client.get("/e/en/guide/intro").bodyAsText()
        assertTrue(after.contains("""data-editor-view="plain""""), "plain view after admin selects it")

        // The middle mode round-trips too.
        client.post("/a/settings") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("editorViewMode", "basic") }))
        }
        val basic = client.get("/e/en/guide/intro").bodyAsText()
        assertTrue(basic.contains("""data-editor-view="basic""""), "basic view after admin selects it")

        // An unknown (or absent) value falls back to the fully formatted view rather than sticking.
        client.post("/a/settings") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("editorViewMode", "bogus") }))
        }
        val reverted = client.get("/e/en/guide/intro").bodyAsText()
        assertTrue(reverted.contains("""data-editor-view="formatted""""), "unknown value falls back to formatted")
    }
}
