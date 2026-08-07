package com.wikikt

import io.ktor.client.HttpClient
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

/**
 * Content width (Appearance > Content width): the site-wide capped/full default, and — only when the
 * admin allows it — the per-account preference and the per-request `?fullWidth=` override. The whole
 * effect is the `wiki-layout--full` class landing on the layout container (site.css lifts the cap),
 * so the assertions read the rendered HTML for that class.
 */
class ContentWidthTest {
    private val FULL_CLASS = "wiki-layout--full"

    private suspend fun saveAppearance(client: HttpClient, csrf: String, vararg fields: Pair<String, String>) {
        client.post("/a/settings/appearance") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); fields.forEach { append(it.first, it.second) } }))
        }
    }

    @Test
    fun `capped by default, and the URL param is inert while overrides are off`() = testApplication {
        environment { config = testConfig("content-width-default") }
        application { configure() }
        val guest = createClient { install(HttpCookies) }

        assertFalse(guest.get("/en/home").bodyAsText().contains(FULL_CLASS), "capped is the default")
        assertFalse(
            guest.get("/en/home?fullWidth=true").bodyAsText().contains(FULL_CLASS),
            "?fullWidth is ignored until the admin allows user overrides",
        )
    }

    @Test
    fun `a full-width site default applies to guests and the param cannot undo it while overrides are off`() = testApplication {
        environment { config = testConfig("content-width-site-full") }
        application { configure() }
        val admin = createClient { install(HttpCookies) }
        val csrf = admin.loginAsAdmin()
        saveAppearance(admin, csrf, "contentWidth" to "full")

        val guest = createClient { install(HttpCookies) }
        assertTrue(guest.get("/en/home").bodyAsText().contains(FULL_CLASS), "guests get the full-width default")
        assertTrue(
            guest.get("/en/home?fullWidth=false").bodyAsText().contains(FULL_CLASS),
            "no override allowed, so the param can't switch back to capped either",
        )
    }

    @Test
    fun `with overrides allowed the URL param switches a single request, strictly parsed`() = testApplication {
        environment { config = testConfig("content-width-param") }
        application { configure() }
        val admin = createClient { install(HttpCookies) }
        val csrf = admin.loginAsAdmin()
        saveAppearance(admin, csrf, "contentWidth" to "capped", "contentWidthUserChoice" to "1")

        val guest = createClient { install(HttpCookies) }
        assertFalse(guest.get("/en/home").bodyAsText().contains(FULL_CLASS), "the default is still capped")
        assertTrue(guest.get("/en/home?fullWidth=true").bodyAsText().contains(FULL_CLASS), "param widens one view")
        assertFalse(
            guest.get("/en/home?fullWidth=yes").bodyAsText().contains(FULL_CLASS),
            "only strict true/false parse; junk falls through to the default",
        )

        // And the reverse direction: a full-width site default narrowed for one request.
        saveAppearance(admin, csrf, "contentWidth" to "full", "contentWidthUserChoice" to "1")
        assertFalse(guest.get("/en/home?fullWidth=false").bodyAsText().contains(FULL_CLASS), "param narrows one view")
    }

    @Test
    fun `the account preference persists, loses to the URL param, and dies with the admin toggle`() = testApplication {
        environment { config = testConfig("content-width-account") }
        application { configure() }
        val admin = createClient { install(HttpCookies) }
        val csrf = admin.loginAsAdmin()

        // While overrides are off the account page doesn't offer the control at all.
        assertFalse(admin.get("/p/settings").bodyAsText().contains("""id="userContentWidth""""), "hidden while disallowed")

        saveAppearance(admin, csrf, "contentWidth" to "capped", "contentWidthUserChoice" to "1")
        assertTrue(admin.get("/p/settings").bodyAsText().contains("""id="userContentWidth""""), "offered once allowed")

        // Save a personal full-width preference; it applies to this account's page views.
        admin.post("/p/settings") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("contentWidth", "full") }))
        }
        assertTrue(admin.get("/en/home").bodyAsText().contains(FULL_CLASS), "preference beats the capped site default")
        assertFalse(admin.get("/en/home?fullWidth=false").bodyAsText().contains(FULL_CLASS), "explicit param beats the preference")

        // Turning the admin toggle back off silences the stored preference without clearing it.
        saveAppearance(admin, csrf, "contentWidth" to "capped")
        assertFalse(admin.get("/en/home").bodyAsText().contains(FULL_CLASS), "preference ignored while disallowed")
        saveAppearance(admin, csrf, "contentWidth" to "capped", "contentWidthUserChoice" to "1")
        assertTrue(admin.get("/en/home").bodyAsText().contains(FULL_CLASS), "and honoured again when re-allowed")
    }

    @Test
    fun `the appearance form round-trips both fields`() = testApplication {
        environment { config = testConfig("content-width-roundtrip") }
        application { configure() }
        val admin = createClient { install(HttpCookies) }
        val csrf = admin.loginAsAdmin()

        val before = admin.get("/a/settings/appearance").bodyAsText()
        assertTrue(before.contains("""name="contentWidth""""), "select rendered")
        assertFalse(before.contains("""id="contentWidthUserChoice" value="1" checked"""), "override off by default")

        saveAppearance(admin, csrf, "contentWidth" to "full", "contentWidthUserChoice" to "1")
        val after = admin.get("/a/settings/appearance").bodyAsText()
        assertTrue(after.contains("""<option value="full" selected>"""), "full width selected after save")
        assertTrue(after.contains("""id="contentWidthUserChoice" value="1" checked"""), "override checked after save")

        // An unknown submitted value falls back to (and displays as) the capped default.
        saveAppearance(admin, csrf, "contentWidth" to "bogus")
        assertTrue(
            admin.get("/a/settings/appearance").bodyAsText().contains("""<option value="capped" selected>"""),
            "junk value cleared to the capped default",
        )
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
}
