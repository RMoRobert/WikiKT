package com.wikikt.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable

enum class ContentFormat {
    MARKDOWN,
    HTML,
}

/**
 * Account lifecycle. Admin-created and seeded accounts are [ACTIVE]. Self-registered accounts start
 * [PENDING_EMAIL] until the visitor confirms their address, then become [ACTIVE] — or [PENDING_APPROVAL]
 * when the site requires an admin to approve new registrations. Only [ACTIVE] accounts may sign in.
 */
enum class UserStatus {
    ACTIVE,
    PENDING_EMAIL,
    PENDING_APPROVAL,
}

// A site hosted by this instance. Content (pages, assets, nav menus, fragments, settings) is partitioned
// by site_id; users, groups, sessions and API keys are global (one login spans all sites). A site is
// reached by its hostname; exactly one site is the catch-all that serves any host not matched by name.
object SitesTable : UIntIdTable("sites") {
    val name = varchar("name", 100)
    // Host this site answers to (e.g. "docs.example.com"), matched case-insensitively; null = reachable
    // only as the catch-all. Unique so two sites can't claim the same host.
    val hostname = varchar("hostname", 255).nullable().uniqueIndex()
    val isCatchAll = bool("is_catchall").default(false)
    val position = integer("position").default(0)
    val createdAt = long("created_at")
}

object UsersTable : UIntIdTable("users") {
    val username = varchar("username", 100).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val email = varchar("email", 255).nullable()
    val createdAt = long("created_at")
    // IANA zone id (e.g. "America/New_York") for rendering timestamps to THIS user. Null = fall back
    // to the server's default zone. Display-only; stored times remain epoch millis (UTC).
    val timezone = varchar("timezone", 64).nullable()
    // Per-user date/time display preferences (see DateDisplay). Null = follow the code defaults. Keys
    // into DateDisplay's catalogs: dateFormatShort (iso|us|eu|dot|abbrev) is the compact date shown
    // with a time, dateFormatLong (full|long|medium) the locale-styled date-only line, timeFormat
    // (12|24) the clock style. Display-only, like [timezone].
    val dateFormatShort = varchar("date_format_short", 20).nullable()
    val dateFormatLong = varchar("date_format_long", 20).nullable()
    val timeFormat = varchar("time_format", 10).nullable()
    // Optional self-service profile fields (all null until the user fills them in). Display name, when
    // set, is the friendly name to show instead of the login username.
    val displayName = varchar("display_name", 100).nullable()
    val jobTitle = varchar("job_title", 150).nullable()
    val location = varchar("location", 150).nullable()
    // Per-user color-theme override: light | dark | auto. Null/blank = follow the site default. Applied
    // across the user's devices (distinct from the per-browser localStorage choice used for guests).
    val theme = varchar("theme", 10).nullable()
    // Account lifecycle (see [UserStatus]), stored as the enum name. Defaults to ACTIVE so admin-created
    // and seeded accounts are immediately usable — self-registration is the only path that sets it
    // non-ACTIVE (PENDING_EMAIL until confirmed, then PENDING_APPROVAL if the site requires approval).
    val status = varchar("status", 20).default(UserStatus.ACTIVE.name)
}

// A group's identity. Its GLOBAL (admin) permission verbs live in GroupPermissionsTable; its content
// access is granted per (site, path, locale) via GroupPageRulesTable.
object GroupsTable : UIntIdTable("groups") {
    val name = varchar("name", 100).uniqueIndex()
    val isSystem = bool("is_system").default(false)
}

// The global (admin) permission verbs a group holds instance-wide (e.g. manage:users, manage:sites).
// Content verbs (read:pages, write:assets, …) are NOT stored here — they come only from page rules.
object GroupPermissionsTable : UIntIdTable("group_permissions") {
    val groupId = reference("group_id", GroupsTable)
    val permission = varchar("permission", 40)

    init {
        uniqueIndex(groupId, permission)
    }
}

object UserGroupsTable : UIntIdTable("user_groups") {
    val userId = reference("user_id", UsersTable)
    val groupId = reference("group_id", GroupsTable)

    init {
        uniqueIndex(userId, groupId)
    }
}

