package com.wikikt.service

import com.wikikt.db.EmailQueueTable
import com.wikikt.model.nowMillis
import com.wikikt.service.email.EmailSender
import com.wikikt.service.email.MailSettings
import com.wikikt.service.email.SendResult
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/** Delivery lifecycle of a queued email. */
enum class EmailStatus { PENDING, SENT, FAILED, DEAD_LETTER }

/** One outbox row, for the admin queue view. */
data class EmailQueueEntry(
    val id: UInt,
    val recipient: String,
    val templateKey: String,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Long,
    val sentAt: Long?,
    val nextAttemptAt: Long,
)

/** Counts by status for the admin queue summary. */
data class EmailQueueCounts(val pending: Int, val sent: Int, val failed: Int, val deadLetter: Int)

/**
 * The durable outbox: enqueues outbound mail to [EmailQueueTable] and drains it from a background
 * worker ([processPending]). Callers (user creation, password reset, admin notifications) only ever
 * [enqueue]; nothing sends on the request path, so a slow or down SMTP server never blocks a web
 * request. SMTP connection settings are per-site runtime settings (see [SettingsService] `MAIL_*`),
 * resolved per email at send time so each site can use its own relay.
 *
 * Retry: a failed transient send is retried on an exponential backoff up to [MAX_ATTEMPTS], after
 * which the row is parked as DEAD_LETTER (visible in the admin queue, retryable by hand). A permanent
 * failure (auth, invalid recipient) is parked immediately.
 */
