package com.wikikt

import com.wikikt.auth.RegisterThrottle
import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.db.UserStatus
import com.wikikt.model.CreateUserRequest
import com.wikikt.model.UpdateUserRequest
import com.wikikt.service.MigrationService
import com.wikikt.service.SettingsService
import com.wikikt.service.UserService
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
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Usernames are case-insensitive identifiers: every write goes through [UserService.normalizeUsername]
 * (trim + lowercase) and every lookup normalizes its input the same way, so "Bob" and "bob" are the
 * same account everywhere — login, self-registration, admin creation, the JSON API, and seeding.
 */
class UsernameNormalizationTest {

    private fun service() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-username-norm-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        UserService(database)
    }

    @Test
    fun `create stores the username trimmed and lowercased`() = runBlocking {
        val users = service()
        val created = users.create(CreateUserRequest("  Alice.Smith ", "hunter2pw"))
        assertEquals("alice.smith", created.username)
    }

    @Test
    fun `lookup and authentication accept any casing`() = runBlocking {
        val users = service()
        users.create(CreateUserRequest("bob", "hunter2pw"))
        assertNotNull(users.findByUsername("BOB"), "case-variant lookup resolves the account")
        assertNotNull(users.authenticate("Bob", "hunter2pw"), "case-variant login reaches the account")
        assertNull(users.authenticate("Bob", "wrong"), "the password still has to match")
    }

    @Test
    fun `a case-variant duplicate hits the unique index`() = runBlocking {
        val users = service()
        users.create(CreateUserRequest("carol", "hunter2pw"))
        assertFails { users.create(CreateUserRequest("Carol", "hunter2pw")) }
        assertEquals(1, users.list().size, "no second account was created")
    }

    @Test
    fun `register normalizes and reclaims a stale pending name across casings`() = runBlocking {
        val users = service()
        val first = users.register("Dave", "dave@example.com", "hunter2pw", defaultGroupId = null)
        assertEquals("dave", first.username)
        assertEquals(UserStatus.PENDING_EMAIL, first.status)
        // A never-confirmed registration is reclaimable — including under a different casing.
        val second = users.register("DAVE", "dave2@example.com", "hunter2pw", defaultGroupId = null)
        assertEquals("dave", second.username)
        assertEquals(1, users.list().size, "the stale pending row was replaced, not duplicated")
    }

    @Test
    fun `update lowercases a username change`() = runBlocking {
        val users = service()
        val id = users.create(CreateUserRequest("erin", "hunter2pw")).id
        val updated = users.update(id, UpdateUserRequest(username = "Erin.New"))
        assertEquals("erin.new", updated?.username)
    }

    private fun testConfig(dbUrl: String, adminUsername: String = "admin") = MapApplicationConfig(
        "wikikt.defaultLocale" to "en",
        "wikikt.defaultAdmin.username" to adminUsername,
        "wikikt.defaultAdmin.password" to "test",
        "wikikt.database.type" to "h2",
        "wikikt.database.h2.r2dbcUrl" to dbUrl,
        "wikikt.database.h2.username" to "sa",
        "wikikt.database.h2.password" to "",
    )

    @Test
    fun `a mixed-case configured admin seeds one lowercase account that any-casing login reaches`() = testApplication {
        lateinit var app: Application
        environment {
            config = testConfig("r2dbc:h2:mem:///wikikt-username-seed-${System.nanoTime()};DB_CLOSE_DELAY=-1", adminUsername = "Admin")
        }
        application { app = this; configure() }

        val client = createClient { install(HttpCookies) }
        // The application{} block runs lazily on first request; prime it so seeding has happened.
        assertEquals(HttpStatusCode.OK, client.get("/login").status)
        val seeded = runBlocking { app.appContext.users.list().single() }
        assertEquals("admin", seeded.username, "the configured 'Admin' is stored normalized")
        // loginAsAdmin throws if the login doesn't produce a session/CSRF token.
        client.loginAsAdmin(username = "ADMIN", password = "test")
    }

    @Test
    fun `registering a case variant of an existing username is rejected as taken`() = testApplication {
        RegisterThrottle.reset()
        lateinit var app: Application
        environment {
            config = testConfig("r2dbc:h2:mem:///wikikt-username-taken-${System.nanoTime()};DB_CLOSE_DELAY=-1")
        }
        application { app = this; configure() }

        val client = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.OK, client.get("/login").status)
        runBlocking {
            val ctx = app.appContext
            val siteId = ctx.sites.catchAll()!!.id
            ctx.settings.setBool(siteId, SettingsService.MAIL_ENABLED, true)
            ctx.settings.setBool(siteId, SettingsService.REGISTRATION_ENABLED, true)
        }
        val csrf = Regex("""name="_csrf" value="([^"]+)"""").find(client.get("/register").bodyAsText())!!.groupValues[1]

        // "Admin" is a case variant of the seeded active "admin" — without normalization this would
        // mint a lookalike account one case-swap away from the administrator.
        val resp = client.post("/register") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("username", "Admin")
                        append("email", "imposter@example.com")
                        append("password", "hunter2pw")
                        append("confirm", "hunter2pw")
                        append("homepage", "")
                    },
                )
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("already taken"), "a case variant of a taken username is rejected inline")
    }

    @Test
    fun `the admin console rejects a taken username with a friendly message`() = testApplication {
        environment {
            config = testConfig("r2dbc:h2:mem:///wikikt-username-admin-taken-${System.nanoTime()};DB_CLOSE_DELAY=-1")
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()
        suspend fun postForm(path: String, vararg fields: Pair<String, String>) = client.post(path) {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        for ((k, v) in fields) append(k, v)
                    },
                )
            )
        }

        // A normal user to rename later, created over the JSON API.
        val created = client.post("/u/v1/users") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"username":"frank","password":"hunter2pw"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val frankId = Regex("\"id\":\"(\\d+)\"").find(created.bodyAsText())!!.groupValues[1]

        // Create form: a case variant of the seeded "admin" re-renders with the friendly message
        // instead of surfacing the unique-index violation.
        val dupCreate = postForm("/a/users", "username" to "Admin", "password" to "hunter2pw")
        assertEquals(HttpStatusCode.OK, dupCreate.status)
        assertTrue(dupCreate.bodyAsText().contains("This username cannot be registered"), "create form shows the friendly message")

        // Edit form: renaming frank onto the admin's name gets the same message...
        val dupRename = postForm("/a/users/$frankId", "username" to "ADMIN", "displayName" to "")
        assertEquals(HttpStatusCode.OK, dupRename.status)
        assertTrue(dupRename.bodyAsText().contains("This username cannot be registered"), "edit form shows the friendly message")

        // ...while re-submitting his own name in a different casing is not a collision.
        val selfRename = postForm("/a/users/$frankId", "username" to "Frank", "displayName" to "")
        assertEquals(HttpStatusCode.Found, selfRename.status, "a user's own name in a different casing saves fine")
    }
}
