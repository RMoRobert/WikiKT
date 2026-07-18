package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.service.EmailService
import com.wikikt.service.EmailStatus
import com.wikikt.service.EmailTemplateService
import com.wikikt.service.MigrationService
import com.wikikt.service.SettingsService
import com.wikikt.service.SiteService
import com.wikikt.service.email.EmailSender
import com.wikikt.service.email.MailSettings
import com.wikikt.service.email.OutboundEmail
import com.wikikt.service.email.SendResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records every send; returns a scripted result (default: success). */
private class FakeSender(var result: SendResult = SendResult.Sent) : EmailSender {
    val sent = mutableListOf<OutboundEmail>()
    override suspend fun send(settings: MailSettings, email: OutboundEmail): SendResult {
        sent += email
        return result
    }
}

class EmailServiceTest {
    private var dbCounter = 0

    private fun fixture() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-email-test-${dbCounter++};DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = SiteService(database).create("Test site", null, isCatchAll = true).id
        val settings = SettingsService(database)
        val templates = EmailTemplateService(database)
        Triple(database, siteId, settings) to templates
    }

    private suspend fun configureMail(settings: SettingsService, siteId: UInt, enabled: Boolean = true) {
        settings.setBool(siteId, SettingsService.MAIL_ENABLED, enabled)
        settings.set(siteId, SettingsService.MAIL_SMTP_HOST, "smtp.example.com")
        settings.set(siteId, SettingsService.MAIL_FROM_ADDRESS, "noreply@example.com")
    }

    @Test
    fun `disabled mail leaves the row pending, enabling then draining sends it`() = runBlocking {
        val (f, templates) = fixture()
        val (database, siteId, settings) = f
        val sender = FakeSender()
        val email = EmailService(database, templates, settings, sender)

        val id = email.enqueue(siteId, "user@example.com", EmailTemplateService.WELCOME, mapOf("username" to "alice"))

        // Mail off: the worker must not send, and the row stays PENDING.
        email.processPending()
        assertTrue(sender.sent.isEmpty())
        assertEquals(EmailStatus.PENDING.name, email.recentQueue(siteId).single().status)

        // Turn it on and drain: now it sends and the row flips to SENT.
        configureMail(settings, siteId)
        email.processPending()
        assertEquals(1, sender.sent.size)
        val row = email.recentQueue(siteId).single { it.id == id }
        assertEquals(EmailStatus.SENT.name, row.status)
        assertNull(row.lastError)
    }

    @Test
    fun `welcome template renders its subject and body from context`() = runBlocking {
        val (f, templates) = fixture()
        val (_, siteId, _) = f
        val rendered = templates.render(
            siteId, EmailTemplateService.WELCOME, "user@example.com",
            mapOf("siteName" to "MyWiki", "username" to "alice", "displayName" to "Alice", "loginUrl" to "https://w/login"),
        )!!
        assertEquals("Welcome to MyWiki", rendered.subject)
        assertTrue(rendered.text.contains("Username: alice"), "body should interpolate the username")
        assertTrue(rendered.text.contains("https://w/login"), "body should interpolate the login URL")
        assertNull(rendered.html)
    }

    @Test
    fun `a transient failure retries with backoff, then parks as dead-letter`() = runBlocking {
        val (f, templates) = fixture()
        val (database, siteId, settings) = f
        val sender = FakeSender(SendResult.Failed("smtp down", transient = true))
        val email = EmailService(database, templates, settings, sender)
        configureMail(settings, siteId)

        val id = email.enqueue(siteId, "user@example.com", EmailTemplateService.WELCOME, mapOf("username" to "a"))

        // First drain: transient failure → FAILED, one attempt, next attempt pushed into the future.
        // Base the clock on real time since enqueue stamps nextAttemptAt with the real clock.
        val now = System.currentTimeMillis() + 1_000
        email.processPending(now)
        var row = email.recentQueue(siteId).single { it.id == id }
        assertEquals(EmailStatus.FAILED.name, row.status)
        assertEquals(1, row.attempts)
        assertTrue(row.nextAttemptAt > now, "backoff should schedule the next attempt in the future")
        assertEquals("smtp down", row.lastError)

        // Keep draining past each backoff window until it exhausts retries and parks as DEAD_LETTER.
        var clock = now
        repeat(EmailService.MAX_ATTEMPTS + 1) {
            clock = email.recentQueue(siteId).single { it.id == id }.nextAttemptAt + 1
            email.processPending(clock)
        }
        row = email.recentQueue(siteId).single { it.id == id }
        assertEquals(EmailStatus.DEAD_LETTER.name, row.status)
        assertEquals(EmailService.MAX_ATTEMPTS, row.attempts)
    }

    @Test
    fun `a permanent failure parks immediately without retrying`() = runBlocking {
        val (f, templates) = fixture()
        val (database, siteId, settings) = f
        val sender = FakeSender(SendResult.Failed("bad recipient", transient = false))
        val email = EmailService(database, templates, settings, sender)
        configureMail(settings, siteId)

        val id = email.enqueue(siteId, "nope@example.com", EmailTemplateService.WELCOME, mapOf("username" to "a"))
        email.processPending()
        val row = email.recentQueue(siteId).single { it.id == id }
        assertEquals(EmailStatus.DEAD_LETTER.name, row.status)
        assertEquals(1, row.attempts)
    }

    @Test
    fun `template override wins until reset, then falls back to default`() = runBlocking {
        val (f, templates) = fixture()
        val (_, siteId, _) = f

        assertTrue(templates.content(siteId, EmailTemplateService.WELCOME)!!.isDefault)

        templates.saveOverride(siteId, EmailTemplateService.WELCOME, "Hi {{username}}", "Body {{username}}", null, userId = null)
        val overridden = templates.content(siteId, EmailTemplateService.WELCOME)!!
        assertTrue(!overridden.isDefault)
        assertEquals("Hi {{username}}", overridden.subject)
        val rendered = templates.render(siteId, EmailTemplateService.WELCOME, "u@e.com", mapOf("username" to "bob"))!!
        assertEquals("Hi bob", rendered.subject)

        templates.resetToDefault(siteId, EmailTemplateService.WELCOME)
        assertTrue(templates.content(siteId, EmailTemplateService.WELCOME)!!.isDefault)
    }
}
