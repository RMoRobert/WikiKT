package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage for API-key bearer auth: an admin mints a key from the console, the plaintext
 * is shown once, and that key then authenticates API calls as its owner. Revocation kills it.
 */
class ApiKeyAuthTest {
    private fun config(name: String) = MapApplicationConfig(
        "wikikt.defaultLocale" to "en",
        "wikikt.defaultAdmin.username" to "admin",
        "wikikt.defaultAdmin.password" to "test",
        "wikikt.database.type" to "h2",
        "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-$name;DB_CLOSE_DELAY=-1",
        "wikikt.database.h2.username" to "sa",
        "wikikt.database.h2.password" to "",
    )

    @Test
    fun `a minted key authenticates as its owner and revocation kills it`() = testApplication {
        environment { config = config("apikey-flow") }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val adminLogin = admin.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }
        assertEquals(HttpStatusCode.OK, adminLogin.status)
        val csrf = adminLogin.headers["X-CSRF-Token"]!!

        // Admin (id 1) mints a key for themselves via the console form.
        val createPage = admin.submitForm(
            url = "/a/api-keys",
            formParameters = Parameters.build {
                append("_csrf", csrf)
                append("name", "CI publisher")
                append("userId", "1")
                append("expiresIn", "")
            },
        )
        assertEquals(HttpStatusCode.OK, createPage.status)
        // The one-time plaintext is present in the reveal banner.
        val token = Regex("(wkt_[A-Za-z0-9_-]+)").find(createPage.bodyAsText())?.groupValues?.get(1)
        assertNotNull(token, "the created key's plaintext should be shown once")