object PagesTable : UIntIdTable("pages") {
    val siteId = reference("site_id", SitesTable)
    val locale = varchar("locale", 10)
    val path = varchar("path", 500)
    val title = varchar("title", 500)
    val description = varchar("description", 1000).nullable()
    // Per-page <meta name="robots"> override (e.g. "noindex,nofollow"); null = inherit the site default.
    val metaRobots = varchar("meta_robots", 30).nullable()
    val content = text("content")
    val contentFormat = varchar("content_format", 20).default(ContentFormat.MARKDOWN.name)
    val published = bool("published").default(true)
    val publishAt = long("publish_at").nullable()
    // Infobox instance data for this page: a small JSON object of field-name → value (string, boolean,
    // or string array) supplied by the editor, validated against the template resolved for the page's
    // path (see InfoboxService / InfoboxPathRulesTable). Null = no infobox data. Travels with the page
    // in history/staging and is emitted into the export file's front-matter (PageFileFormat).
    val infobox = text("infobox").nullable()
    // Per-page custom code injected into the page's <head> on view (never sanitized — gated by
    // manage:theme, the same permission that governs site-wide custom HTML/CSS). customCss is wrapped
    // in a <style> tag on render; customJs is emitted verbatim, so the author supplies their own
    // <script> tags (mirrors Wiki.js). Null/blank = nothing injected.
    val customCss = text("custom_css").nullable()
    val customJs = text("custom_js").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val updatedBy = reference("updated_by", UsersTable).nullable()

    init {
        uniqueIndex(siteId, locale, path)
    }
}

// Uploaded files (images) that live at a virtual (locale, path) like pages and are served at that
// path. Bytes are stored on disk by id (sharded); this row is the metadata.
object AssetsTable : UIntIdTable("assets") {
    val siteId = reference("site_id", SitesTable)
    val locale = varchar("locale", 10)
    val path = varchar("path", 500)
    val originalFilename = varchar("original_filename", 500)
    val mime = varchar("mime", 100)
    val sizeBytes = long("size_bytes")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val uploadedBy = reference("uploaded_by", UsersTable).nullable()
    // Default alt text applied at render time when the markdown/HTML uses the "{alt}" sentinel.
    val altText = varchar("alt_text", 1000).nullable()
    // Editor-only note, searchable from the asset manager; never rendered to readers.
    val description = varchar("description", 2000).nullable()

    init {
        uniqueIndex(siteId, locale, path)
    }
}

// Prior versions of an asset, kept when its file is replaced (bounded by config.assets.maxAssetVersions).
// Bytes live on disk under the storage dir's `rev/` tree by this row's id; this is the metadata.
object AssetRevisionsTable : UIntIdTable("asset_revisions") {
    val assetId = reference("asset_id", AssetsTable)
    val versionNumber = integer("version_number")
    val originalFilename = varchar("original_filename", 500)
    val mime = varchar("mime", 100)
    val sizeBytes = long("size_bytes")
    val createdAt = long("created_at")
    val createdBy = reference("created_by", UsersTable).nullable()
}

// Free-form tags on a page (categorization / future search). Tags are stored normalized (lowercased).
object PageTagsTable : UIntIdTable("page_tags") {
    val pageId = reference("page_id", PagesTable)
    val tag = varchar("tag", 100)

    init {
        uniqueIndex(pageId, tag)
        index(false, tag)
    }
}

object PageViewAclTable : UIntIdTable("page_view_acl") {
    val pageId = reference("page_id", PagesTable)
    val groupId = reference("group_id", GroupsTable).nullable()
    val userId = reference("user_id", UsersTable).nullable()
}

object PageEditAclTable : UIntIdTable("page_edit_acl") {
    val pageId = reference("page_id", PagesTable)
    val groupId = reference("group_id", GroupsTable).nullable()
    val userId = reference("user_id", UsersTable).nullable()
}

object PageAliasesTable : UIntIdTable("page_aliases") {
    val aliasPath = varchar("alias_path", 500)
    val locale = varchar("locale", 10).nullable()
    val pageId = reference("page_id", PagesTable)

    init {
        uniqueIndex(aliasPath, locale)
    }
}

// Reusable shared content transcluded into pages via {{fragment:key}}. Not a standalone page.
object FragmentsTable : UIntIdTable("fragments") {
    val siteId = reference("site_id", SitesTable)
    val locale = varchar("locale", 10)
    val key = varchar("key", 200)
    val title = varchar("title", 500)
    val content = text("content")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    val updatedBy = reference("updated_by", UsersTable).nullable()

    init {
        uniqueIndex(siteId, locale, key)
    }
}

// A page rule owned by a group. effect=ALLOW|DENY, match_type=PREFIX|EXACT|SUFFIX|
// REGEX|TAG. The content verbs it grants/denies are in GroupPageRuleRolesTable; the site and locale
// scopes are in GroupPageRuleSitesTable / GroupPageRuleLocalesTable (empty scope = all).
object GroupPageRulesTable : UIntIdTable("group_page_rules") {
    val groupId = reference("group_id", GroupsTable)
    val effect = varchar("effect", 10)
    val matchType = varchar("match_type", 20)
    val pattern = varchar("pattern", 1000)
    val position = integer("position").default(0)

