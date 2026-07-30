package com.wikikt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Per-color-mode appearance settings: header bar colors, the search box surface preference, the
 * header/sidebar divider, and the sidebar menu heading color.
 *
 * All are resolved server-side but consumed by CSS that branches on the *live* root theme, so the
 * assertions here are on what the page hands the browser — the mode-scoped `<style>` blocks from
 * partials/brand-style.hbs and the `data-wk-search-*` attributes on the search form.
 */
class AppearanceColorsTest {
    private suspend fun saveAppearance(client: HttpClient, csrf: String, vararg fields: Pair<String, String>) {
        client.post("/a/settings/appearance") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); fields.forEach { append(it.first, it.second) } }))
        }
    }

    @Test
    fun `header colors are scoped per color mode and flip the bar tint when light`() = testApplication {
        environment { config = testConfig("header-colors") }
        application { configure() }
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = login(client)

        // A pale bar in light mode, a near-black one in dark mode: each must stay inside its own mode.
        saveAppearance(client, csrf, "siteHeaderColor" to "#eeeeee", "siteHeaderColorDark" to "#101010")

        client.get("/a").bodyAsText().let { html ->
            assertTrue(
                html.contains(":root:not([data-bs-theme='dark']) .wk-navbar,:root:not([data-bs-theme='dark']) .editor-bar{--wk-navbar-bg:#eeeeee;--wk-navbar-tint:0,0,0;}"),
                "light-mode bar is scoped to light mode and flips the tint to black for its pale color",
            )
            assertTrue(
                html.contains(":root[data-bs-theme='dark'] .wk-navbar,:root[data-bs-theme='dark'] .editor-bar{--wk-navbar-bg:#101010;}"),
                "dark-mode bar is scoped to dark mode and keeps the default white tint",
            )
        }

        // Unset the dark-mode color: light mode keeps its color, dark mode falls back to the site.css bar.
        saveAppearance(client, csrf, "siteHeaderColor" to "#eeeeee", "siteHeaderColorDark" to "")
        client.get("/a").bodyAsText().let { html ->
            assertTrue(html.contains("--wk-navbar-bg:#eeeeee"), "light-mode color survives")
            assertFalse(html.contains(":root[data-bs-theme='dark'] .wk-navbar"), "no dark-mode override emitted when unset")
        }

        // Junk is not injected as CSS.
        saveAppearance(client, csrf, "siteHeaderColor" to "#eeeeee", "siteHeaderColorDark" to "red; }")
        client.get("/a").bodyAsText().let { html ->
            assertFalse(html.contains("red; }"), "invalid dark header color rejected")
        }
    }

    @Test
    fun `sidebar menu heading color is per color mode and independent of the sidebar color`() = testApplication {
        environment { config = testConfig("nav-heading") }
        application { configure() }
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = login(client)

        // Unset: nothing is emitted, so site.css's fallback to the background-derived muted tint stands.
        assertFalse(
            client.get("/a").bodyAsText().contains("--wk-sidebar-heading-fg"),
            "no heading override emitted when unset",
        )

        // Set both modes, with no sidebar background color at all — the heading color must not depend on it.
        saveAppearance(client, csrf, "siteNavHeadingColor" to "#aa0000", "siteNavHeadingColorDark" to "#00bb00")
        client.get("/a").bodyAsText().let { html ->
            assertTrue(
                html.contains(":root:not([data-bs-theme='dark']){--wk-sidebar-heading-fg:#aa0000;}"),
                "light-mode heading color scoped to light mode",
            )
            assertTrue(
                html.contains(":root[data-bs-theme='dark']{--wk-sidebar-heading-fg:#00bb00;}"),
                "dark-mode heading color scoped to dark mode",
            )
        }

        // One mode alone is fine: the other keeps falling back.
        saveAppearance(client, csrf, "siteNavHeadingColor" to "#aa0000", "siteNavHeadingColorDark" to "")
        client.get("/a").bodyAsText().let { html ->
            assertTrue(html.contains("--wk-sidebar-heading-fg:#aa0000;"), "light-mode color survives alone")
            assertFalse(html.contains(":root[data-bs-theme='dark']{--wk-sidebar-heading-fg"), "dark mode still falls back")
        }

        // Junk is not injected as CSS.
        saveAppearance(client, csrf, "siteNavHeadingColor" to "blue; }")
        client.get("/a").bodyAsText().let { html ->
            assertFalse(html.contains("blue; }"), "invalid heading color rejected")
            assertFalse(html.contains("--wk-sidebar-heading-fg"), "invalid heading color clears the override")
        }
    }

    @Test
    fun `sidebar divider color is per color mode and leaves the in-sidebar dividers alone`() = testApplication {
        environment { config = testConfig("sidebar-divider") }
        application { configure() }
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = login(client)

        // Unset: nothing is emitted, so site.css's --wk-sidebar-line-derived hairline stands.
        assertFalse(
            client.get("/a").bodyAsText().contains("--wk-sidebar-header-line"),
            "no divider override emitted when unset",
        )

        saveAppearance(client, csrf, "siteSidebarHeaderLineColor" to "#aa0000", "siteSidebarHeaderLineColorDark" to "#00bb00")
        client.get("/a").bodyAsText().let { html ->
            assertTrue(
                html.contains(":root:not([data-bs-theme='dark']){--wk-sidebar-header-line:var(--bs-border-width) solid #aa0000;}"),
                "light-mode divider scoped to light mode, keeping the site's border width",
            )
            assertTrue(
                html.contains(":root[data-bs-theme='dark']{--wk-sidebar-header-line:var(--bs-border-width) solid #00bb00;}"),
                "dark-mode divider scoped to dark mode",
            )
            // The menu dividers and "Edit menu" separator ride --wk-sidebar-line, which must stay untouched.
            assertFalse(html.contains("--wk-sidebar-line:"), "in-sidebar dividers keep their derived tint")
        }

        // One mode alone is fine: the other keeps falling back.
        saveAppearance(client, csrf, "siteSidebarHeaderLineColor" to "#aa0000", "siteSidebarHeaderLineColorDark" to "")
        client.get("/a").bodyAsText().let { html ->
            assertTrue(html.contains("solid #aa0000;"), "light-mode color survives alone")
            assertFalse(html.contains(":root[data-bs-theme='dark']{--wk-sidebar-header-line"), "dark mode still falls back")
        }

        // Junk is not injected as CSS.
        saveAppearance(client, csrf, "siteSidebarHeaderLineColor" to "red; }")
        client.get("/a").bodyAsText().let { html ->
            assertFalse(html.contains("red; }"), "invalid divider color rejected")
            assertFalse(html.contains("--wk-sidebar-header-line"), "invalid divider color clears the override")
        }
    }

    @Test
    fun `search box surface follows the admin preference in each color mode`() = testApplication {
        environment { config = testConfig("search-box") }
        application { configure() }
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = login(client)

        suspend fun surfaces(): Pair<String, String> {
            val html = client.get("/a").bodyAsText()
            val light = Regex("""data-wk-search-light="(\w+)"""").find(html)?.groupValues?.get(1)
            val dark = Regex("""data-wk-search-dark="(\w+)"""").find(html)?.groupValues?.get(1)
            return (light ?: "missing") to (dark ?: "missing")
        }

        // Default: follows the site theme, like every other input on the page.
        assertTrue(surfaces() == "light" to "dark", "default follows the color mode, got ${surfaces()}")

        // Pinned surfaces ignore both the color mode and the bar color.
        saveAppearance(client, csrf, "searchBoxTheme" to "light", "siteHeaderColorDark" to "#eeeeee")
        assertTrue(surfaces() == "light" to "light", "always-light pins both modes, got ${surfaces()}")
        saveAppearance(client, csrf, "searchBoxTheme" to "dark", "siteHeaderColorDark" to "#eeeeee")
        assertTrue(surfaces() == "dark" to "dark", "always-dark pins both modes, got ${surfaces()}")

        // An unknown value can't strand the box: it clears back to the follow-the-theme default. Covers
        // the retired luminance-derived option too, so a site that stored "auto" degrades to the default
        // rather than to a stuck surface.
        saveAppearance(client, csrf, "searchBoxTheme" to "auto", "siteHeaderColorDark" to "#eeeeee")
        assertTrue(surfaces() == "light" to "dark", "retired preference falls back to theme, got ${surfaces()}")
        saveAppearance(client, csrf, "searchBoxTheme" to "bogus", "siteHeaderColorDark" to "#eeeeee")
        assertTrue(surfaces() == "light" to "dark", "unknown preference falls back to theme, got ${surfaces()}")

        // The admin select offers every option and pre-selects the stored one.
        saveAppearance(client, csrf, "searchBoxTheme" to "light")
        client.get("/a/settings/appearance").bodyAsText().let { html ->
            // Scoped to this select's own markup: the page's *theme mode* select carries an unrelated
            // value="auto" option, which a whole-page search would mistake for the retired one.
            val select = Regex("""<select[^>]*name="searchBoxTheme".*?</select>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.value
            assertTrue(select != null, "search box select rendered")
            assertTrue(select!!.contains("""<option value="light" selected>"""), "stored preference pre-selected")
            assertTrue(select.contains("""<option value="dark" """), "every option offered")
            assertFalse(select.contains("""value="auto""""), "retired option no longer offered")
        }
    }

    @Test
    fun `brand colors are per color mode and contrast-validated on save`() = testApplication {
        environment { config = testConfig("brand-colors") }
        application { configure() }
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = login(client)

        // Valid: a dark-enough primary (>=4.5:1 on white) and a light-enough dark-mode link (on the dark body).
        saveAppearance(client, csrf, "siteBrandColor" to "#0172ad", "siteBrandColorDark" to "#6ea8fe")
        client.get("/a").bodyAsText().let { html ->
            assertTrue(html.contains(":root{--bs-primary:#0172ad;}"), "primary set at :root (both modes)")
            assertTrue(
                html.contains(":root:not([data-bs-theme='dark']){--bs-link-color:#0172ad;--bs-link-hover-color:#0172ad;}"),
                "light-mode link color scoped to light mode",
            )
            assertTrue(
                html.contains(":root[data-bs-theme='dark']{--bs-link-color:#6ea8fe;--bs-link-hover-color:#6ea8fe;}"),
                "dark-mode link color scoped to dark mode",
            )
        }

        // Dark color unset: dark-mode links fall back to Bootstrap's default — no override emitted, so a
        // light-tuned primary never lands unreadable on the dark body.
        saveAppearance(client, csrf, "siteBrandColor" to "#0172ad", "siteBrandColorDark" to "")
        client.get("/a").bodyAsText().let { html ->
            assertTrue(html.contains("--bs-primary:#0172ad;"), "primary survives")
            assertFalse(html.contains(":root[data-bs-theme='dark']{--bs-link-color"), "no dark-mode link override when unset")
        }

        // Too-light primary (fails 4.5:1 on white) is rejected: the form re-renders with a message and
        // NOTHING is persisted (the prior valid value stands).
        val badLight = client.post("/a/settings/appearance") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("siteBrandColor", "#dddddd") }))
        }.bodyAsText()
        assertTrue(badLight.contains("too light"), "low-contrast primary rejected with a message")
        client.get("/a").bodyAsText().let { html ->
            assertFalse(html.contains("--bs-primary:#dddddd"), "rejected primary not persisted")
            assertTrue(html.contains("--bs-primary:#0172ad;"), "prior value untouched by the failed save")
        }

        // Too-dark dark-mode color (fails 4.5:1 on the dark body) is likewise rejected.
        val badDark = client.post("/a/settings/appearance") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf); append("siteBrandColor", "#0172ad"); append("siteBrandColorDark", "#333333")
                    },
                ),
            )
        }.bodyAsText()
        assertTrue(badDark.contains("too dark"), "low-contrast dark-mode color rejected with a message")
        assertFalse(client.get("/a").bodyAsText().contains("--bs-link-color:#333333"), "rejected dark color not persisted")
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
