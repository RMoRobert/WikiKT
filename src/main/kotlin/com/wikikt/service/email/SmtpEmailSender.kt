package com.wikikt.service.email

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.SendFailedException
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

/**
 * Delivers mail over SMTP using Jakarta Mail (Angus). Jakarta Mail's transport is blocking, so the
 * whole send runs on [Dispatchers.IO]; the caller is the background worker, never a request thread.
 * Connection/read/write timeouts are set so a wedged relay can't hang the worker indefinitely. A
 * delivery failure is never thrown — it's classified into a [SendResult.Failed] the worker can retry
 * (transient: connection/timeout) or park (permanent: auth failure, invalid recipient).
 */
class SmtpEmailSender : EmailSender {

    override suspend fun send(settings: MailSettings, email: OutboundEmail): SendResult =
        withContext(Dispatchers.IO) {
            try {
                val session = buildSession(settings)
                val message = buildMessage(session, settings, email)
                Transport.send(message)
                SendResult.Sent
            } catch (e: AuthenticationFailedException) {
                // Bad credentials won't fix themselves on retry.
                SendResult.Failed("SMTP authentication failed: ${e.message}", transient = false)
            } catch (e: SendFailedException) {
                // A rejected/invalid recipient is permanent; other send failures may be transient.
                val invalid = e.invalidAddresses?.isNotEmpty() == true
                SendResult.Failed("SMTP send rejected: ${e.message}", transient = !invalid)
            } catch (e: Exception) {
                // Connection refused, timeout, DNS, TLS handshake — treat as transient and retry.
                SendResult.Failed("SMTP error: ${e.javaClass.simpleName}: ${e.message}", transient = true)
            }
        }

    private fun buildSession(settings: MailSettings): Session {
        val props = Properties().apply {
            put("mail.smtp.host", settings.host)
            put("mail.smtp.port", settings.port.toString())
            put("mail.smtp.connectiontimeout", TIMEOUT_MS)
            put("mail.smtp.timeout", TIMEOUT_MS)
            put("mail.smtp.writetimeout", TIMEOUT_MS)
            when (settings.security.lowercase()) {
                "ssl" -> {
                    // Implicit TLS (typically 465): the socket is encrypted from the start.
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.ssl.checkserveridentity", "true")
                }
                "none" -> {
                    // Plaintext — for a trusted local relay or dev catcher only.
                }
                else -> {
                    // starttls (typically 587): connect in the clear, then upgrade in-band. Required so a
                    // relay that silently omits STARTTLS can't downgrade us to plaintext.
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                    put("mail.smtp.ssl.checkserveridentity", "true")
                }
            }
        }
        val authenticator = if (settings.username.isNotBlank()) {
            props.put("mail.smtp.auth", "true")
            object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(settings.username, settings.password)
            }
        } else {
            null
        }
        return Session.getInstance(props, authenticator)
    }

    private fun buildMessage(session: Session, settings: MailSettings, email: OutboundEmail): MimeMessage {
        val message = MimeMessage(session)
        val from = if (settings.fromName.isNullOrBlank()) {
            InternetAddress(settings.fromAddress)
        } else {
            InternetAddress(settings.fromAddress, settings.fromName, "UTF-8")
        }
        message.setFrom(from)
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(email.to))
        message.setSubject(email.subject, "UTF-8")
        val html = email.html
        if (html.isNullOrBlank()) {
            message.setText(email.text, "UTF-8")
        } else {
            // multipart/alternative: text first, HTML second — clients prefer the last part they grok.
            val textPart = MimeBodyPart().apply { setText(email.text, "UTF-8") }
            val htmlPart = MimeBodyPart().apply { setContent(html, "text/html; charset=UTF-8") }
            message.setContent(MimeMultipart("alternative", textPart, htmlPart))
        }
        return message
    }

    private companion object {
        // 15s each for connect/read/write so a hung relay can't stall the outbox worker for long.
        const val TIMEOUT_MS = "15000"
    }
}