    init {
        index(false, groupId)
    }
}

/** Content verbs a page rule grants/denies (e.g. read:pages, write:assets). */
object GroupPageRuleRolesTable : UIntIdTable("group_page_rule_roles") {
    val ruleId = reference("rule_id", GroupPageRulesTable)
    val permission = varchar("permission", 40)

    init {
        uniqueIndex(ruleId, permission)
    }
}

/** Sites a page rule is scoped to; NO rows for a rule = all sites. */
object GroupPageRuleSitesTable : UIntIdTable("group_page_rule_sites") {
    val ruleId = reference("rule_id", GroupPageRulesTable)
    val siteId = reference("site_id", SitesTable)

    init {
        uniqueIndex(ruleId, siteId)
    }
}

/** Locales a page rule is scoped to; NO rows for a rule = all locales. */
object GroupPageRuleLocalesTable : UIntIdTable("group_page_rule_locales") {
    val ruleId = reference("rule_id", GroupPageRulesTable)
    val locale = varchar("locale", 10)

    init {
        uniqueIndex(ruleId, locale)
    }
}

// Tracks which schema migrations have been applied (the migration runner's bookkeeping).
object SchemaMigrationsTable : Table("schema_migrations") {
    val version = integer("version")
    val name = varchar("name", 200)
    val appliedAt = long("applied_at")

    override val primaryKey = PrimaryKey(version)
}

// A navigation sidebar scoped to a path prefix. scope "" is the default menu (used where no more
// specific menu matches); otherwise the menu applies to its prefix and descendants.
object NavMenusTable : UIntIdTable("nav_menus") {
    val siteId = reference("site_id", SitesTable)
    val scope = varchar("scope", 500)
    val position = integer("position").default(0)

    init {
        uniqueIndex(siteId, scope)
    }
}

// One row in a nav menu: either a non-link heading or a link (label + optional MDI icon + target).
object NavItemsTable : UIntIdTable("nav_items") {
    val menuId = reference("menu_id", NavMenusTable)
    val position = integer("position")
    val isHeader = bool("is_header").default(false)
    // A visual divider (horizontal rule). Mutually exclusive with isHeader; both false = a link.
    val isDivider = bool("is_divider").default(false)
    // Indent level for nesting: 0 = top-level, 1 = a child of the nearest preceding top-level link.
    // Capped at one level; headers/dividers are always 0.
    val depth = integer("depth").default(0)
    val label = varchar("label", 300)
    val icon = varchar("icon", 100).nullable()
    val target = varchar("target", 1000).nullable()
}

// Server-side session store, enabling revocation (delete the row to kill a live session).
object SessionsTable : Table("sessions") {
    // SHA-256 hex of the session id (the plaintext lives only in the user's encrypted cookie),
    // so a leaked database or backup doesn't contain live sessions.
    val token = varchar("token", 64)
    val userId = reference("user_id", UsersTable)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")

    override val primaryKey = PrimaryKey(token)
}

// Long-lived bearer tokens for non-browser API clients. A key belongs to a user and authenticates
// as that user, so it inherits exactly that user's permissions — scope a key by pointing it at a
// purpose-built user in the desired groups. Unlike sessions, the token is stored HASHED (SHA-256
// hex): the plaintext is shown once at creation and never persisted. `prefix` is the leading, non-
// secret slice of the plaintext, kept only so the UI can identify a key in a list.
object ApiKeysTable : UIntIdTable("api_keys") {
    val userId = reference("user_id", UsersTable)
    val name = varchar("name", 255)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    // The leading, non-secret slice of the plaintext, so the UI can identify a key ("Partial key").
    val partialKey = varchar("partial_key", 16)
    val createdAt = long("created_at")
    val lastUsedAt = long("last_used_at").nullable()
    val expiresAt = long("expires_at").nullable()
    val revokedAt = long("revoked_at").nullable()
}

// Single-use, short-lived tokens for the self-service password-reset flow. Like sessions and API keys
// the token is stored HASHED (SHA-256 hex) — the plaintext lives only in the emailed reset link — so a
// leaked database or backup can't be used to reset anyone's password. A row is spent when `usedAt` is
// set (single use) and ignored once `expiresAt` has passed; expired rows are purged periodically.
object PasswordResetTokensTable : UIntIdTable("password_reset_tokens") {
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val userId = reference("user_id", UsersTable)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val usedAt = long("used_at").nullable()
}

