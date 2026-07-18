package com.wikikt

import com.wikikt.auth.ResetRequestThrottle
import com.wikikt.db.EmailQueueTable
import com.wikikt.service.EmailTemplateService
import com.wikikt.service.SettingsService
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswordResetFlowTest {

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
    private val tokenPattern = Regex("""token=([^&"\s]+)""")

    /** Enables mail on the catch-all site through the APP's own services, so its cached settings see it. */
    private fun enableMail(app: Application): UInt = runBlocking {
        val ctx = app.appContext
        val siteId = ctx.sites.catchAll()!!.id
        ctx.settings.setBool(siteId, SettingsService.MAIL_ENABLED, true)
        siteId
    }

    // Reset emails are now enqueued OFF the response path (timing-oracle fix), so the queue row may not
    // exist the instant the POST returns — poll briefly for it.
    private fun <T> awaitNonEmpty(app: Application, select: suspend () -> List<T>): List<T> = runBlocking {
        repeat(200) {
            val rows = select()
            if (rows.isNotEmpty()) return@runBlocking rows
            delay(25)
        }
        emptyList()
    }

    /** The recipients of every queued password-reset email, read from the app's own database. */
    private fun queuedResetRecipients(app: Application): List<String> = awaitNonEmpty(app) {
        suspendTransaction(app.appContext.database) {
            EmailQueueTable.selectAll()
                .where { EmailQueueTable.templateKey eq EmailTemplateService.PASSWORD_RESET }
                .map { it[EmailQueueTable.recipient] }
                .toList()
        }
    }

    /** The stored Mustache context of the single queued password-reset email. */
    private fun queuedResetContext(app: Application): String =
        awaitNonEmpty(app) {
            suspendTransaction(app.appContext.database) {
                EmailQueueTable.selectAll()
                    .where { EmailQueueTable.templateKey eq EmailTemplateService.PASSWORD_RESET }
                    .map { it[EmailQueueTable.context] }
                    .toList()
            }
        }.single()

    @Test
    fun `full reset flow updates the password, invalidates sessions, and is single-use`() = testApplication {
        ResetRequestThrottle.reset()
        lateinit var app: Application
        environment { config = testConfig("r2dbc:h2:mem:///wikikt-reset-flow;DB_CLOSE_DELAY=-1") }
        application { app = this; configure() }

        val admin = createClient { install(HttpCookies) }
        val adminCsrf = admin.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!
        admin.post("/u/v1/users") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", adminCsrf)
            setBody("""{"username":"victim","password":"oldpassword","email":"victim@example.com"}""")
        }
        enableMail(app)

        // Victim has a live session before the reset.
        val victim = createClient { install(HttpCookies) }
        victim.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"victim","password":"oldpassword"}""")
        }
        assertEquals(HttpStatusCode.OK, victim.get("/u/v1/auth/me").status, "victim starts with a live session")

        // Anonymous request-a-reset step (fresh client → its own anon CSRF cookie).
        val anon = createClient { install(HttpCookies) }
        val forgotCsrf = csrfPattern.find(anon.get("/forgot-password").bodyAsText())!!.groupValues[1]
        val forgotResp = anon.post("/forgot-password") {
            setBody(FormDataContent(Parameters.build { append("_csrf", forgotCsrf); append("email", "victim@example.com") }))
        }
        assertEquals(HttpStatusCode.OK, forgotResp.status)
        assertTrue(forgotResp.bodyAsText().contains("sent a link", ignoreCase = true))

        // Pull the reset link out of the enqueued email's context.
        val token = tokenPattern.find(queuedResetContext(app))!!.groupValues[1]

        // The reset form loads for a valid token.
        val resetForm = anon.get("/reset-password?token=$token").bodyAsText()
        assertTrue(resetForm.contains("New password"), "the reset form is shown for a valid token")
        val resetCsrf = csrfPattern.find(resetForm)!!.groupValues[1]

        // Set the new password.
        val resetResp = anon.post("/reset-password") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", resetCsrf); append("token", token)
                        append("password", "brandnewpass"); append("confirm", "brandnewpass")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, resetResp.status)
        assertTrue(resetResp.bodyAsText().contains("has been updated", ignoreCase = true))

        // The reset invalidated the victim's pre-existing session.
        assertEquals(HttpStatusCode.Unauthorized, victim.get("/u/v1/auth/me").status, "the old session is revoked")

        // The new password works; the old one doesn't.
        assertEquals(
            HttpStatusCode.OK,
            createClient { install(HttpCookies) }.post("/u/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"victim","password":"brandnewpass"}""")
            }.status,
            "the new password logs in",
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            createClient { install(HttpCookies) }.post("/u/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"victim","password":"oldpassword"}""")
            }.status,
            "the old password no longer works",
        )

        // Single-use: replaying the same link now shows the invalid-token page.
        val replay = anon.post("/reset-password") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", resetCsrf); append("token", token)
                        append("password", "anotherpass"); append("confirm", "anotherpass")
                    },
                ),
            )
        }
        assertTrue(replay.bodyAsText().contains("invalid", ignoreCase = true), "a spent token is rejected")
    }

    @Test
    fun `the request endpoint does not reveal whether an address exists`() = testApplication {
        ResetRequestThrottle.reset()
        lateinit var app: Application
        environment { config = testConfig("r2dbc:h2:mem:///wikikt-reset-enum;DB_CLOSE_DELAY=-1") }
        application { app = this; configure() }

        val admin = createClient { install(HttpCookies) }
        val adminCsrf = admin.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!
        admin.post("/u/v1/users") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", adminCsrf)
            setBody("""{"username":"known","password":"password1","email":"known@example.com"}""")
        }
        enableMail(app)

        val anon = createClient { install(HttpCookies) }
        val csrf = csrfPattern.find(anon.get("/forgot-password").bodyAsText())!!.groupValues[1]
        val known = anon.post("/forgot-password") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("email", "known@example.com") }))
        }
        val unknown = anon.post("/forgot-password") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("email", "nobody@example.com") }))
        }
        assertEquals(HttpStatusCode.OK, known.status)
        assertEquals(HttpStatusCode.OK, unknown.status)
        assertEquals(known.bodyAsText(), unknown.bodyAsText(), "the response must be identical for both")

        // ...but a link was only actually queued for the address that exists.
        assertEquals(listOf("known@example.com"), queuedResetRecipients(app), "only the real account got a reset email")
    }

    @Test
    fun `a reset POST without a valid CSRF token is rejected`() = testApplication {
        ResetRequestThrottle.reset()
        environment { config = testConfig("r2dbc:h2:mem:///wikikt-reset-csrf;DB_CLOSE_DELAY=-1") }
        application { configure() }

        val anon = createClient { install(HttpCookies) }
        // Prime the app (boot migrations + seed).
        assertEquals(HttpStatusCode.OK, anon.get("/login").status)
        // No prior GET to /forgot-password → no anon CSRF cookie → the POST must be forbidden.
        val resp = anon.post("/forgot-password") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("email=someone@example.com")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }
}
