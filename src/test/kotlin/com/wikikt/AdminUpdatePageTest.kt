package com.wikikt

import io.ktor.client.HttpClient
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

/**
 * Administration > Updates: root-only gating, CSRF on the POSTs, and the sidebar link's visibility.
 * Test builds are dev builds (no `builtAt` stamp), so the page always renders the dev-build state
 * here and — by construction (UpdateCheck.NotApplicable short-circuits before any I/O) — the route
 * can never touch the network in tests. State-machine behavior is covered in UpdateServiceTest.
 */
class AdminUpdatePageTest {

    private fun config(name: String) = MapApplicationConfig(
        "wikikt.defaultLocale" to "en",
        "wikikt.defaultAdmin.username" to "admin",
        "wikikt.defaultAdmin.password" to "test",
        "wikikt.database.type" to "h2",
        "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-$name;DB_CLOSE_DELAY=-1",
        "wikikt.database.h2.username" to "sa",
        "wikikt.database.h2.password" to "",
    )

    private suspend fun HttpClient.postJson(path: String, csrf: String, body: String) =
        post(path) {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody(body)
        }

    private suspend fun HttpClient.postForm(path: String, csrf: String?, vararg fields: Pair<String, String>) =
        post(path) {
            setBody(
                FormDataContent(
                    Parameters.build {
                        if (csrf != null) append("_csrf", csrf)
                        for ((k, v) in fields) append(k, v)
                    },
                ),
            )
        }

    @Test
    fun `updates page is root-only and hidden from delegated admins`() = testApplication {
        environment { config = config("updates-gate") }
        application { configure() }

        val admin = createClient { install(HttpCookies); followRedirects = false }
        val adminCsrf = admin.loginAsAdmin()

        // Root sees the page (dev-build state in tests) and the sidebar link.
        val page = admin.get("/a/updates")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue(html.contains("development build"), "test (dev) builds render the dev-build state")
        assertTrue(html.contains(BuildInfo.version), "running version shown")
        val dashboard = admin.get("/a").bodyAsText()
        assertTrue(dashboard.contains("href=\"/a/updates\""), "root sees the Updates sidebar link")

        // A delegated manage:groups admin: no link, and the routes 403.
        val groupAdmins = admin.postJson("/u/v1/groups", adminCsrf, """{"name":"Group Admins","permissions":["manage:groups"]}""")
            .bodyAsText().let { Regex("\"id\":\"(\\d+)\"").find(it)!!.groupValues[1] }
        admin.postJson("/u/v1/users", adminCsrf, """{"username":"galtd","password":"pw-galtd-1","groupIds":["$groupAdmins"]}""")

        val ga = createClient { install(HttpCookies); followRedirects = false }
        val gaCsrf = ga.loginAsAdmin("galtd", "pw-galtd-1")
        assertFalse(ga.get("/a").bodyAsText().contains("href=\"/a/updates\""), "delegated admin is not shown the link")
        assertEquals(HttpStatusCode.Forbidden, ga.get("/a/updates").status)
        assertEquals(HttpStatusCode.Forbidden, ga.postForm("/a/updates/settings", gaCsrf, "updateChecks" to "enable").status)
        assertEquals(HttpStatusCode.Forbidden, ga.postForm("/a/updates/check", gaCsrf).status)
        assertEquals(HttpStatusCode.Forbidden, ga.postForm("/a/updates/dismiss", gaCsrf).status)

        // Anonymous: also forbidden, never a render.
        val anon = createClient { followRedirects = false }
        assertEquals(HttpStatusCode.Forbidden, anon.get("/a/updates").status)
    }

    @Test
    fun `updates POSTs require CSRF and the consent toggle round-trips`() = testApplication {
        environment { config = config("updates-csrf") }
        application { configure() }

        val admin = createClient { install(HttpCookies); followRedirects = false }
        val csrf = admin.loginAsAdmin()

        // Without a CSRF token the POST is rejected.
        val noCsrf = admin.postForm("/a/updates/settings", csrf = null, "updateChecks" to "enable")
        assertTrue(noCsrf.status != HttpStatusCode.Found, "missing CSRF must not be accepted (got ${noCsrf.status})")

        // With CSRF: accepted, PRG back to the page.
        val enable = admin.postForm("/a/updates/settings", csrf, "updateChecks" to "enable")
        assertEquals(HttpStatusCode.Found, enable.status)
        assertEquals("/a/updates", enable.headers["Location"])
        val disable = admin.postForm("/a/updates/settings", csrf, "updateChecks" to "disable")
        assertEquals(HttpStatusCode.Found, disable.status)

        // "Check now" with CSRF: accepted (and in a dev build performs no I/O at all).
        val check = admin.postForm("/a/updates/check", csrf)
        assertEquals(HttpStatusCode.Found, check.status)

        // Install: CSRF required; with CSRF it PRGs — and in this dev/unconfigured build every
        // server-side gate (release build, update available, updater heartbeat) is closed, so it
        // never writes a request no matter what the form claims.
        val installNoCsrf = admin.postForm("/a/updates/install", csrf = null, "confirmInstall" to "1")
        assertTrue(installNoCsrf.status != HttpStatusCode.Found, "missing CSRF must not be accepted")
        val install = admin.postForm("/a/updates/install", csrf, "confirmInstall" to "1")
        assertEquals(HttpStatusCode.Found, install.status)
        assertEquals("/a/updates", install.headers["Location"])

        // Dismissing the last result: CSRF required, PRG back to the page, and harmless here (no
        // updater configured means there is no outcome to hide).
        val dismissNoCsrf = admin.postForm("/a/updates/dismiss", csrf = null)
        assertTrue(dismissNoCsrf.status != HttpStatusCode.Found, "missing CSRF must not be accepted")
        val dismiss = admin.postForm("/a/updates/dismiss", csrf)
        assertEquals(HttpStatusCode.Found, dismiss.status)
        assertEquals("/a/updates", dismiss.headers["Location"])
    }
}