// Single-use, short-lived tokens for the self-service registration email-confirmation flow. Stored
// HASHED (SHA-256 hex) exactly like [PasswordResetTokensTable]: the plaintext lives only in the emailed
// confirmation link. A row is spent when `usedAt` is set and ignored once `expiresAt` has passed;
// expired rows are purged periodically alongside the unconfirmed accounts they belong to.
object EmailVerificationTokensTable : UIntIdTable("email_verification_tokens") {
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val userId = reference("user_id", UsersTable)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val usedAt = long("used_at").nullable()
}

// A second-factor (MFA) credential a user has enrolled. Type-discriminated so more methods (WebAuthn/
// passkeys) can be added later without reshaping the table: `type` selects what `secret` means. For
// "totp", `secret` is the shared secret ENCRYPTED at rest (AES-GCM, Base64) so a leaked database can't
// reconstruct anyone's codes. A factor is active only once `confirmedAt` is set (enrollment verifies a
// live code first); `lastUsedStep` records the last accepted TOTP time step to block replay within a code's
// validity window.
object UserMfaFactorsTable : UIntIdTable("user_mfa_factors") {
    val userId = reference("user_id", UsersTable)
    val type = varchar("type", 20)
    val label = varchar("label", 100).nullable()
    val secret = text("secret")
    val createdAt = long("created_at")
    val confirmedAt = long("confirmed_at").nullable()
    val lastUsedAt = long("last_used_at").nullable()
    val lastUsedStep = long("last_used_step").nullable()
}

// One-time recovery codes: the fallback second factor when a user loses their authenticator. Stored HASHED
// (SHA-256 hex) like the token tables — the plaintext is shown once at generation and never again. A code
// is spent when `usedAt` is set. Works for any factor type; verified in the context of a known user.
object UserMfaRecoveryCodesTable : UIntIdTable("user_mfa_recovery_codes") {
    val userId = reference("user_id", UsersTable)
    val codeHash = varchar("code_hash", 64)
    val createdAt = long("created_at")
    val usedAt = long("used_at").nullable()
}

// Stores a snapshot of each page's content before it is overwritten by an update.
object PageRevisionsTable : UIntIdTable("page_revisions") {
    val pageId = reference("page_id", PagesTable)
    val title = varchar("title", 500)
    val description = varchar("description", 1000).nullable()
    val content = text("content")
    val contentFormat = varchar("content_format", 20)
    // Infobox data as it was at this revision (see PagesTable.infobox); null if the page had none then.
    val infobox = text("infobox").nullable()
    val revisionNumber = integer("revision_number")
    val createdAt = long("created_at")
    val createdBy = reference("created_by", UsersTable).nullable()
}

// A single staged (future) content version of an already-live page. One row per page; promoted to
// live (snapshotting the current content to history) now or when publishAt arrives.
object PageStagedTable : UIntIdTable("page_staged") {
    val pageId = reference("page_id", PagesTable)
    val title = varchar("title", 500)
    val description = varchar("description", 1000).nullable()
    val content = text("content")
    val contentFormat = varchar("content_format", 20)
    // Staged infobox data (see PagesTable.infobox); applied to the live page when this version publishes.
    val infobox = text("infobox").nullable()
    val publishAt = long("publish_at").nullable()
    val updatedAt = long("updated_at")
    val updatedBy = reference("updated_by", UsersTable).nullable()

    init {
        uniqueIndex(pageId)
    }
}

// A scheduled replacement of an asset's file: pending bytes (on disk under storageDir/pending/...)
// swap in at publishAt, archiving the current file as a revision. One row per asset.
object AssetScheduledTable : UIntIdTable("asset_scheduled") {
    val assetId = reference("asset_id", AssetsTable)
    val mime = varchar("mime", 100)
    val sizeBytes = long("size_bytes")
    val originalFilename = varchar("original_filename", 500)
    val publishAt = long("publish_at")
    val createdAt = long("created_at")
    val createdBy = reference("created_by", UsersTable).nullable()

    init {
        uniqueIndex(assetId)
    }
}

// Runtime-editable, global application settings as a simple key/value store (one row per setting).
// Read on demand; written from the Administration > Settings page.
object AppSettingsTable : Table("app_settings") {
    val siteId = reference("site_id", SitesTable)
    val key = varchar("key", 100)
    val value = text("value")

    override val primaryKey = PrimaryKey(siteId, key)
}

// Per-page search text: the page body with {{fragment:key}} references expanded inline, so a search
// can match text that lives in a transcluded fragment as if it were part of the page. One row per
// page, rebuilt when the page's live content changes or any fragment changes (SearchIndexService).
object PageSearchIndexTable : Table("page_search_index") {
    val pageId = reference("page_id", PagesTable)
    val text = text("text")

