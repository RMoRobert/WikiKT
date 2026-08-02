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
 * Asset verbs are granted independently of page verbs, so "can edit every page" does NOT imply "can
 * manage assets" — an ordinary page-editor group has whatever `write:assets` its rule was given, and
 * nothing more. That is deliberate (an operator may want prose editors who can't fill the disk), but it
 * means the editing surfaces have to be honest about it in both directions:
 *
 *  - granted write:assets → the asset manager is reachable AND findable (sidebar link)
 *  - not granted        → the manager 403s, and the Insert Image picker must not offer Upload/Edit
 *                         controls that would 403; browsing and inserting existing images still works
 */
class EditorAssetAccessTest {

    @Test
    fun `a page editor without asset rights can insert images but is offered no upload or edit`() = testApplication {
        environment { config = h2Config("wikikt-editor-assets-none") }
        application { configure() }

        val admin = newClient()
        val adminCsrf = admin.apiLogin("admin", "test")
        val editors = admin.createGroup(adminCsrf, "Prose editors")
        // Full page rights, deliberately no write:assets / manage:assets.
        admin.addRule(adminCsrf, editors, listOf("read:pages", "write:pages", "delete:pages", "manage:pages"))
        admin.createUser(adminCsrf, "prose", "pw-prose-1234", editors)

        val ed = newClient()
        ed.apiLogin("prose", "pw-prose-1234")

        assertEquals(HttpStatusCode.Forbidden, ed.get("/f").status, "the manager needs write:assets")
        // Reading assets is separate and seeded broadly, so the picker can still list what exists.
        assertEquals(HttpStatusCode.OK, ed.get("/u/v1/assets").status, "existing images stay insertable")
        // They reach the console (page rights), and it must not advertise a manager they can't open.
        val console = ed.get("/a/pages")
        assertEquals(HttpStatusCode.OK, console.status)
        assertFalse(console.bodyAsText().contains("""href="/f""""), "no Assets link without write:assets")
        // The editor tells the picker what to offer; both flags must be off here.
        val editor = ed.get("/e/en/docs/draft").bodyAsText()
        assertTrue(editor.contains("""data-can-upload-assets="false""""), "picker Upload suppressed")
        assertTrue(editor.contains("""data-can-manage-assets="false""""), "picker Edit suppressed")
    }

    @Test
    fun `the same editor granted asset rights gets the manager, the link, and the picker controls`() = testApplication {
        environment { config = h2Config("wikikt-editor-assets-granted") }
        application { configure() }

        val admin = newClient()
        val adminCsrf = admin.apiLogin("admin", "test")
        val editors = admin.createGroup(adminCsrf, "Full editors")
        admin.addRule(
            adminCsrf,
            editors,
            listOf("read:pages", "write:pages", "read:assets", "write:assets", "manage:assets"),
        )
        admin.createUser(adminCsrf, "full", "pw-full-12345", editors)

        val ed = newClient()
        ed.apiLogin("full", "pw-full-12345")

        assertEquals(HttpStatusCode.OK, ed.get("/f").status, "write:assets opens the manager")
        assertTrue(ed.get("/a/pages").bodyAsText().contains("""href="/f""""), "and it is linked in the console")
        val editor = ed.get("/e/en/docs/draft").bodyAsText()
        assertTrue(editor.contains("""data-can-upload-assets="true""""), "picker may upload")
        assertTrue(editor.contains("""data-can-manage-assets="true""""), "picker may edit")
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

private suspend fun HttpClient.createGroup(csrf: String, name: String): String {
    val res = post("/u/v1/groups") {
        contentType(ContentType.Application.Json)
        header("X-CSRF-Token", csrf)
        setBody("""{"name":"$name","permissions":[]}""")
    }
    assertEquals(HttpStatusCode.Created, res.status, "create group $name")
    return Regex("\"id\":\"(\\d+)\"").find(res.bodyAsText())!!.groupValues[1]
}

private suspend fun HttpClient.createUser(csrf: String, username: String, password: String, groupId: String) {
    val res = post("/u/v1/users") {
        contentType(ContentType.Application.Json)
        header("X-CSRF-Token", csrf)
        setBody("""{"username":"$username","password":"$password","groupIds":["$groupId"]}""")
    }
    assertEquals(HttpStatusCode.Created, res.status, "create user $username")
}

/** Grants [verbs] under the docs/ prefix via the console's rule form (the coarse gates ignore scope). */
private suspend fun HttpClient.addRule(csrf: String, groupId: String, verbs: List<String>) {
    val res = post("/a/groups/$groupId/rules") {
        setBody(
            FormDataContent(
                Parameters.build {
                    append("_csrf", csrf)
                    append("effect", "ALLOW")
                    append("matchType", "PREFIX")
                    append("pattern", "docs")
                    verbs.forEach { append(it, "on") }
                },
            ),
        )
    }
    assertEquals(HttpStatusCode.Found, res.status, "add rule for $verbs")
}
