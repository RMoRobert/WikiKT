package com.wikikt

import com.wikikt.auth.MfaThrottle
import com.wikikt.auth.Totp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives the two-step login (password → second factor) through the real routes. */
class MfaLoginRouteTest {
    private fun testConfig(db: String) = MapApplicationConfig(
        "wikikt.defaultLocale" to "en",
        "wikikt.defaultAdmin.username" to "admin",
        "wikikt.defaultAdmin.password" to "test",
        "wikikt.database.type" to "h2",
        "wikikt.database.h2.r2dbcUrl" to db,
        "wikikt.database.h2.username" to "sa",
        "wikikt.database.h2.password" to "",
    )

    private val csrfPattern = Regex("""name="_csrf" value="([^"]+)"""")

    // Mustache HTML-escapes "'" → &#39;, so decode before asserting on message text.
    private fun String.htmlDecoded(): String = replace("&#39;", "'")

    private class Enrolled(val secret: ByteArray, val recoveryCodes: List<String>)

    /** Enrolls the seeded admin in TOTP through the app's own service. */
    private fun enrollAdmin(app: Application): Enrolled = runBlocking {
        val ctx = app.appContext
        val adminId = ctx.users.findByUsername("admin")!!.id
        val enr = ctx.mfa.beginTotpEnrollment(adminId, "WikiKT", "admin")
        val secret = Totp.base32Decode(enr.secretBase32)
        val codes = ctx.mfa.confirmTotpEnrollment(adminId, Totp.codeAt(secret, System.currentTimeMillis() / 1000))!!
        Enrolled(secret, codes)
    }

    private suspend fun HttpClient.formLogin(csrf: String) = post("/login") {
        setBody(
            FormDataContent(
                Parameters.build {
                    append("_csrf", csrf)
                    append("username", "admin")
                    append("password", "test")
                },
            ),
        )
    }

    private suspend fun HttpClient.mfaSubmit(csrf: String, code: String) = post("/login/mfa") {
        setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("code", code) }))
    }

    private suspend fun HttpClient.loggedIn() = get("/u/v1/auth/me").status == HttpStatusCode.OK

    @Test
    fun `an MFA-enabled account needs a code after the password`() = testApplication {
        MfaThrottle.reset()
        lateinit var app: Application
        environment { config = testConfig("r2dbc:h2:mem:///wikikt-mfa-login;DB_CLOSE_DELAY=-1") }
        application { app = this; configure() }
        val c = createClient { install(HttpCookies) }

        val loginCsrf = csrfPattern.find(c.get("/login").bodyAsText())!!.groupValues[1]
        val enrolled = enrollAdmin(app)

        // Correct password → a redirect to the second-factor step, and NO session yet.
        assertEquals(HttpStatusCode.Found, c.formLogin(loginCsrf).status, "password alone redirects to the MFA step")
        assertTrue(!c.loggedIn(), "no session is created until the code is verified")

        // The challenge page (following the redirect, like a browser).
        val challenge = c.get("/login/mfa").bodyAsText()
        assertTrue(challenge.contains("Two-factor authentication"), "the code challenge is shown")
        val mfaCsrf = csrfPattern.find(challenge)!!.groupValues[1]

        // A wrong code is rejected, still no session.
        val wrong = c.mfaSubmit(mfaCsrf, "000000").bodyAsText().htmlDecoded()
        assertTrue(wrong.contains("wasn't valid"), "a wrong code is rejected")
        assertTrue(!c.loggedIn(), "still not logged in after a wrong code")

        // The correct code (from a later step, since the enrolling code was already spent) completes login.
        val code = Totp.codeAt(enrolled.secret, System.currentTimeMillis() / 1000 + Totp.PERIOD_SECONDS)
        c.mfaSubmit(mfaCsrf, code)
        assertTrue(c.loggedIn(), "the second factor completes login")
    }

    @Test
    fun `a recovery code also completes the second factor`() = testApplication {
        MfaThrottle.reset()
        lateinit var app: Application
        environment { config = testConfig("r2dbc:h2:mem:///wikikt-mfa-recovery;DB_CLOSE_DELAY=-1") }
        application { app = this; configure() }
        val c = createClient { install(HttpCookies) }

        val loginCsrf = csrfPattern.find(c.get("/login").bodyAsText())!!.groupValues[1]
        val enrolled = enrollAdmin(app)

        c.formLogin(loginCsrf)
        val mfaCsrf = csrfPattern.find(c.get("/login/mfa").bodyAsText())!!.groupValues[1]

        c.mfaSubmit(mfaCsrf, enrolled.recoveryCodes.first())
        assertTrue(c.loggedIn(), "a recovery code completes login")
    }
}
