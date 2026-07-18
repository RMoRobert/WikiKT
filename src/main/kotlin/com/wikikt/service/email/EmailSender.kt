package com.wikikt.service.email

/**
 * Resolved SMTP connection settings for one site, read from [com.wikikt.service.SettingsService].
 * [security] is one of `starttls` | `ssl` | `none`. [fromName] is the optional display name paired
 * with [fromAddress] on the envelope's From header.
 */
data class MailSettings(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val security: String,
    val username: String,
    val password: String,
    val fromAddress: String,
    val fromName: String?,
) {
    /** Whether there's enough here to attempt a send (host + from address are the minimum). */
    val isConfigured: Boolean get() = host.isNotBlank() && fromAddress.isNotBlank()
}

/** One rendered message ready to hand to a transport: [subject] + plain-text and optional HTML bodies. */
data class OutboundEmail(
    val to: String,
    val subject: String,
    val text: String,
    val html: String?,
)

/**
 * Abstraction over "actually put a message on the wire", so the queue/worker doesn't know or care
 * whether delivery is SMTP or (later) a provider API (SES, SendGrid). Phase 1 ships one
 * implementation, [SmtpEmailSender]. Implementations must be safe to call from a background coroutine
 * and must not throw for a delivery failure — they return a [SendResult] the worker can act on.
 */
interface EmailSender {
    suspend fun send(settings: MailSettings, email: OutboundEmail): SendResult
}

/** Outcome of a single delivery attempt. [transient] failures are worth retrying; permanent ones aren't. */
sealed interface SendResult {
    data object Sent : SendResult
    data class Failed(val reason: String, val transient: Boolean) : SendResult
}
