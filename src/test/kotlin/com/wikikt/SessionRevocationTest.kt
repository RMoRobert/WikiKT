package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionRevocationTest {
    @Test
    fun `deleting a user revokes their active session`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-revoke-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val victim = createClient { install(HttpCookies) }

        val adminLogin = admin.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }
        assertEquals(HttpStatusCode.OK, adminLogin.status)
        val adminCsrf = adminLogin.headers["X-CSRF-Token"]!!

        val create = admin.post("/u/v1/users") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", adminCsrf)
            setBody("""{"username":"victim","password":"victimpw"}""")
        }
        assertEquals(HttpStatusCode.Created, create.status)
        val victimId = Regex("\"id\":\"(\\d+)\"").find(create.bodyAsText())!!.groupValues[1]

        // Victim logs in and confirms an active session.
        val victimLogin = victim.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"victim","password":"victimpw"}""")
        }
        assertEquals(HttpStatusCode.OK, victimLogin.status)
        assertEquals(HttpStatusCode.OK, victim.get("/u/v1/auth/me").status)

        // Admin deletes the victim.
        val del = admin.delete("/u/v1/users/$victimId") { header("X-CSRF-Token", adminCsrf) }
        assertEquals(HttpStatusCode.NoContent, del.status)

        // The victim's previously-valid session is now revoked.
        assertEquals(HttpStatusCode.Unauthorized, victim.get("/u/v1/auth/me").status)
    }

    @Test
    fun `a stale session cookie still reaches the login form instead of bouncing`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-stalelogin-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        // followRedirects=false so we can see whether /login serves the form or redirects away.
        val victim = createClient { install(HttpCookies); followRedirects = false }

        val adminCsrf = admin.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!
        val create = admin.post("/u/v1/users") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", adminCsrf)
            setBody("""{"username":"victim","password":"victimpw"}""")
        }
        val victimId = Regex("\"id\":\"(\\d+)\"").find(create.bodyAsText())!!.groupValues[1]

        // Victim logs in (real cookie + server session). While valid, /login redirects away from the form.
        victim.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"victim","password":"victimpw"}""")
        }
        assertEquals(HttpStatusCode.Found, victim.get("/login").status, "a valid session skips the login form")

        // Admin deletes the victim: the server session is gone but the victim's cookie remains (stale) —
        // the same situation as wiping the database out from under a live browser session.
        admin.delete("/u/v1/users/$victimId") { header("X-CSRF-Token", adminCsrf) }

        // The stale cookie must NOT bounce the user: /login serves the form (200) instead of redirecting.
        val res = victim.get("/login")
        assertEquals(HttpStatusCode.OK, res.status, "a stale cookie still reaches the login form")
        assertTrue(res.bodyAsText().contains("password", ignoreCase = true), "the login form is shown")
    }
}
