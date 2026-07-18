package com.wikikt

import com.wikikt.auth.Totp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Drives the /p/security enrollment flow end-to-end through the real routes and templates. */
class MfaEnrollmentRouteTest {
    private fun testConfig(db: String) = MapApplicationConfig(
        "wikikt.defaultLocale" to "en",
        "wikikt.defaultAdmin.username" to "admin",
        "wikikt.defaultAdmin.password" to "test",
        "wikikt.database.type" to "h2",
        "wikikt.database.h2.r2dbcUrl" to db,
        "wikikt.database.h2.username" to "sa",
        "wikikt.database.h2.password" to "",
    )

    private val secretPattern = Regex("secret=([A-Z2-7]+)")

    // Mustache HTML-escapes entities (e.g. '=' → &#61;, "'" → &#39;); decode so assertions read naturally.
    private fun String.htmlDecoded(): String = this
        .replace("&#61;", "=").replace("&#39;", "'").replace("&#38;", "&").replace("&amp;", "&")

    private suspend fun login(c: HttpClient): String =
        c.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"] ?: error("login did not return a CSRF token")

    private suspend fun HttpClient.postForm(path: String, csrf: String, vararg fields: Pair<String, String>): HttpResponse =
        post(path) {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        for ((k, v) in fields) append(k, v)
                    },
                ),
            )
        }

    @Test
    fun `a user enrolls in TOTP, sees recovery codes, and can disable it`() = testApplication {
        environment { config = testConfig("r2dbc:h2:mem:///wikikt-mfa-enroll;DB_CLOSE_DELAY=-1") }
        application { configure() }
        val c = createClient { install(HttpCookies) }
        val csrf = login(c)

        assertTrue(c.get("/p/security").bodyAsText().contains("Set up two-factor authentication"), "starts off")

        // Enable → the setup page carries the otpauth secret we can turn into a live code.
        val setup = c.postForm("/p/security/enable", csrf).bodyAsText().htmlDecoded()
        assertTrue(setup.contains("setup key", ignoreCase = true), "the setup page shows the manual-entry key")
        val secret = secretPattern.find(setup)!!.groupValues[1]
        val code = Totp.codeAt(Totp.base32Decode(secret), System.currentTimeMillis() / 1000)

        // Confirm → MFA on, recovery codes revealed once.
        val confirmed = c.postForm("/p/security/confirm", csrf, "code" to code).bodyAsText().htmlDecoded()
        assertTrue(confirmed.contains("now on", ignoreCase = true), "confirmation enables MFA")
        assertTrue(confirmed.contains("recovery codes", ignoreCase = true), "recovery codes are shown")
        assertTrue(c.get("/p/security").bodyAsText().contains("recovery codes remaining"), "now shows the on state")

        // Disable is password-gated.
        assertTrue(
            c.postForm("/p/security/disable", csrf, "currentPassword" to "wrong").bodyAsText().contains("password is incorrect"),
            "a wrong password won't disable MFA",
        )
        assertTrue(
            c.postForm("/p/security/disable", csrf, "currentPassword" to "test").bodyAsText().contains("turned off", ignoreCase = true),
            "the correct password disables MFA",
        )
        assertTrue(c.get("/p/security").bodyAsText().contains("Set up two-factor authentication"), "back to off")
    }

    @Test
    fun `a wrong confirmation code does not enable MFA`() = testApplication {
        environment { config = testConfig("r2dbc:h2:mem:///wikikt-mfa-badcode;DB_CLOSE_DELAY=-1") }
        application { configure() }
        val c = createClient { install(HttpCookies) }
        val csrf = login(c)

        c.postForm("/p/security/enable", csrf)
        val res = c.postForm("/p/security/confirm", csrf, "code" to "000000").bodyAsText().htmlDecoded()
        assertTrue(res.contains("didn't match"), "a wrong code is rejected")
        assertFalse(res.contains("now on", ignoreCase = true), "MFA is not enabled")
        assertTrue(res.contains("setup key", ignoreCase = true), "the setup page is re-shown for another try")
    }
}
