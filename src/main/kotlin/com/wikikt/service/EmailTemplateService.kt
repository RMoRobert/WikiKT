package com.wikikt.service

import com.github.mustachejava.DefaultMustacheFactory
import com.wikikt.db.EmailTemplatesTable
import com.wikikt.model.nowMillis
import com.wikikt.service.email.OutboundEmail
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer

/** A built-in email type: its stable [key], admin-facing [label]/[description], the context [variables]
 *  it can reference, and the default subject/body used until an admin overrides it. */
data class EmailTemplateDef(
    val key: String,
    val label: String,
    val description: String,
    val variables: List<String>,
    val defaultSubject: String,
    val defaultText: String,
    val defaultHtml: String? = null,
    /** Whether this template appears in the admin template list. Internal ones (the SMTP test message)
     *  are still renderable and overridable by key, just not surfaced as a "mail your site sends". */
    val listed: Boolean = true,
)

/** The effective content of a template for a site: either the admin's override or the built-in default. */
data class EmailTemplateContent(
    val key: String,
    val subject: String,
    val text: String,
    val html: String?,
    /** True when this is the built-in default (no per-site override row exists). */
    val isDefault: Boolean,
)

/**
 * Owns the email templates: the built-in defaults (in code, [DEFAULTS]) and per-site overrides
 * ([EmailTemplatesTable]). Renders a template to an [OutboundEmail] by expanding its subject/bodies as
 * Mustache against a per-send context map. The subject and plain-text body are rendered WITHOUT HTML
 * escaping (they aren't HTML); the optional HTML body is rendered with escaping so a value like a
 * display name can't inject markup.
 */
class EmailTemplateService(private val database: R2dbcDatabase) {

    // Two factories: one escapes for the HTML body, one leaves the plain-text subject/body untouched.
    private val htmlFactory = DefaultMustacheFactory()
    private val plainFactory = object : DefaultMustacheFactory() {
        override fun encode(value: String, writer: Writer) {
            writer.write(value) // no HTML escaping for plain-text contexts
        }
    }

    /** The built-in template for [key], or null if [key] isn't a known email type. */
    fun default(key: String): EmailTemplateDef? = DEFAULTS[key]

    /** The built-in templates shown in the admin list (excludes internal ones like the SMTP test). */
    fun defaults(): List<EmailTemplateDef> = DEFAULTS.values.filter { it.listed }

    /** The effective (override-or-default) content for [key] on [siteId], or null for an unknown key. */
    suspend fun content(siteId: UInt, key: String): EmailTemplateContent? {
        val def = DEFAULTS[key] ?: return null
        val override = suspendTransaction(database) {
            EmailTemplatesTable.selectAll()
                .where { (EmailTemplatesTable.siteId eq siteId) and (EmailTemplatesTable.key eq key) }
                .map {
                    EmailTemplateContent(
                        key = key,
                        subject = it[EmailTemplatesTable.subject],
                        text = it[EmailTemplatesTable.textBody],
                        html = it[EmailTemplatesTable.htmlBody],
                        isDefault = false,
                    )
                }
                .singleOrNull()
        }
        return override ?: EmailTemplateContent(key, def.defaultSubject, def.defaultText, def.defaultHtml, isDefault = true)
    }

    /** Saves a per-site override for [key] (upsert). Blank [html] is stored as null (no HTML part). */
    suspend fun saveOverride(siteId: UInt, key: String, subject: String, text: String, html: String?, userId: UInt?) {
        require(key in DEFAULTS) { "Unknown email template: $key" }
        val cleanHtml = html?.trim()?.ifBlank { null }
        suspendTransaction(database) {
            val updated = EmailTemplatesTable.update(
                { (EmailTemplatesTable.siteId eq siteId) and (EmailTemplatesTable.key eq key) },
            ) {
                it[EmailTemplatesTable.subject] = subject
                it[EmailTemplatesTable.textBody] = text
                it[EmailTemplatesTable.htmlBody] = cleanHtml
                it[EmailTemplatesTable.updatedAt] = nowMillis()
                it[EmailTemplatesTable.updatedBy] = userId
            }
            if (updated == 0) {
                EmailTemplatesTable.insert {
                    it[EmailTemplatesTable.siteId] = siteId
                    it[EmailTemplatesTable.key] = key
                    it[EmailTemplatesTable.subject] = subject
                    it[EmailTemplatesTable.textBody] = text
                    it[EmailTemplatesTable.htmlBody] = cleanHtml
                    it[EmailTemplatesTable.updatedAt] = nowMillis()
                    it[EmailTemplatesTable.updatedBy] = userId
                }
            }
        }
    }

    /** Drops the per-site override for [key], reverting to the built-in default. */
    suspend fun resetToDefault(siteId: UInt, key: String) {
        suspendTransaction(database) {
            EmailTemplatesTable.deleteWhere {
                (EmailTemplatesTable.siteId eq siteId) and (EmailTemplatesTable.key eq key)
            }
        }
    }

    /** Renders [key] for [siteId] against [context] into a ready-to-send [OutboundEmail], or null for an
     *  unknown key. [recipient] is the address the message is addressed to. */
    suspend fun render(siteId: UInt, key: String, recipient: String, context: Map<String, String>): OutboundEmail? {
        val content = content(siteId, key) ?: return null
        return OutboundEmail(
            to = recipient,
            subject = renderString(plainFactory, content.subject, context).trim(),
            text = renderString(plainFactory, content.text, context),
            html = content.html?.let { renderString(htmlFactory, it, context) },
        )
    }