class EmailService(
    private val database: R2dbcDatabase,
    private val templates: EmailTemplateService,
    private val settings: SettingsService,
    private val sender: EmailSender,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val contextSerializer = MapSerializer(String.serializer(), String.serializer())

    /**
     * Queues an email for later delivery by the worker. Returns the new row id. [context] is the map of
     * Mustache variables the template is rendered with (recipient is added automatically). Never sends
     * inline — enqueue is cheap and can't fail on a bad SMTP server.
     */
    suspend fun enqueue(
        siteId: UInt,
        recipient: String,
        templateKey: String,
        context: Map<String, String>,
    ): UInt {
        val now = nowMillis()
        val payload = json.encodeToString(contextSerializer, context + ("recipient" to recipient))
        return suspendTransaction(database) {
            EmailQueueTable.insert {
                it[EmailQueueTable.siteId] = siteId
                it[EmailQueueTable.recipient] = recipient
                it[EmailQueueTable.templateKey] = templateKey
                it[EmailQueueTable.context] = payload
                it[EmailQueueTable.status] = EmailStatus.PENDING.name
                it[EmailQueueTable.attempts] = 0
                it[EmailQueueTable.nextAttemptAt] = now
                it[EmailQueueTable.createdAt] = now
            }[EmailQueueTable.id].value
        }
    }

    /**
     * Renders and sends the built-in "test" template to [recipient] immediately (not via the queue), so
     * the admin gets synchronous feedback that SMTP works. Records the outcome as the site's last-run
     * status. Returns a human-readable result string.
     */
    suspend fun sendTest(siteId: UInt, recipient: String): String {
        val cfg = mailSettings(siteId)
        if (!cfg.enabled) return "Mail is disabled. Enable it and save before sending a test."
        if (!cfg.isConfigured) return "SMTP isn't configured yet (host and From address are required)."
        val email = templates.render(siteId, EmailTemplateService.TEST, recipient, mapOf("siteName" to siteName(siteId)))
            ?: return "Test template is missing."
        val result = sender.send(cfg, email)
        recordRun(siteId, result)
        return when (result) {
            is SendResult.Sent -> "Test email sent to $recipient."
            is SendResult.Failed -> "Test failed: ${result.reason}"
        }
    }

    /**
     * Drains up to [BATCH_SIZE] due rows (PENDING, or FAILED whose backoff has elapsed). For each: resolve
     * its site's SMTP settings, render its template, attempt delivery, and record the outcome. Rows for a
     * site whose mail is disabled/unconfigured are left PENDING (they'll flush once it's set up). Called on
     * a timer from the application worker; each row is isolated so one failure can't starve the batch.
     */
    suspend fun processPending(now: Long = nowMillis()) {
        val due = suspendTransaction(database) {
            EmailQueueTable.selectAll()
                .where {
                    (EmailQueueTable.nextAttemptAt lessEq now) and
                        ((EmailQueueTable.status eq EmailStatus.PENDING.name) or (EmailQueueTable.status eq EmailStatus.FAILED.name))
                }
                .orderBy(EmailQueueTable.nextAttemptAt, SortOrder.ASC)
                .limit(BATCH_SIZE)
                .map { it.toEntry() to it[EmailQueueTable.siteId].value }
                .toList()
        }
        if (due.isEmpty()) return

        // Cache resolved SMTP settings per site so a batch of mail to one site reads settings once.
        val settingsBySite = HashMap<UInt, MailSettings>()
        for ((entry, siteId) in due) {
            val cfg = settingsBySite.getOrPut(siteId) { mailSettings(siteId) }
            if (!cfg.enabled || !cfg.isConfigured) continue // leave PENDING until the site is set up
            deliver(entry, siteId, cfg, now)
        }
    }

    private suspend fun deliver(entry: EmailQueueEntry, siteId: UInt, cfg: MailSettings, now: Long) {
        val context = runCatching { json.decodeFromString(contextSerializer, entryContext(entry.id)) }
            .getOrDefault(emptyMap())
        val email = templates.render(siteId, entry.templateKey, entry.recipient, context)
        if (email == null) {
            // Unknown template key (e.g. removed) — nothing to retry; park it.
            markDeadLetter(entry.id, "Unknown template: ${entry.templateKey}", entry.attempts + 1)
            return
        }
        val result = sender.send(cfg, email)
        recordRun(siteId, result)
        when (result) {
            is SendResult.Sent -> markSent(entry.id)
            is SendResult.Failed -> {
                val attempts = entry.attempts + 1
                if (!result.transient || attempts >= MAX_ATTEMPTS) {
                    markDeadLetter(entry.id, result.reason, attempts)
                } else {
                    markRetry(entry.id, result.reason, attempts, now + backoffMs(attempts))
                }
            }
        }
    }

    // --- Admin queue view ---

    /** The most recent [limit] outbox rows for [siteId], newest first. */
    suspend fun recentQueue(siteId: UInt, limit: Int = 50): List<EmailQueueEntry> = suspendTransaction(database) {
        EmailQueueTable.selectAll()
            .where { EmailQueueTable.siteId eq siteId }
            .orderBy(EmailQueueTable.createdAt, SortOrder.DESC)
            .limit(limit)
            .map { it.toEntry() }
            .toList()
    }

    /** Status tallies for [siteId], for the queue summary line. */
    suspend fun queueCounts(siteId: UInt): EmailQueueCounts = suspendTransaction(database) {
        val byStatus = EmailQueueTable.selectAll()
            .where { EmailQueueTable.siteId eq siteId }
            .map { it[EmailQueueTable.status] }
            .toList()
            .groupingBy { it }.eachCount()
        EmailQueueCounts(
            pending = byStatus[EmailStatus.PENDING.name] ?: 0,
            sent = byStatus[EmailStatus.SENT.name] ?: 0,
            failed = byStatus[EmailStatus.FAILED.name] ?: 0,
            deadLetter = byStatus[EmailStatus.DEAD_LETTER.name] ?: 0,
        )
    }

    /** Requeues a failed/dead-letter row for an immediate retry (resets it to PENDING, now). Site-scoped
     *  so an admin managing one site can't touch another's outbox. Returns false if no such row. */
    suspend fun retry(siteId: UInt, id: UInt): Boolean = suspendTransaction(database) {
        EmailQueueTable.update({ (EmailQueueTable.id eq id) and (EmailQueueTable.siteId eq siteId) }) {
            it[status] = EmailStatus.PENDING.name
            it[nextAttemptAt] = nowMillis()
            it[lastError] = null
        } > 0
    }

    /** Deletes an outbox row (site-scoped). Returns false if no such row. */
    suspend fun deleteEntry(siteId: UInt, id: UInt): Boolean = suspendTransaction(database) {
        EmailQueueTable.deleteWhere { (EmailQueueTable.id eq id) and (EmailQueueTable.siteId eq siteId) } > 0
    }

    // --- Settings helpers ---

    /** Resolves [siteId]'s SMTP settings from the app-settings store into a [MailSettings]. */
    suspend fun mailSettings(siteId: UInt): MailSettings {
        val s = SettingsService
        return MailSettings(
            enabled = settings.getBool(siteId, s.MAIL_ENABLED),
            host = settings.get(siteId, s.MAIL_SMTP_HOST).orEmpty().trim(),
            port = settings.get(siteId, s.MAIL_SMTP_PORT)?.toIntOrNull() ?: s.DEFAULT_MAIL_SMTP_PORT,
            security = settings.get(siteId, s.MAIL_SMTP_SECURITY)?.takeIf { it in s.MAIL_SECURITY_OPTIONS } ?: s.DEFAULT_MAIL_SECURITY,
            username = settings.get(siteId, s.MAIL_SMTP_USERNAME).orEmpty(),
            password = settings.get(siteId, s.MAIL_SMTP_PASSWORD).orEmpty(),
            fromAddress = settings.get(siteId, s.MAIL_FROM_ADDRESS).orEmpty().trim(),
            fromName = settings.get(siteId, s.MAIL_FROM_NAME)?.trim()?.ifBlank { null },
        )
    }

    private suspend fun siteName(siteId: UInt): String =
        settings.get(siteId, SettingsService.SITE_NAME)?.ifBlank { null } ?: SettingsService.DEFAULT_SITE_NAME

    private suspend fun recordRun(siteId: UInt, result: SendResult) {
        settings.set(siteId, SettingsService.MAIL_LAST_RUN_AT, nowMillis().toString())
        settings.setBool(siteId, SettingsService.MAIL_LAST_OK, result is SendResult.Sent)
        val msg = when (result) {
            is SendResult.Sent -> "Sent"
            is SendResult.Failed -> result.reason.take(500)
        }
        settings.set(siteId, SettingsService.MAIL_LAST_RESULT, msg)
    }

    // --- Row mutations ---

    private suspend fun markSent(id: UInt) = suspendTransaction(database) {
        EmailQueueTable.update({ EmailQueueTable.id eq id }) {
            it[status] = EmailStatus.SENT.name
            it[sentAt] = nowMillis()
            it[lastError] = null
        }
        Unit
    }

    private suspend fun markRetry(id: UInt, error: String, attempts: Int, nextAt: Long) = suspendTransaction(database) {
        EmailQueueTable.update({ EmailQueueTable.id eq id }) {
            it[status] = EmailStatus.FAILED.name
            it[EmailQueueTable.attempts] = attempts
            it[lastError] = error.take(1000)
            it[nextAttemptAt] = nextAt
        }
        Unit
    }

    private suspend fun markDeadLetter(id: UInt, error: String, attempts: Int) = suspendTransaction(database) {
        EmailQueueTable.update({ EmailQueueTable.id eq id }) {
            it[status] = EmailStatus.DEAD_LETTER.name
            it[EmailQueueTable.attempts] = attempts
            it[lastError] = error.take(1000)
        }
        Unit
    }

    // Reads the stored context JSON for a row (small, single-row lookup used only on the send path).
    private suspend fun entryContext(id: UInt): String = suspendTransaction(database) {
        EmailQueueTable.selectAll().where { EmailQueueTable.id eq id }
            .map { it[EmailQueueTable.context] }.singleOrNull().orEmpty()
    }

    private fun backoffMs(attempts: Int): Long =
        (BASE_BACKOFF_MS * (1L shl (attempts - 1).coerceIn(0, 10))).coerceAtMost(MAX_BACKOFF_MS)

    private fun ResultRow.toEntry() = EmailQueueEntry(
        id = this[EmailQueueTable.id].value,
        recipient = this[EmailQueueTable.recipient],
        templateKey = this[EmailQueueTable.templateKey],
        status = this[EmailQueueTable.status],
        attempts = this[EmailQueueTable.attempts],
        lastError = this[EmailQueueTable.lastError],
        createdAt = this[EmailQueueTable.createdAt],
        sentAt = this[EmailQueueTable.sentAt],
        nextAttemptAt = this[EmailQueueTable.nextAttemptAt],
    )

    companion object {
        const val BATCH_SIZE = 20
        const val MAX_ATTEMPTS = 5
        const val BASE_BACKOFF_MS = 60_000L // 1 min, doubling each attempt
        const val MAX_BACKOFF_MS = 6L * 60 * 60 * 1000 // 6h cap
    }
}