    override val primaryKey = PrimaryKey(pageId)
}

// Server-side cache of a page's rendered body HTML (the expensive Markdown/HTML → sanitized-HTML step),
// so a live page view serves stored output instead of re-rendering on every request. One row per page.
// `renderEpoch` is the global render-settings version the row was built under; `sourceUpdatedAt` is the
// page's updatedAt at build time — a row is valid only when both still match (else it is re-rendered).
// Per-locale image/alt resolution and the page chrome are applied per request, so they are NOT stored here.
object PageRenderCacheTable : Table("page_render_cache") {
    val pageId = reference("page_id", PagesTable)
    val html = text("html")
    // The page's rendered infobox card HTML (see InfoboxService), cached in the same row as the body so
    // both are built and invalidated together (by renderEpoch + sourceUpdatedAt). Null = no infobox.
    val infoboxHtml = text("infobox_html").nullable()
    val renderEpoch = long("render_epoch")
    val sourceUpdatedAt = long("source_updated_at")

    override val primaryKey = PrimaryKey(pageId)
}

// A per-site override of a built-in email template (welcome, password_reset, admin_notification, …).
// The built-in defaults live in code (EmailTemplateService.DEFAULTS); a row here exists only when an
// admin has customized that template for the site. `key` identifies which email; subject/body are
// Mustache templates rendered against a per-send context. `htmlBody` is an optional richer variant.
object EmailTemplatesTable : UIntIdTable("email_templates") {
    val siteId = reference("site_id", SitesTable)
    val key = varchar("key", 100)
    val subject = varchar("subject", 500)
    val textBody = text("text_body")
    val htmlBody = text("html_body").nullable()
    val updatedAt = long("updated_at")
    val updatedBy = reference("updated_by", UsersTable).nullable()

    init {
        uniqueIndex(siteId, key)
    }
}

// The durable outbox. Every email is enqueued here first (so a send survives a restart and stays
// visible to the admin) and a background worker (EmailService) drains it. `context` is the JSON of
// Mustache variables the template is rendered with. `status` is PENDING → SENT | FAILED, with FAILED
// rows retried on an exponential backoff until `attempts` hits the cap, then parked as DEAD_LETTER.
object EmailQueueTable : UIntIdTable("email_queue") {
    val siteId = reference("site_id", SitesTable)
    val recipient = varchar("recipient", 255)
    val templateKey = varchar("template_key", 100)
    val context = text("context")
    val status = varchar("status", 20).default("PENDING")
    val attempts = integer("attempts").default(0)
    // When the row next becomes eligible to (re)send: enqueue time for a new row, then pushed out on
    // each failure by the backoff. The worker only picks up rows whose nextAttemptAt has passed.
    val nextAttemptAt = long("next_attempt_at")
    val lastError = varchar("last_error", 1000).nullable()
    val createdAt = long("created_at")
    val sentAt = long("sent_at").nullable()

    init {
        index(false, status, nextAttemptAt)
    }
}

// An admin-defined infobox template: a named, reusable set of fields (stored as a JSON array of field
// definitions) that pages under a matching path (InfoboxPathRulesTable) fill in with their own values.
// Site-scoped, like all content. See InfoboxService.
object InfoboxTemplatesTable : UIntIdTable("infobox_templates") {
    val siteId = reference("site_id", SitesTable)
    val slug = varchar("slug", 100)
    val name = varchar("name", 200)
    val description = varchar("description", 1000).nullable()
    // JSON array of field defs: [{name,label,type,required,help,options}] — see InfoboxFieldDef.
    // Named fieldsJson (not `fields`) because Exposed's ColumnSet already has a `fields` member.
    val fieldsJson = text("fields")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    init {
        uniqueIndex(siteId, slug)
    }
}

// Binds an infobox template to pages by a rule. `matchType` = PATH (pattern is an exact path, a `foo/*`
// direct-children glob, or `foo/**` descendants glob) or TAG (pattern is a tag the page must carry). A
// page can match more than one rule (even for different templates) — all are always optional: an empty
// infobox simply doesn't render, and editors get a note only when a matched infobox has no data yet
// (see InfoboxService). Site-scoped.
object InfoboxPathRulesTable : UIntIdTable("infobox_path_rules") {
    val siteId = reference("site_id", SitesTable)
    val matchType = varchar("match_type", 10).default("PATH")
    val pattern = varchar("pattern", 500)
    val templateId = reference("template_id", InfoboxTemplatesTable)
    val position = integer("position").default(0)

    init {
        index(false, siteId)
    }
}