        // A fresh, cookie-less client authenticates purely with the bearer token.
        val apiClient = createClient { }
        val me = apiClient.get("/u/v1/auth/me") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText().contains("admin"), "resolves to the owning user")

        // The key inherits the owner's permissions: admin can list users. No CSRF header needed
        // (cookie-less requests are not CSRF-prone), and no session cookie is involved.
        val users = apiClient.get("/u/v1/users") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, users.status)

        // Find the key's id from the list page, then revoke it.
        val listPage = admin.get("/a/api-keys").bodyAsText()
        val keyId = Regex("/a/api-keys/(\\d+)/revoke").find(listPage)!!.groupValues[1]
        val revoke = admin.submitForm(
            url = "/a/api-keys/$keyId/revoke",
            formParameters = Parameters.build { append("_csrf", csrf) },
        )
        // Redirects back to the list on success.
        assertTrue(revoke.status == HttpStatusCode.OK || revoke.status == HttpStatusCode.Found)

        // The revoked token no longer authenticates.
        val afterRevoke = apiClient.get("/u/v1/auth/me") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.Unauthorized, afterRevoke.status)
    }

    @Test
    fun `a non-admin manages only their own keys and cannot assign to others`() = testApplication {
        environment { config = config("apikey-selfservice") }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val adminCsrf = admin.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!
        // A plain user with no management permissions (default groups only).
        admin.submitForm(
            url = "/a/users",
            formParameters = Parameters.build {
                append("_csrf", adminCsrf); append("username", "editor"); append("password", "userpw")
            },
        )

        // Grant the implicit "User" group the "Create API keys" capability so the editor may self-serve.
        val userGroupId = admin.groupIdByName("User")
        admin.submitForm(
            url = "/a/groups/$userGroupId/permissions",
            formParameters = Parameters.build {
                append("_csrf", adminCsrf); append("create:apikeys", "on")
            },
        )

        val user = createClient { install(HttpCookies) }
        val userCsrf = user.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"editor","password":"userpw"}""")
        }.headers["X-CSRF-Token"]!!

        // The non-admin can now reach the self-service page and mint a key. Any userId param is ignored:
        // the key is owned by them, not admin (id 1).
        val created = user.submitForm(
            url = "/p/api-keys",
            formParameters = Parameters.build {
                append("_csrf", userCsrf); append("name", "my token"); append("expiresIn", "")
                append("userId", "1") // attempt to assign to admin — must be ignored
            },
        )
        assertEquals(HttpStatusCode.OK, created.status)
        val token = Regex("(wkt_[A-Za-z0-9_-]+)").find(created.bodyAsText())!!.groupValues[1]
        // The minted key authenticates as the editor, not admin.
        val apiClient = createClient { }
        val whoami = apiClient.get("/u/v1/auth/me") { header("Authorization", "Bearer $token") }.bodyAsText()
        assertTrue(whoami.contains("editor"), "key is owned by its creator, not the userId param")

        // The non-admin is forbidden from the admin keys area.
        assertEquals(HttpStatusCode.Forbidden, user.get("/a/api-keys").status)

        // Admin mints a key for themselves; the editor must not be able to revoke it (not their key).
        admin.submitForm(
            url = "/a/api-keys",
            formParameters = Parameters.build {
                append("_csrf", adminCsrf); append("name", "admin key"); append("userId", "1"); append("expiresIn", "")
            },
        )
        val adminKeyId = Regex("/a/api-keys/(\\d+)/revoke").find(admin.get("/a/api-keys").bodyAsText())!!.groupValues[1]
        val steal = user.submitForm(
            url = "/p/api-keys/$adminKeyId/revoke",
            formParameters = Parameters.build { append("_csrf", userCsrf) },
        )
        assertEquals(HttpStatusCode.NotFound, steal.status, "a user cannot act on another user's key by id")
    }

    @Test
    fun `key creation is gated by the capability but existing keys keep working`() = testApplication {
        environment { config = config("apikey-gate") }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val adminCsrf = admin.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!
        admin.submitForm(
            url = "/a/users",
            formParameters = Parameters.build {
                append("_csrf", adminCsrf); append("username", "member"); append("password", "userpw")
            },
        )

        val member = createClient { install(HttpCookies) }
        val memberCsrf = member.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"member","password":"userpw"}""")
        }.headers["X-CSRF-Token"]!!

        // Without the capability: creation is forbidden, but the (empty) list page still loads and
        // offers no "New key" button.
        assertEquals(HttpStatusCode.Forbidden, member.get("/p/api-keys/new").status)
        val denied = member.submitForm(
            url = "/p/api-keys",
            formParameters = Parameters.build { append("_csrf", memberCsrf); append("name", "x"); append("expiresIn", "") },
        )
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        val listBefore = member.get("/p/api-keys")
        assertEquals(HttpStatusCode.OK, listBefore.status)
        assertFalse(listBefore.bodyAsText().contains("/p/api-keys/new"), "no New key button without the capability")

        // Grant "Create API keys" on the User group; now the member can mint a key.
        val userGroupId = admin.groupIdByName("User")
        admin.submitForm(
            url = "/a/groups/$userGroupId/permissions",
            formParameters = Parameters.build { append("_csrf", adminCsrf); append("create:apikeys", "on") },
        )
        val created = member.submitForm(
            url = "/p/api-keys",
            formParameters = Parameters.build { append("_csrf", memberCsrf); append("name", "k"); append("expiresIn", "") },
        )
        assertEquals(HttpStatusCode.OK, created.status)
        val token = Regex("(wkt_[A-Za-z0-9_-]+)").find(created.bodyAsText())!!.groupValues[1]
        val api = createClient { }
        assertEquals(HttpStatusCode.OK, api.get("/u/v1/auth/me") { header("Authorization", "Bearer $token") }.status)

        // Revoke the capability again: new creation is blocked, but the existing key still authenticates
        // (disabling creation does not disable use of existing keys).
        admin.submitForm(
            url = "/a/groups/$userGroupId/permissions",
            formParameters = Parameters.build { append("_csrf", adminCsrf) },
        )
        assertEquals(HttpStatusCode.Forbidden, member.get("/p/api-keys/new").status)
        assertEquals(
            HttpStatusCode.OK,
            api.get("/u/v1/auth/me") { header("Authorization", "Bearer $token") }.status,
            "existing keys keep working after the capability is revoked",
        )
    }

    @Test
    fun `settings page hosts the api keys tab and admins get an admin-area pointer`() = testApplication {
        environment { config = config("apikey-settings") }
        application { configure() }

        val admin = createClient { install(HttpCookies); followRedirects = false }
        admin.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }

        // Bare /p redirects to the Settings page.
        val bare = admin.get("/p")
        assertEquals(HttpStatusCode.Found, bare.status)
        assertEquals("/p/settings", bare.headers["Location"])

        // The Settings page renders and links the API keys tab.
        val settings = admin.get("/p/settings")
        assertEquals(HttpStatusCode.OK, settings.status)
        val settingsBody = settings.bodyAsText()
        assertTrue(settingsBody.contains("nav-tabs"), "settings shows the account tab bar")
        assertTrue(settingsBody.contains("/p/api-keys"), "settings links the API keys tab")

        // The API keys tab shows the tab bar and — because admin can manage all keys — a pointer to
        // the Administration area.
        val keysBody = admin.get("/p/api-keys").bodyAsText()
        assertTrue(keysBody.contains("nav-tabs"), "api keys tab shows the account tab bar")
        assertTrue(keysBody.contains("/a/api-keys"), "admins see the manage-all-keys pointer")
    }

    @Test
    fun `a garbage bearer token is unauthorized`() = testApplication {
        environment { config = config("apikey-garbage") }
        application { configure() }
        val apiClient = createClient { }
        val res = apiClient.get("/u/v1/auth/me") { header("Authorization", "Bearer wkt_not-a-real-key") }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }
}

/** Looks up a group's numeric id by name via the admin JSON API. */
private suspend fun io.ktor.client.HttpClient.groupIdByName(name: String): String {
    val json = get("/u/v1/groups").bodyAsText()
    return Regex("\\{\"id\":\"(\\d+)\",\"name\":\"" + Regex.escape(name) + "\"")
        .find(json)!!.groupValues[1]
}