    private fun renderString(factory: DefaultMustacheFactory, template: String, context: Map<String, String>): String {
        val mustache = factory.compile(StringReader(template), "email")
        val out = StringWriter()
        mustache.execute(out, context).flush()
        return out.toString()
    }

    companion object {
        const val WELCOME = "welcome"
        const val PASSWORD_RESET = "password_reset"
        const val REGISTRATION_CONFIRM = "registration_confirm"
        const val REGISTRATION_APPROVED = "registration_approved"
        const val REGISTRATION_EMAIL_EXISTS = "registration_email_exists"
        const val ADMIN_NOTIFICATION = "admin_notification"
        const val TEST = "test"

        /**
         * Built-in templates, keyed by [EmailTemplateDef.key] and ordered for display. Subjects/bodies are
         * Mustache; the variables each may reference are listed in [EmailTemplateDef.variables] and are
         * surfaced in the admin editor. Kept intentionally plain-text-first (with a light HTML variant) —
         * deliverability over decoration.
         */
        val DEFAULTS: Map<String, EmailTemplateDef> = listOf(
            EmailTemplateDef(
                key = WELCOME,
                label = "Welcome",
                description = "Sent to a new user when their account is created.",
                variables = listOf("siteName", "username", "displayName", "loginUrl", "recipient"),
                defaultSubject = "Welcome to {{siteName}}",
                defaultText = """
                    |Hello {{displayName}},
                    |
                    |An account has been created for you on {{siteName}}.
                    |
                    |Username: {{username}}
                    |Sign in: {{loginUrl}}
                    |
                    |— {{siteName}}
                """.trimMargin(),
                defaultHtml = null,
            ),
            EmailTemplateDef(
                key = PASSWORD_RESET,
                label = "Password reset",
                description = "Sent when a user requests a password reset. Must include {{resetLink}}.",
                variables = listOf("siteName", "username", "displayName", "resetLink", "recipient"),
                defaultSubject = "Reset your {{siteName}} password",
                defaultText = """
                    |Hello {{displayName}},
                    |
                    |We received a request to reset the password for your {{siteName}} account
                    |({{username}}). Click the link below to choose a new password:
                    |
                    |{{resetLink}}
                    |
                    |If you didn't request this, you can ignore this email — your password won't change.
                    |
                    |— {{siteName}}
                """.trimMargin(),
                defaultHtml = null,
            ),
            EmailTemplateDef(
                key = REGISTRATION_CONFIRM,
                label = "Registration confirmation",
                description = "Sent to a new self-registered user to confirm their email address. Must include {{confirmLink}}.",
                variables = listOf("siteName", "username", "confirmLink", "recipient"),
                defaultSubject = "Confirm your {{siteName}} account",
                defaultText = """
                    |Hello {{username}},
                    |
                    |Thanks for registering at {{siteName}}. Confirm your email address to activate your
                    |account by clicking the link below:
                    |
                    |{{confirmLink}}
                    |
                    |If you didn't create this account, you can ignore this email — it won't be activated.
                    |
                    |— {{siteName}}
                """.trimMargin(),
                defaultHtml = null,
            ),
            EmailTemplateDef(
                key = REGISTRATION_APPROVED,
                label = "Registration approved",
                description = "Sent when an administrator approves a new account (only when registration requires approval).",
                variables = listOf("siteName", "username", "loginUrl", "recipient"),
                defaultSubject = "Your {{siteName}} account is approved",
                defaultText = """
                    |Hello {{username}},
                    |
                    |An administrator has approved your {{siteName}} account. You can now sign in:
                    |
                    |{{loginUrl}}
                    |
                    |— {{siteName}}
                """.trimMargin(),
                defaultHtml = null,
            ),
            EmailTemplateDef(
                key = REGISTRATION_EMAIL_EXISTS,
                label = "Registration — address already in use",
                description = "Sent to an existing account's address when someone tries to register with it — instead of revealing on the form that the address is taken.",
                variables = listOf("siteName", "username", "loginUrl", "resetUrl", "recipient"),
                defaultSubject = "Someone tried to register with your {{siteName}} email",
                defaultText = """
                    |Hello {{username}},
                    |
                    |Someone just tried to create a new {{siteName}} account with this email address, but it's
                    |already registered to you. No new account was created and nothing has changed.
                    |
                    |If this was you, simply sign in: {{loginUrl}}
                    |Forgot your password? Reset it here: {{resetUrl}}
                    |If it wasn't you, you can safely ignore this email.
                    |
                    |— {{siteName}}
                """.trimMargin(),
                defaultHtml = null,
            ),
            EmailTemplateDef(
                key = ADMIN_NOTIFICATION,
                label = "Admin notification",
                description = "Sent to the configured admin recipients about noteworthy events.",
                variables = listOf("siteName", "subject", "message", "recipient"),
                defaultSubject = "[{{siteName}}] {{subject}}",
                defaultText = """
                    |{{message}}
                    |
                    |— {{siteName}} administration
                """.trimMargin(),
                defaultHtml = null,
            ),
            EmailTemplateDef(
                key = TEST,
                label = "Test message",
                description = "Sent by the “Send test email” button to verify SMTP settings.",
                variables = listOf("siteName", "recipient"),
                defaultSubject = "{{siteName}} SMTP test",
                defaultText = """
                    |This is a test email from {{siteName}}.
                    |
                    |If you're reading it, your SMTP settings are working.
                """.trimMargin(),
                defaultHtml = null,
                // Internal diagnostic — not one of the "emails your site sends", so keep it off the list.
                listed = false,
            ),
        ).associateBy { it.key }
    }
}
