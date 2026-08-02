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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The asset manager lives at `/f`, outside `/a`, but is listed in the admin console's Content section
 * and renders inside the console shell — otherwise the tool is findable only by knowing the URL. These
 * tests pin the sidebar link, and the per-item gating that keeps that shell honest for a delegated
 * editor whose only grant is over assets.
 */
class AssetManagerShellTest {

    @Test
    fun `the console links Assets, and the manager renders in the console shell`() = testApplication {
        environment { config = h2Config("wikikt-asset-shell-admin") }
        application { configure() }

        val admin = newClient()
        admin.apiLogin("admin", "test")

        // The link is what makes the manager findable: it has to be in the sidebar of the ordinary
        // admin pages, not only on /f itself.
        val pages = admin.get("/a/pages").bodyAsText()
        assertTrue(
            pages.contains("""<a class="admin-nav-link" href="/f">"""),
            "the admin sidebar links the asset manager",
        )

        val manager = admin.get("/f")
        assertEquals(HttpStatusCode.OK, manager.status)
        val body = manager.bodyAsText()
        assertTrue(body.contains("""class="admin-sidebar""""), "/f keeps the admin shell for an admin")
        assertTrue(
            body.contains("""<a class="admin-nav-link active" href="/f">"""),
            "the Assets item is highlighted while on /f",
        )
        // /f works on the site serving the request, not the console's site-switcher selection, so the
        // switcher must not appear here claiming otherwise.
        assertFalse(body.contains("admin-site-switcher"), "no site switcher on the asset manager")

        // The maintenance tools hang off the same shell.
        assertTrue(admin.get("/f/unused").bodyAsText().contains("""class="admin-sidebar""""))
        assertTrue(admin.get("/f/broken").bodyAsText().contains("""class="admin-sidebar""""))
    }

    @Test
    fun `an editor granted only assets sees Assets and nothing else in the sidebar`() = testApplication {
        environment { config = h2Config("wikikt-asset-shell-editor") }
        application { configure() }

        val admin = newClient()
        val adminCsrf = admin.apiLogin("admin", "test")
        val editors = admin.createGroup(adminCsrf, "Asset editors", emptyList())
        admin.addRule(adminCsrf, editors, verbs = listOf("read:pages", "read:assets", "write:assets", "manage:assets"))
        admin.createUser(adminCsrf, "ed", "pw-ed-12345", listOf(editors))

        val editor = newClient()
        editor.apiLogin("ed", "pw-ed-12345")

        val manager = editor.get("/f")
        assertEquals(HttpStatusCode.OK, manager.status, "write:assets alone still opens the manager")
        val body = manager.bodyAsText()
        assertTrue(body.contains("""<a class="admin-nav-link active" href="/f">"""), "Assets, highlighted")
        // A write:assets grant also carries the Pages list (canManagePages counts it), so those links
        // belong here. Everything site-wide needs manage:groups/manage:users and must stay hidden — a
        // delegated editor should never be shown a link that 403s on click.
        listOf("/a/fragments", "/a/infoboxes", "/a/users", "/a/groups", "/a/settings", "/a/security", "/a/sites")
            .forEach { assertFalse(body.contains("""href="$it""""), "no link to $it for an asset-only editor") }
        assertFalse(body.contains("admin-site-switcher"), "the site switcher is admin-only and site-scoped")
    }

    // --- helpers ---

    private fun ApplicationTestBuilder.newClient(): HttpClient =
        createClient { install(HttpCookies); followRedirects = false }

    private fun h2Config(dbName: String) = MapApplicationConfig(
        "wikikt.defaultLocale" to "en",
        "wikikt.defaultAdmin.username" to "admin",
        "wikikt.defaultAdmin.password" to "test",
        "wikikt.database.type" to "h2",
        "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///$dbName;DB_CLOSE_DELAY=-1",
        "wikikt.database.h2.username" to "sa",
        "wikikt.database.h2.password" to "",
    )
}

private suspend fun HttpClient.apiLogin(username: String, password: String): String {
    val res = post("/u/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username","password":"$password"}""")
    }
    assertEquals(HttpStatusCode.OK, res.status, "login $username")
    return res.headers["X-CSRF-Token"] ?: error("no CSRF token for $username")
}

private suspend fun HttpClient.createGroup(csrf: String, name: String, perms: List<String>): String {
    val permsJson = perms.joinToString(",") { "\"$it\"" }
    val res = post("/u/v1/groups") {
        contentType(ContentType.Application.Json)
        header("X-CSRF-Token", csrf)
        setBody("""{"name":"$name","permissions":[$permsJson]}""")
    }
    assertEquals(HttpStatusCode.Created, res.status, "create group $name")
    return Regex("\"id\":\"(\\d+)\"").find(res.bodyAsText())!!.groupValues[1]
}

private suspend fun HttpClient.createUser(csrf: String, username: String, password: String, groupIds: List<String>) {
    val gids = groupIds.joinToString(",") { "\"$it\"" }
    val res = post("/u/v1/users") {
        contentType(ContentType.Application.Json)
        header("X-CSRF-Token", csrf)
        setBody("""{"username":"$username","password":"$password","groupIds":[$gids]}""")
    }
    assertEquals(HttpStatusCode.Created, res.status, "create user $username")
}

/**
 * Grants [verbs] under a [pattern] prefix to a group, via the admin console's rule form. The manager's
 * own gate only asks whether the user holds write:assets *somewhere*, so the scope is immaterial here.
 */
private suspend fun HttpClient.addRule(csrf: String, groupId: String, verbs: List<String>, pattern: String = "docs") {
    val res = post("/a/groups/$groupId/rules") {
        setBody(
            FormDataContent(
                Parameters.build {
                    append("_csrf", csrf)
                    append("effect", "ALLOW")
                    append("matchType", "PREFIX")
                    append("pattern", pattern)
                    verbs.forEach { append(it, "on") }
                },
            ),
        )
    }
    assertEquals(HttpStatusCode.Found, res.status, "add rule for $verbs")
}
