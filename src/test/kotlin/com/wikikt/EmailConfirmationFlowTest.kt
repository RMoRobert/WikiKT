package com.wikikt

import com.wikikt.db.UserStatus
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

/**
 * Email confirmation is a two-step GET -> POST: the GET only renders a confirm button (it must NOT spend
 * the single-use token, so a mail-security scanner or link prefetcher that merely fetches the link can't
 * burn it or silently activate the account), and the POST — carrying the anon-CSRF token issued on the GET
 * — consumes the token and activates the account.
 */
class EmailConfirmationFlowTest {

    private fun testConfig(dbUrl: String) = MapApplicationConfig(
        "wikikt.defaultLocale" to "en",
        "wikikt.defaultAdmin.username" to "admin",
        "wikikt.defaultAdmin.password" to "test",
        "wikikt.database.type" to "h2",
        "wikikt.database.h2.r2dbcUrl" to dbUrl,
        "wikikt.database.h2.username" to "sa",
        "wikikt.database.h2.password" to "",
    )

    private val csrfPattern = Regex("""name="_csrf" value="([^"]+)"""")

    /** Creates a PENDING_EMAIL account and a confirmation token through the app's own services. */
    private fun seedPending(app: Application): Pair<UInt, String> = runBlocking {
        val ctx = app.appContext
        val user = ctx.users.register("newbie", "newbie@example.com", "hunter2pw", defaultGroupId = null)
        user.id to ctx.emailVerification.createToken(user.id)
    }

    private fun statusOf(app: Application, userId: UInt): UserStatus = runBlocking {
        app.appContext.users.findById(userId)!!.status
    }

    @Test
    fun `a GET of the confirmation link does not spend the token, the POST activates the account`() = testApplication {
        lateinit var app: Application
        environment { config = testConfig("r2dbc:h2:mem:///wikikt-verify-flow;DB_CLOSE_DELAY=-1") }
        application { app = this; configure() }

        val client = createClient { install(HttpCookies) }
        // Prime the app (boot migrations + seed) before reaching into its services.
        assertEquals(HttpStatusCode.OK, client.get("/login").status)
        val (userId, token) = seedPending(app)

        // Simulate a mail-security scanner / prefetch: fetching the link (twice) must NOT consume the token
        // or activate the account — it only renders the confirm button.
        repeat(2) {
            val page = client.get("/verify-email?token=$token").bodyAsText()
            assertTrue(page.contains("Confirm my email"), "the confirm button is shown for a valid token")
        }
        assertEquals(UserStatus.PENDING_EMAIL, statusOf(app, userId), "a GET must not activate the account")

        // The human clicks Confirm -> the POST (carrying the anon-CSRF token from the page) activates it.
        val confirmPage = client.get("/verify-email?token=$token").bodyAsText()
        val csrf = csrfPattern.find(confirmPage)!!.groupValues[1]
        val confirmResp = client.post("/verify-email") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("token", token) }))
        }
        assertEquals(HttpStatusCode.OK, confirmResp.status)
        assertTrue(confirmResp.bodyAsText().contains("confirmed", ignoreCase = true))
        assertEquals(UserStatus.ACTIVE, statusOf(app, userId), "the POST activates the account")

        // Single-use: replaying the POST now shows the invalid-token page.
        val replay = client.post("/verify-email") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("token", token) }))
        }
        assertTrue(replay.bodyAsText().contains("invalid", ignoreCase = true), "a spent token is rejected")
    }

    @Test
    fun `a confirmation POST without a valid CSRF token is rejected and leaves the token unspent`() = testApplication {
        lateinit var app: Application
        environment { config = testConfig("r2dbc:h2:mem:///wikikt-verify-csrf;DB_CLOSE_DELAY=-1") }
        application { app = this; configure() }

        val boot = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.OK, boot.get("/login").status)
        val (userId, token) = seedPending(app)

        // A bogus CSRF token must be rejected (the confirmation link's secret token is the credential, but
        // the POST is still anon-CSRF-protected like every other anonymous state change).
        val resp = boot.post("/verify-email") {
            setBody(FormDataContent(Parameters.build { append("_csrf", "not-a-real-token"); append("token", token) }))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertEquals(UserStatus.PENDING_EMAIL, statusOf(app, userId), "a rejected POST leaves the token unspent")
    }
}
