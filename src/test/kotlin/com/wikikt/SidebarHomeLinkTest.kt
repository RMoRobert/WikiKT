package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.setBody
import io.ktor.http.Parameters
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sidebar's "Home" shortcut (Administration > Navigation): on by default, rendered as a plain nav
 * link when there's no view switch and folded into the switch as an icon button when there is one.
 */
class SidebarHomeLinkTest {
    @Test
    fun `the sidebar Home shortcut adapts to the nav mode and can be turned off`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-sidebarhome-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val pageUrl = "/en/$SAMPLE_PAGE_PATH"

        val csrf = admin.loginAsAdmin()
        admin.createSamplePage(csrf)

        suspend fun saveNav(mode: String, showHome: Boolean) = admin.post("/a/navigation/mode") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("mode", mode)
                        append("showEditMenuLink", "1")
                        if (showHome) append("showHome", "1")
                    },
                ),
            )
        }

        // Default (static menu, no switch): a plain sidebar link pointing at the reserved home path.
        val default = admin.get(pageUrl).bodyAsText()
        assertTrue(
            default.contains("""class="wk-nav-home-link" href="/home""""),
            "plain Home link shown by default, targeting the unprefixed home page",
        )
        assertFalse(default.contains("wk-nav-home-btn"), "no switch, so no switch-embedded Home button")

        // Both modes available: the switch appears and the shortcut moves inside it as an icon button.
        saveNav(mode = "both", showHome = true)
        val both = admin.get(pageUrl).bodyAsText()
        assertTrue(both.contains("wk-nav-switch"), "the view switch is shown in both mode")
        assertTrue(both.contains("wk-nav-home-btn"), "Home folded into the switch")
        assertFalse(both.contains("wk-nav-home-link"), "not also rendered as a standalone link")

        // Off: gone from both placements.
        saveNav(mode = "both", showHome = false)
        val bothOff = admin.get(pageUrl).bodyAsText()
        assertTrue(bothOff.contains("wk-nav-switch"), "the switch itself is unaffected")
        assertFalse(bothOff.contains("wk-nav-home-btn"), "Home button hidden when disabled")
        saveNav(mode = "static", showHome = false)
        assertFalse(admin.get(pageUrl).bodyAsText().contains("wk-nav-home-link"), "Home link hidden when disabled")
        assertFalse(
            admin.get("/a/navigation").bodyAsText().contains("""name="showHome" id="showHome" value="1" checked"""),
            "admin checkbox unchecked",
        )

        // On the home page itself the shortcut marks itself current.
        saveNav(mode = "static", showHome = true)
        assertTrue(
            admin.get("/en/home").bodyAsText().contains("""class="wk-nav-home-link active""""),
            "Home marked current on the home page",
        )
    }
}
