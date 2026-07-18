package com.wikikt

import com.wikikt.auth.LoginThrottle
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SecurityHardeningTest {
    // The throttle is a JVM-wide singleton keyed partly by client host, which is the same for every
    // testApplication request — clear it so lockouts can't leak into other tests.
    @AfterTest
    fun clearThrottle() = LoginThrottle.reset()

    private fun ApplicationTestBuilder.configureApp(dbName: String) {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///$dbName;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }
    }

    @Test
    fun `JSON login is throttled after repeated failures`() = testApplication {
        configureApp("wikikt-sec-throttle-test")

        repeat(5) {
            val res = client.post("/u/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"brute-target","password":"wrong$it"}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, res.status, "attempt ${it + 1} should still be a plain 401")
        }
        val locked = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"brute-target","password":"wrong6"}""")
        }
        assertEquals(HttpStatusCode.TooManyRequests, locked.status, "the sixth attempt should be throttled")
    }

    @Test
    fun `login redirect rejects protocol-relative and backslash escapes`() = testApplication {
        configureApp("wikikt-sec-redirect-test")
        val client = createClient { install(HttpCookies); followRedirects = false }

        // GET /login issues the anonymous-form CSRF token the POST now requires (login-CSRF guard).
        val csrf = Regex("""name="_csrf" value="([^"]+)"""").find(client.get("/login").bodyAsText())!!.groupValues[1]
        suspend fun loginRedirect(target: String): String? {
            val res = client.post("/login") {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("_csrf", csrf)
                            append("username", "admin")
                            append("password", "test")
                            append("redirect", target)
                        },
                    ),
                )
            }
            assertEquals(HttpStatusCode.Found, res.status)
            return res.headers["Location"]
        }

        assertEquals("/", loginRedirect("//evil.com"), "protocol-relative URL must fall back to /")
        assertEquals("/", loginRedirect("/\\evil.com"), "backslash escape must fall back to /")
        assertEquals("/", loginRedirect("https://evil.com"), "absolute URL must fall back to /")
        assertEquals("/en/some/page", loginRedirect("/en/some/page"), "a normal site path is preserved")
    }

    @Test
    fun `logout is POST-only and CSRF-protected`() = testApplication {
        configureApp("wikikt-sec-logout-test")
        val client = createClient { install(HttpCookies); followRedirects = false }

        val login = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val csrf = assertNotNull(login.headers["X-CSRF-Token"])

        // GET must not end the session (a prefetched link or <img src="/logout"> would otherwise).
        assertEquals(HttpStatusCode.NotFound, client.get("/logout").status)
        assertEquals(HttpStatusCode.OK, client.get("/u/v1/auth/me").status, "GET /logout must not end the session")

        // POST without a token (what a cross-site form post looks like) is rejected; session survives.
        val noToken = client.post("/logout") { setBody(FormDataContent(Parameters.Empty)) }
        assertEquals(HttpStatusCode.Forbidden, noToken.status)
        assertEquals(HttpStatusCode.OK, client.get("/u/v1/auth/me").status, "CSRF-less logout must not end the session")

        // POST with the token logs out.
        val withToken = client.post("/logout") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf) }))
        }
        assertEquals(HttpStatusCode.Found, withToken.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/u/v1/auth/me").status, "session should be gone after logout")
    }

    @Test
    fun `security headers are present on responses`() = testApplication {
        configureApp("wikikt-sec-headers-test")

        val res = client.get("/login")
        assertEquals(HttpStatusCode.OK, res.status)
        val csp = assertNotNull(res.headers["Content-Security-Policy"], "CSP header missing")
        assertTrue("frame-ancestors 'self'" in csp)
        assertTrue("object-src 'none'" in csp)
        assertEquals("nosniff", res.headers["X-Content-Type-Options"])
        assertEquals("SAMEORIGIN", res.headers["X-Frame-Options"])
        assertEquals("strict-origin-when-cross-origin", res.headers["Referrer-Policy"])
        // secureCookie is off in this config, so HSTS must NOT be sent (plain-HTTP deployment).
        assertEquals(null, res.headers["Strict-Transport-Security"])
    }

    @Test
    fun `changing a password revokes the account's sessions`() = testApplication {
        configureApp("wikikt-sec-pwchange-test")

        val admin = createClient { install(HttpCookies) }
        val victim = createClient { install(HttpCookies) }

        val adminCsrf = assertNotNull(
            admin.post("/u/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"test"}""")
            }.headers["X-CSRF-Token"],
        )
        val create = admin.post("/u/v1/users") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", adminCsrf)
            setBody("""{"username":"victim","password":"victimpw"}""")
        }
        assertEquals(HttpStatusCode.Created, create.status)
        val victimId = assertNotNull(Regex("\"id\":\"(\\d+)\"").find(create.bodyAsText())).groupValues[1]

        victim.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"victim","password":"victimpw"}""")
        }
        assertEquals(HttpStatusCode.OK, victim.get("/u/v1/auth/me").status)

        // Admin resets the (compromised) account's password — the attacker's session must die with it.
        val update = admin.put("/u/v1/users/$victimId") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", adminCsrf)
            setBody("""{"password":"new-password"}""")
        }
        assertEquals(HttpStatusCode.OK, update.status)

        assertEquals(HttpStatusCode.Unauthorized, victim.get("/u/v1/auth/me").status, "old session must be revoked")
    }
}
