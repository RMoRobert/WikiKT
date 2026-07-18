package com.wikikt

import com.wikikt.auth.RegisterThrottle
import com.wikikt.service.SettingsService
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The self-registration route carries a honeypot field (hidden from people). A submission that fills it is
 * treated as a bot: it gets the same generic "check your email" response but creates no account and sends
 * no mail. A genuine submission (empty honeypot) registers normally.
 */
class RegistrationRouteTest {

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

    /** Turns on self-registration (needs mail enabled too) through the app's own services. */
    private fun enableRegistration(app: Application) = runBlocking {
        val ctx = app.appContext
        val siteId = ctx.sites.catchAll()!!.id
        ctx.settings.setBool(siteId, SettingsService.MAIL_ENABLED, true)
        ctx.settings.setBool(siteId, SettingsService.REGISTRATION_ENABLED, true)
    }

    private fun userExists(app: Application, username: String): Boolean = runBlocking {
        app.appContext.users.list().any { it.username == username }
    }

    /** The account is created off the response path (app.launch), so poll briefly for it. */
    private fun awaitUser(app: Application, username: String): Boolean = runBlocking {
        repeat(200) {
            if (userExists(app, username)) return@runBlocking true
            delay(25)
        }
        false
    }

    @Test
    fun `a filled honeypot silently succeeds without creating an account`() = testApplication {
        RegisterThrottle.reset()
        lateinit var app: Application
        environment { config = testConfig("r2dbc:h2:mem:///wikikt-honeypot-bot;DB_CLOSE_DELAY=-1") }
        application { app = this; configure() }

        val client = createClient { install(HttpCookies) }
        // The application{} block runs lazily on first request; prime it, then enable registration.
        assertEquals(HttpStatusCode.OK, client.get("/login").status)
        enableRegistration(app)
        val csrf = csrfPattern.find(client.get("/register").bodyAsText())!!.groupValues[1]

        val resp = client.post("/register") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("username", "botuser")
                        append("email", "bot@example.com")
                        append("password", "hunter2pw")
                        append("confirm", "hunter2pw")
                        append("homepage", "http://spam.example") // honeypot filled → treated as a bot
                    },
                )
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("check your inbox", ignoreCase = true), "shows the same generic success page")

        // The honeypot short-circuits before any account work, so no account is ever created. Give the
        // (skipped) async path a moment to prove it truly did nothing.
        runBlocking { delay(200) }
        assertTrue(!userExists(app, "botuser"), "a tripped honeypot creates no account")
    }

    @Test
    fun `a genuine submission with an empty honeypot registers normally`() = testApplication {
        RegisterThrottle.reset()
        lateinit var app: Application
        environment { config = testConfig("r2dbc:h2:mem:///wikikt-honeypot-human;DB_CLOSE_DELAY=-1") }
        application { app = this; configure() }

        val client = createClient { install(HttpCookies) }
        // The application{} block runs lazily on first request; prime it, then enable registration.
        assertEquals(HttpStatusCode.OK, client.get("/login").status)
        enableRegistration(app)
        val csrf = csrfPattern.find(client.get("/register").bodyAsText())!!.groupValues[1]

        val resp = client.post("/register") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("username", "realuser")
                        append("email", "real@example.com")
                        append("password", "hunter2pw")
                        append("confirm", "hunter2pw")
                        append("homepage", "") // empty → a real person
                    },
                )
            )
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("check your inbox", ignoreCase = true))
        assertTrue(awaitUser(app, "realuser"), "a genuine submission creates the pending account")
    }
}
