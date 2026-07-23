package com.wikikt

import io.ktor.client.HttpClient
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The consistent-emoji setting (Appearance > Typography). It is deliberately a *typography* feature,
 * not a rendering one: the stored HTML is untouched and the whole effect is "Noto Color Emoji" landing
 * at the end of the font stacks, so `:smile:` expansions and pasted emoji are covered alike and no
 * render-cache bump is needed when it is flipped.
 */
class EmojiFontTest {
    private suspend fun saveAppearance(client: HttpClient, csrf: String, vararg fields: Pair<String, String>) {
        client.post("/a/settings/appearance") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); fields.forEach { append(it.first, it.second) } }))
        }
    }

    @Test
    fun `on by default the emoji font is in the stacks and its stylesheet is linked`() = testApplication {
        environment { config = testConfig("emoji-font-default") }
        application { configure() }
        val client = createClient { install(HttpCookies); followRedirects = false }
        login(client)

        val html = client.get("/a").bodyAsText()
        assertTrue(
            html.contains("--bs-body-font-family:'Roboto', system-ui, sans-serif, 'Noto Color Emoji';"),
            "emoji family is appended last, so it only catches codepoints Roboto/system-ui lack",
        )
        assertTrue(html.contains("--wk-heading-font:'Roboto', system-ui, sans-serif, 'Noto Color Emoji';"), "headings too")
        assertTrue(
            html.contains("code,pre,kbd,samp{font-family:var(--bs-font-monospace),'Noto Color Emoji';}"),
            "code spans use Bootstrap's monospace stack, which the body variable doesn't cover",
        )
        assertTrue(
            html.contains("""href="https://fonts.googleapis.com/css2?family=Noto+Color+Emoji&display=swap""""),
            "Google Fonts serves the same unicode-range slicing; the CSP baseline already allows this host",
        )
        assertFalse(html.contains("/static/vendor/noto-emoji/"), "the bundled copy is not also linked")
    }

    @Test
    fun `the emoji font host is independent of the general asset source`() = testApplication {
        // The point of the separate knob: 2MB of woff2 shouldn't ride on the Bootstrap/jsDelivr switch.
        // A local-asset install still gets the emoji font from Google unless it opts out explicitly.
        environment { config = testConfig("emoji-font-independent").apply { put("wikikt.ui.assetSource", "local") } }
        application { configure() }
        val client = createClient { install(HttpCookies); followRedirects = false }
        login(client)

        val html = client.get("/a").bodyAsText()
        assertTrue(html.contains("/static/vendor/bootstrap/"), "Bootstrap is served locally, as configured")
        assertTrue(html.contains("fonts.googleapis.com/css2?family=Noto+Color+Emoji"), "the emoji font still comes from the CDN")
    }

    @Test
    fun `the Appearance page reports the effective asset sources read-only`() = testApplication {
        // Deployment config, not a site setting — the panel exists so an admin can see the state and the
        // variable that changes it, without a form control implying it's editable per site.
        environment {
            config = testConfig("emoji-font-panel").apply {
                put("wikikt.ui.assetSource", "local")
                put("wikikt.ui.emojiFontSource", "local")
            }
        }
        application { configure() }
        val client = createClient { install(HttpCookies); followRedirects = false }
        login(client)

        val html = client.get("/a/settings/appearance").bodyAsText()
        val panel = html.substringAfter("<h2>Asset delivery</h2>")
        assertTrue(panel.contains("WIKIKT_UI_ASSET_SOURCE"), "names the variable for each row")
        assertTrue(panel.contains("WIKIKT_UI_ICON_FONT_SOURCE"))
        assertTrue(panel.contains("WIKIKT_UI_EMOJI_FONT_SOURCE"))
        // Two configured local, one left at the CDN default — the panel must distinguish them.
        assertEquals(2, Regex(">Local<").findAll(panel).count(), "both local sources reported as local")
        assertEquals(1, Regex(">CDN<").findAll(panel).count(), "the icon font is still on the CDN")
        assertFalse(panel.contains("<input"), "read-only: no form control that would imply it's editable here")
    }

    @Test
    fun `emojiFontSource local serves the bundled copy for air-gapped installs`() = testApplication {
        environment { config = testConfig("emoji-font-local").apply { put("wikikt.ui.emojiFontSource", "local") } }
        application { configure() }
        val client = createClient { install(HttpCookies); followRedirects = false }
        login(client)

        val html = client.get("/a").bodyAsText()
        assertTrue(html.contains("""href="/static/vendor/noto-emoji/noto-color-emoji.css""""), "bundled stylesheet linked")
        assertFalse(html.contains("family=Noto+Color+Emoji"), "and no third-party request is made")
    }

    @Test
    fun `turning it off restores the plain OS-dependent stacks`() = testApplication {
        environment { config = testConfig("emoji-font-off") }
        application { configure() }
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = login(client)

        // Checkbox convention: absent = off.
        saveAppearance(client, csrf, "bodyFont" to "roboto", "headingFont" to "roboto")

        val html = client.get("/a").bodyAsText()
        assertFalse(html.contains("Noto Color Emoji"), "no emoji family, no stylesheet, no monospace override")
        assertTrue(html.contains("--bs-body-font-family:'Roboto', system-ui, sans-serif;"), "stack is otherwise unchanged")
    }

    @Test
    fun `the setting survives a round-trip through the appearance form`() = testApplication {
        environment { config = testConfig("emoji-font-roundtrip") }
        application { configure() }
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = login(client)

        saveAppearance(client, csrf, "bodyFont" to "roboto", "headingFont" to "roboto")
        assertFalse(client.get("/a/settings/appearance").bodyAsText().contains("""id="emojiFont" checked"""), "unchecked after save")

        saveAppearance(client, csrf, "bodyFont" to "roboto", "headingFont" to "roboto", "emojiFont" to "1")
        assertTrue(client.get("/a/settings/appearance").bodyAsText().contains("""id="emojiFont" checked"""), "checked after save")
    }

    private fun testConfig(dbName: String) = MapApplicationConfig(
        "wikikt.defaultLocale" to "en",
        "wikikt.defaultAdmin.username" to "admin",
        "wikikt.defaultAdmin.password" to "test",
        "wikikt.database.type" to "h2",
        "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-$dbName-test;DB_CLOSE_DELAY=-1",
        "wikikt.database.h2.username" to "sa",
        "wikikt.database.h2.password" to "",
    )

    private suspend fun login(client: HttpClient): String =
        client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!
}
