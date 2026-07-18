package com.wikikt

import com.wikikt.auth.LoginThrottle
import com.wikikt.auth.PasswordPolicy
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordPolicyTest {
    // The login throttle is a JVM-wide singleton; a failed login here must not leak into other tests.
    @AfterTest
    fun clearThrottle() = LoginThrottle.reset()

    @Test
    fun `policy accepts 5-to-72 chars, all characters, and rejects out-of-range`() {
        assertNotNull(PasswordPolicy.validate("abcd"), "4 chars is too short")
        assertNull(PasswordPolicy.validate("abcde"), "5 chars is the minimum")
        assertNull(PasswordPolicy.validate("a".repeat(72)), "72 bytes is allowed")
        assertNotNull(PasswordPolicy.validate("a".repeat(73)), "73 bytes exceeds bcrypt's limit")
        assertNull(PasswordPolicy.validate("correct horse battery staple"), "spaces are allowed")
        assertNull(PasswordPolicy.validate("p@ss'w0rd<>\"&"), "special characters are allowed")
        // A multibyte char counts by UTF-8 bytes: 24 emoji = 96 bytes, over the limit.
        assertNotNull(PasswordPolicy.validate("😀".repeat(24)), "byte length, not char count, is the cap")
    }

    @Test
    fun `validate honors a configurable minimum length`() {
        assertNull(PasswordPolicy.validate("abcde"), "5 chars still passes the default minimum")
        assertNotNull(PasswordPolicy.validate("abcde", minLength = 8), "5 chars fails an 8-char minimum")
        assertNull(PasswordPolicy.validate("abcdefgh", minLength = 8), "8 chars passes an 8-char minimum")
    }

    @Test
    fun `the configured minimum password length is enforced end-to-end`() = testApplication {
        configureApp("wikikt-pwpolicy-configurable", minPasswordLength = 12)
        val admin = createClient { install(HttpCookies) }
        val csrf = login(admin, "admin", "test")

        // An 8-char password that WOULD pass the default (5) is rejected under the configured minimum (12).
        val tooShort = admin.post("/u/v1/users") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"username":"midlen","password":"abcdefgh"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, tooShort.status)
        assertTrue(tooShort.bodyAsText().contains("at least 12"), "the error states the configured minimum")

        // A 12-char password is accepted.
        val ok = admin.post("/u/v1/users") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"username":"longenough","password":"abcdefghijkl"}""")
        }
        assertEquals(HttpStatusCode.Created, ok.status)
    }

    private fun ApplicationTestBuilder.configureApp(dbName: String, minPasswordLength: Int? = null) {
        environment {
            val entries = buildList {
                add("wikikt.defaultLocale" to "en")
                add("wikikt.defaultAdmin.username" to "admin")
                add("wikikt.defaultAdmin.password" to "test")
                add("wikikt.database.type" to "h2")
                add("wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///$dbName;DB_CLOSE_DELAY=-1")
                add("wikikt.database.h2.username" to "sa")
                add("wikikt.database.h2.password" to "")
                if (minPasswordLength != null) add("wikikt.security.minPasswordLength" to minPasswordLength.toString())
            }
            config = MapApplicationConfig(*entries.toTypedArray())
        }
        application { configure() }
    }

    private suspend fun login(client: HttpClient, username: String, password: String): String {
        val res = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"$username","password":"$password"}""")
        }
        return assertNotNull(res.headers["X-CSRF-Token"], "login should return a CSRF token")
    }

    @Test
    fun `API rejects a too-short password when creating a user`() = testApplication {
        configureApp("wikikt-pwpolicy-api")
        val admin = createClient { install(HttpCookies) }
        val csrf = login(admin, "admin", "test")

        val res = admin.post("/u/v1/users") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"username":"shorty","password":"abc"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("at least"), "the error should explain the minimum length")
    }

    @Test
    fun `self-service change rotates the current session and logs out other devices`() = testApplication {
        configureApp("wikikt-pwchange-flow")
        val device1 = createClient { install(HttpCookies) } // performs the change
        val device2 = createClient { install(HttpCookies) } // a second logged-in session

        val csrf1 = login(device1, "admin", "test")
        login(device2, "admin", "test")
        assertEquals(HttpStatusCode.OK, device2.get("/u/v1/auth/me").status)

        val change = device1.post("/p/password") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf1)
                        append("currentPassword", "test")
                        append("newPassword", "newpassword")
                        append("confirmPassword", "newpassword")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, change.status)
        assertTrue(change.bodyAsText().contains("Password changed"))

        // Device 1 stays logged in (its session was rotated, cookie refreshed).
        assertEquals(HttpStatusCode.OK, device1.get("/u/v1/auth/me").status, "the changing device stays signed in")
        // Device 2's session was dropped.
        assertEquals(HttpStatusCode.Unauthorized, device2.get("/u/v1/auth/me").status, "other devices are signed out")

        // The new password works; the old one no longer does.
        val fresh = createClient { install(HttpCookies) }
        assertEquals(
            HttpStatusCode.OK,
            fresh.post("/u/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"newpassword"}""")
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            fresh.post("/u/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"test"}""")
            }.status,
        )
    }

    @Test
    fun `self-service change rejects a wrong current password, a mismatch, and a too-short new password`() = testApplication {
        configureApp("wikikt-pwchange-validation")
        val client = createClient { install(HttpCookies) }
        val csrf = login(client, "admin", "test")

        suspend fun change(current: String, next: String, confirm: String) = client.post("/p/password") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("currentPassword", current)
                        append("newPassword", next)
                        append("confirmPassword", confirm)
                    },
                ),
            )
        }

        assertTrue(change("wrong", "newpassword", "newpassword").bodyAsText().contains("current password is incorrect"))
        assertTrue(change("test", "newpassword", "different").bodyAsText().contains("do not match"))
        assertTrue(change("test", "abc", "abc").bodyAsText().contains("at least"))

        // None of the rejected attempts changed anything: the original password still works.
        assertEquals(HttpStatusCode.OK, client.get("/u/v1/auth/me").status)
    }
}
