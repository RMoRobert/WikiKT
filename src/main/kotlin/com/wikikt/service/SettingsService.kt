package com.wikikt.service

import com.wikikt.db.AppSettingsTable
import com.wikikt.db.ContentFormat
import org.jetbrains.exposed.v1.core.and
import com.wikikt.markdown.ExternalLinkMode
import com.wikikt.markdown.MarkdownRenderer
import com.wikikt.markdown.RenderOptions
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * Runtime-editable, global application settings backed by [AppSettingsTable] (key/value).
 * Written from Administration > Settings. Known keys are declared as constants so callers don't
 * pass raw strings. The whole (small, global) table is cached in memory and invalidated on write,
 * so reads — which happen on every rendered page for branding — don't hit the database each time.
 */
class SettingsService(private val database: R2dbcDatabase) {
    companion object {
        /** When true, the Markdown editor uses a monospace font and renders no inline styling. */
        const val EDITOR_PLAIN_VIEW = "editor.plainView"

        /** Site branding (all customizable from Administration > Settings). */
        const val SITE_NAME = "site.name"
        // A site asset URL (validated against uploaded image assets), shown beside the site name.
        // Defaults to the bundled [DEFAULT_LOGO_URL] when unset.
        const val SITE_LOGO_URL = "site.logoUrl"
        const val DEFAULT_LOGO_URL = "/logo.svg"
        const val SITE_BRAND_COLOR = "site.brandColor"
        // Optional brand color used for links in DARK mode. The light [SITE_BRAND_COLOR] drives light-mode
        // links and the primary/button color in both modes (validated for contrast on white); this one
        // drives dark-mode link text only, so it can be light enough to read on the dark body. Unset =
        // dark-mode links fall back to Bootstrap's default. Both are contrast-validated on save (see
        // brandColorContrastError); the per-mode scoping lives in partials/brand-style.hbs.
        const val SITE_BRAND_COLOR_DARK = "site.brandColorDark"
        // Optional background colors for the top header bar, one per color mode (light / dark). When
        // unset, a subtly tinted default is used (see site.css). Foreground text/borders are auto-chosen
        // for contrast from each color's luminance (partials/brand-style.hbs).
        const val SITE_HEADER_COLOR = "site.headerColor"
        const val SITE_HEADER_COLOR_DARK = "site.headerColorDark"
        /**
         * Which surface the header search box wears:
         *  - `theme` — follows the site color mode, like every other input (the default).
         *  - `light` / `dark` — pinned, whatever the color mode or header color.
         * Deliberately no luminance-derived option: deriving the box from the header bar only differs
         * from a pinned choice when the two per-mode header colors differ in brightness, which is a
         * corner of a corner. Resolved per color mode in [brandingModel]; see `.header-search` in site.css.
         */
        const val SITE_SEARCH_BOX_THEME = "site.searchBoxTheme"
        const val DEFAULT_SEARCH_BOX_THEME = "theme"
        val SEARCH_BOX_THEME_OPTIONS = listOf("theme", "light", "dark")
        // Optional background colors for the wiki nav sidebar, one per color mode (light / dark). When
        // unset, site.css defaults apply (blue in light mode, dark surface in dark mode). Foreground
        // tints are auto-chosen for contrast from the color's luminance (partials/brand-style.hbs).
        const val SITE_SIDEBAR_COLOR = "site.sidebarColor"
        const val SITE_SIDEBAR_COLOR_DARK = "site.sidebarColorDark"
        // Optional color for the divider along the sidebar's top edge, where it meets the top header bar
        // (--wk-sidebar-header-line in site.css), one per color mode. Unset, the line keeps the tint
        // derived from the sidebar background — which vanishes when a site gives the header and the
        // sidebar the same color, so this is the knob that puts the seam back (or recolors it).
        const val SITE_SIDEBAR_HEADER_LINE_COLOR = "site.sidebarHeaderLineColor"
        const val SITE_SIDEBAR_HEADER_LINE_COLOR_DARK = "site.sidebarHeaderLineColorDark"
        // Optional text color for `header` items in the sidebar menu (the group labels added from
        // Administration > Navigation), one per color mode. Unset, they keep the muted tint derived from
        // the sidebar background — so these override only the headings, not the links around them.
        const val SITE_NAV_HEADING_COLOR = "site.navHeadingColor"
        const val SITE_NAV_HEADING_COLOR_DARK = "site.navHeadingColorDark"
        // Optional color for the fading underline drawn beneath content section headings (h2). When
        // unset, a neutral border-color fade is used (see .wiki-content h2 in site.css).
        const val SITE_HEADING_LINE_COLOR = "site.headingLineColor"
        // Browser-tab favicon: an uploaded image asset, or the bundled default at [DEFAULT_FAVICON_URL].
        const val SITE_FAVICON_URL = "site.faviconUrl"
        const val DEFAULT_FAVICON_URL = "/favicon.svg"

        /** Footer: org name + content license build the default footer; the override (Markdown) replaces it. */
        const val SITE_ORG_NAME = "site.orgName"
        const val SITE_CONTENT_LICENSE = "site.contentLicense"
        const val SITE_FOOTER_OVERRIDE = "site.footerOverride"

        /** SEO: default page meta description + default robots directive (when a page has no override). */
        const val SITE_DESCRIPTION = "site.description"
        const val SITE_META_ROBOTS = "site.metaRobots"

        /** Per-page table of contents (built from H1/H2): which side it sits on, or off entirely. */
        const val SITE_TOC_MODE = "site.tocMode"   // left | right | off
        const val DEFAULT_TOC_MODE = "right"
        val TOC_MODE_OPTIONS = listOf("left", "right", "off")

        /**
         * Which wiki sidebar navigation(s) the site offers (Administration > Navigation):
         *  - `static` — only the admin-curated menus (the default; unchanged behavior).
         *  - `tree`   — only the auto-generated site tree, browsed folder-by-folder.
         *  - `both`   — both, with a per-visitor switch (choice remembered per browser).
         *  - `none`   — no sidebar at all.
         * Defaults to [DEFAULT_NAV_MODE] so existing sites keep their static menus untouched.
         */
        const val NAV_MODE = "nav.mode"
        const val DEFAULT_NAV_MODE = "static"
        val NAV_MODE_OPTIONS = listOf("static", "tree", "both", "none")

        /** Whether the wiki sidebar shows its "Edit menu" link to users who may manage navigation.
         *  Off = those users edit menus from Administration > Navigation instead. Default on. */
        const val NAV_SHOW_EDIT_MENU_LINK = "nav.showEditMenuLink"

        /** Whether the wiki sidebar offers a "Home" shortcut at its top — folded into the view switch in
         *  `both` mode, and a plain nav link above the menu otherwise. Default on. */
        const val NAV_SHOW_HOME = "nav.showHome"

        /**
         * Typography + custom styling (Appearance page). Body/heading fonts are a preset key from
         * [FONT_PRESETS]; when the key is "custom" the paired `*Custom` value holds a raw CSS
         * font-family stack. [APPEARANCE_CUSTOM_CSS] is author CSS injected into every page's <head>.
         */
        /** Site default color mode: light | dark | auto (auto follows the visitor's OS). Each visitor
         *  can override it locally via the header theme switch (stored in their browser). */
        const val APPEARANCE_THEME = "appearance.theme"
        const val DEFAULT_THEME = "light"
        val THEME_OPTIONS = listOf("light", "dark", "auto")
        /** Whether the header shows the light/dark theme switch. Off = visitors get the default/their
         *  account theme with no in-page switch. Default on. */
        const val APPEARANCE_SHOW_THEME_PICKER = "appearance.showThemePicker"

        /** Raw HTML injected into every page's <head> / end of <body> (admin-only; analytics, meta tags,
         *  verification snippets). Not sanitized — that's the point — but the CSP still limits external
         *  script/style hosts. See [APPEARANCE_CUSTOM_CSS] for the CSS-only, safer sibling. */
        const val APPEARANCE_HEAD_HTML = "appearance.headHtml"
        const val APPEARANCE_BODY_HTML = "appearance.bodyHtml"
        const val MAX_INJECT_HTML_LENGTH = 20_000

        const val APPEARANCE_BODY_FONT = "appearance.bodyFont"
        const val APPEARANCE_HEADING_FONT = "appearance.headingFont"
        const val APPEARANCE_BODY_FONT_CUSTOM = "appearance.bodyFontCustom"
        const val APPEARANCE_HEADING_FONT_CUSTOM = "appearance.headingFontCustom"
        const val APPEARANCE_BASE_FONT_SIZE = "appearance.baseFontSize"
        const val APPEARANCE_CUSTOM_CSS = "appearance.customCss"
        const val DEFAULT_BODY_FONT = "roboto"
        const val DEFAULT_HEADING_FONT = "roboto"
        const val DEFAULT_BASE_FONT_SIZE = 16
        const val MAX_CUSTOM_CSS_LENGTH = 20_000

        /**
         * Render emoji from the bundled Noto Color Emoji webfont instead of whatever the visitor's OS
         * supplies (Apple Color Emoji / Segoe UI Emoji / …), so a page looks the same everywhere. On by
         * default. Implemented purely by appending [EMOJI_FONT_FAMILY] to the font stacks — emoji stay
         * real text, so copy/paste, in-page find and screen readers are unaffected, both `:shortcode:`
         * expansions and literal pasted emoji are covered, and nothing about the *rendered HTML* changes
         * (so flipping this needs no render-cache bump). Off = the previous OS-dependent behavior.
         * The stylesheet is loaded in partials/head-styles.hbs, from Google Fonts or the vendored copy
         * at /static/vendor/noto-emoji/ depending on `wikikt.ui.useCdnAssets`.
         */
        const val APPEARANCE_EMOJI_FONT = "appearance.emojiFont"
        const val DEFAULT_EMOJI_FONT = true

        /** Appended to every font stack when [APPEARANCE_EMOJI_FONT] is on. Last position is deliberate:
         *  the earlier families win for the characters they cover, and only codepoints they lack — i.e.
         *  emoji — fall through to here, ahead of the browser's own system-emoji fallback. */
        const val EMOJI_FONT_FAMILY = "'Noto Color Emoji'"

        /**
         * Curated web fonts. [stack] is the CSS `font-family` value; [googleSpec] is the Google Fonts
         * `family=` fragment to load (null = a system stack that needs no network load). The special
         * "custom" preset means "use the admin-entered font-family value" (loaded via Custom CSS).
         */
        data class FontPreset(val key: String, val label: String, val stack: String, val googleSpec: String?)
        val FONT_PRESETS = listOf(
            FontPreset("roboto", "Roboto", "'Roboto', system-ui, sans-serif", "Roboto:wght@400;500;700"),
            FontPreset("mulish", "Mulish", "'Mulish', system-ui, sans-serif", "Mulish:wght@400;600;700;800"),
            FontPreset("inter", "Inter", "'Inter', system-ui, sans-serif", "Inter:wght@400;500;700"),
            FontPreset("open-sans", "Open Sans", "'Open Sans', system-ui, sans-serif", "Open+Sans:wght@400;600;700"),
            FontPreset("lato", "Lato", "'Lato', system-ui, sans-serif", "Lato:wght@400;700"),
            FontPreset("source-sans", "Source Sans 3", "'Source Sans 3', system-ui, sans-serif", "Source+Sans+3:wght@400;600;700"),
            FontPreset("nunito", "Nunito", "'Nunito', system-ui, sans-serif", "Nunito:wght@400;600;700"),
            FontPreset("work-sans", "Work Sans", "'Work Sans', system-ui, sans-serif", "Work+Sans:wght@400;500;700"),
            FontPreset("merriweather", "Merriweather (serif)", "'Merriweather', Georgia, serif", "Merriweather:wght@400;700"),
            FontPreset("lora", "Lora (serif)", "'Lora', Georgia, serif", "Lora:wght@400;600;700"),
            FontPreset("system", "System UI", "system-ui, -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif", null),
            FontPreset("custom", "Custom…", "", null),
        )

        /**
         * Content-Security-Policy (Administration > Security). The BASELINE below is fixed in code so
         * the app always works and the policy tracks app changes. Admins can only *append* trusted
         * sources to the loosenable directives (the [CspDirective]s with a settings key) — e.g. to allow
         * a self-hosted font CDN — so they can extend the policy without being able to remove a baseline
         * protection and break/expose the site. A report-only toggle sends the policy without enforcing,
         * for safe testing. Header-injection is prevented by [sanitizeCspSources] (tokens can't contain
         * `;`, whitespace, or quotes, so an addition can never introduce a new directive).
         */
        const val SECURITY_CSP_REPORT_ONLY = "security.csp.reportOnly"
        const val MAX_CSP_FIELD_LENGTH = 2_000

        /** A CSP directive: its baseline sources, and (if loosenable) the settings key holding admin additions. */
        data class CspDirective(val name: String, val label: String, val baseline: List<String>, val settingKey: String?)
        val CSP_DIRECTIVES = listOf(
            CspDirective("default-src", "Default", listOf("'self'"), null),
            CspDirective("script-src", "Scripts", listOf("'self'", "'unsafe-inline'", "https://cdn.jsdelivr.net"), "security.csp.scriptSrc"),
            CspDirective("style-src", "Styles", listOf("'self'", "'unsafe-inline'", "https://cdn.jsdelivr.net", "https://fonts.googleapis.com"), "security.csp.styleSrc"),
            CspDirective("font-src", "Fonts", listOf("'self'", "data:", "https://fonts.gstatic.com", "https://cdn.jsdelivr.net"), "security.csp.fontSrc"),
            CspDirective("img-src", "Images", listOf("'self'", "data:", "https:", "http:"), "security.csp.imgSrc"),
            CspDirective("connect-src", "Connect (fetch/XHR)", listOf("'self'"), "security.csp.connectSrc"),
            CspDirective("frame-src", "Frames", listOf("https:"), "security.csp.frameSrc"),
            CspDirective("object-src", "Objects", listOf("'none'"), null),
            CspDirective("base-uri", "Base URI", listOf("'self'"), null),
            CspDirective("form-action", "Form action", listOf("'self'"), null),
            CspDirective("frame-ancestors", "Frame ancestors", listOf("'self'"), null),
        )
        // Only host/scheme sources, quoted keywords, nonces and hashes — no ';', whitespace, or quotes,
        // so a value can extend a directive but never open a new one (header/directive-injection safe).
        private val CSP_SOURCE_RE = Regex("^[A-Za-z0-9:/*._'+=-]{1,200}$")

        /** Splits an admin CSP-source field into validated, de-duplicated tokens (silently drops junk). */
        fun sanitizeCspSources(raw: String?): List<String> =
            raw.orEmpty().split(Regex("[\\s,]+")).map { it.trim() }
                .filter { it.isNotEmpty() && CSP_SOURCE_RE.matches(it) }.distinct()

        /** The baseline policy with no admin additions — the fail-safe when per-site settings can't be read. */
        fun baselineCspValue(): String =
            CSP_DIRECTIVES.joinToString("; ") { "${it.name} ${it.baseline.joinToString(" ")}" }

        /**
         * Additional content locales beyond the configured default, stored comma-separated and
         * canonicalized. The enabled set (default + these) populates the locale dropdowns site-wide.
         */
        const val SITE_LOCALES = "site.locales"

        /**
         * Content rendering toggles (Administration > Settings > Rendering), read on each page render
         * into a [RenderOptions] (see [renderOptions]). Defaults live in [RenderOptions] and are the
         * fallback when a key is unset — iframes/style off (security), autolink on.
         */
        const val RENDER_ALLOW_IFRAMES = "render.allowIframes"
        const val RENDER_ALLOW_STYLE = "render.allowStyleAttr"
        const val RENDER_AUTOLINK = "render.autolink"
        const val RENDER_LINE_BREAKS = "render.lineBreaks"

        /**
         * Whether links leaving the wiki get an "opens elsewhere" icon (see
         * [com.wikikt.markdown.ExternalLinkMode]): `site` (the default — mark links leaving this site),
         * `instance` (mark only links leaving the whole instance), or `off` (no marker). Stored as the
         * lowercased enum name.
         */
        const val RENDER_EXTERNAL_LINK_ICON = "render.externalLinkIcon"
        val EXTERNAL_LINK_MODE_OPTIONS = listOf("off", "site", "instance")
        const val DEFAULT_EXTERNAL_LINK_ICON = "site"

        /**
         * Monotonic "render settings version": bumped whenever a `render.*` toggle changes, so the
         * server-side render cache (page_render_cache) treats every row built under an older epoch as
         * stale and re-renders it. See [renderEpoch] / [bumpRenderEpoch] and
         * [com.wikikt.service.PageRenderService].
         */
        const val RENDER_EPOCH = "render.epoch"

        /**
         * When true, paths without a locale prefix (`/home`) 301-redirect to the primary locale
         * (`/en/home`), making the locale-qualified URL canonical. When false (the default), the
         * locale is inferred but the unprefixed URL is served as-is — no redirect.
         */
        const val LOCALE_FORCE_PREFIX = "locale.forcePrefix"

        /**
         * How many prior versions of a page / an asset to keep (per site). Old revisions beyond the
         * limit are pruned when a new one is created. Page history is cheap (text) so it defaults
         * higher; asset history holds binary bytes so it defaults low. The asset default falls back to
         * the `wikikt.assets.maxAssetVersions` config/env when that is set (see call sites).
         */
        const val HISTORY_MAX_PAGE_REVISIONS = "history.maxPageRevisions"
        const val HISTORY_MAX_ASSET_REVISIONS = "history.maxAssetRevisions"
        const val DEFAULT_MAX_PAGE_REVISIONS = 10
        const val DEFAULT_MAX_ASSET_REVISIONS = 3
        const val MAX_HISTORY_LIMIT = 500 // sanity ceiling for the UI-entered values

        /**
         * How many files one asset upload may carry (per site), governing both the /f upload form and the
         * editor's multi-file "Upload". Unset falls back to the `wikikt.assets.maxFilesPerUpload` config/env
         * (see call sites). Offered in the admin UI as a dropdown of [UPLOAD_FILE_LIMIT_OPTIONS] presets.
         */
        const val ASSETS_MAX_FILES_PER_UPLOAD = "assets.maxFilesPerUpload"
        const val MAX_UPLOAD_FILE_LIMIT = 500 // sanity ceiling
        val UPLOAD_FILE_LIMIT_OPTIONS = listOf(5, 10, 20, 30, 50, 100)

        /**
         * Whether uploaded images are stripped of privacy-sensitive metadata (EXIF GPS/camera info,
         * XMP, IPTC, comments) at upload time. On by default — on a public wiki a contributor's photo
         * would otherwise leak its capture location. Admins can disable it (Administration > Storage)
         * when metadata must be preserved. See [com.wikikt.service.MetadataStripper].
         */
        const val ASSETS_STRIP_METADATA = "assets.stripMetadata"
        const val DEFAULT_STRIP_METADATA = true

        /**
         * Common content locales offered as a checklist in Settings (code -> display name). Curated, not
         * exhaustive; any code already configured but not listed here is merged in so it's never lost.
         */
        val COMMON_LOCALES: List<Pair<String, String>> = listOf(
            "en" to "English", "en-GB" to "English (UK)", "en-US" to "English (US)",
            "fr" to "French", "fr-CA" to "French (Canada)",
            "es" to "Spanish", "es-ES" to "Spanish (Spain)", "es-MX" to "Spanish (Mexico)",
            "pt" to "Portuguese", "pt-BR" to "Portuguese (Brazil)", "pt-PT" to "Portuguese (Portugal)",
            "de" to "German", "de-AT" to "German (Austria)", "it" to "Italian", "nl" to "Dutch",
            "sv" to "Swedish", "no" to "Norwegian", "da" to "Danish", "fi" to "Finnish",
            "pl" to "Polish", "cs" to "Czech", "ro" to "Romanian", "hu" to "Hungarian",
            "el" to "Greek", "tr" to "Turkish", "ru" to "Russian", "uk" to "Ukrainian",
            "ar" to "Arabic", "he" to "Hebrew", "hi" to "Hindi", "th" to "Thai",
            "vi" to "Vietnamese", "id" to "Indonesian",
            "zh" to "Chinese", "zh-CN" to "Chinese (Simplified)", "zh-TW" to "Chinese (Traditional)",
            "ja" to "Japanese", "ko" to "Korean",
        )

        /**
         * Git synchronization (Administration > Git Sync). Content is mirrored to a git repository
         * in WikiJS-compatible layout; phase 1 supports mode `off` | `push` (wiki → repository).
         */
        const val GIT_SYNC_MODE = "gitSync.mode"
        const val GIT_SYNC_REPO_URL = "gitSync.repoUrl"
        const val GIT_SYNC_BRANCH = "gitSync.branch"
        const val GIT_SYNC_USERNAME = "gitSync.username"
        // SECURITY: stored plaintext in app_settings, and embedded in the local clone's remote URL
        // (.git/config) for HTTPS auth — both live in the server's data dir / database trust domain.
        const val GIT_SYNC_TOKEN = "gitSync.token"
        const val GIT_SYNC_AUTHOR_NAME = "gitSync.authorName"
        const val GIT_SYNC_AUTHOR_EMAIL = "gitSync.authorEmail"
        /** Automatic sync cadence in minutes; 0 = manual only. Runs whenever the mode isn't off. */
        const val GIT_SYNC_INTERVAL_MINUTES = "gitSync.intervalMinutes"
        /** Status of the last sync run (written by GitSyncService, displayed read-only). */
        const val GIT_SYNC_LAST_RUN_AT = "gitSync.lastRunAt"
        const val GIT_SYNC_LAST_OK = "gitSync.lastOk"
        const val GIT_SYNC_LAST_RESULT = "gitSync.lastResult"
        const val GIT_SYNC_LAST_COMMIT = "gitSync.lastCommit"
        /** The remote commit the wiki was last synced against — pulls apply only the diff since it. */
        const val GIT_SYNC_LAST_SYNCED_COMMIT = "gitSync.lastSyncedCommit"
        val GIT_SYNC_MODE_OPTIONS = listOf("off", "push", "pull", "bidirectional")
        val GIT_SYNC_INTERVAL_OPTIONS = listOf(0, 5, 15, 30, 60)
        const val DEFAULT_GIT_SYNC_BRANCH = "main"
        const val DEFAULT_GIT_SYNC_INTERVAL_MINUTES = 0

        /**
         * Release update check opt-in (Administration > Updates, root only). Instance-wide, so it's
         * read/written via [instanceAnchorSiteId], not the admin's selected site. Tri-state: absent =
         * never asked (the page shows a consent card and performs NO network call), "true" = enabled,
         * "false" = explicitly declined. This is the only control over the check: unset or false and
         * the app never contacts api.github.com.
         */
        const val UPDATE_CHECK_ENABLED = "update.checkEnabled"

        /**
         * Audit breadcrumb of the last self-update request (who clicked Install, when, from which
         * version). Instance-wide like [UPDATE_CHECK_ENABLED]. Written just before the request file;
         * survives the app's own replacement so the new instance can corroborate the updater's
         * status.json with "requested by X at Y". Not secrets; fine in backups.
         */
        const val UPDATE_LAST_REQUEST_ID = "update.lastRequestId"
        const val UPDATE_LAST_REQUESTED_AT = "update.lastRequestedAt"
        const val UPDATE_LAST_REQUESTED_BY = "update.lastRequestedBy"
        const val UPDATE_LAST_REQUESTED_FROM = "update.lastRequestedFrom"

        /**
         * Settings whose values are plaintext credentials. A full backup NEVER writes these into its
         * database dump; they're either dropped (default) or carried separately, password-encrypted, in
         * `secrets.json` (see [BackupService] / [BackupCrypto]). Everything else — including one-way
         * password/API-key hashes — is safe to dump as-is.
         */
        val SENSITIVE_SETTING_KEYS = setOf(MAIL_SMTP_PASSWORD, GIT_SYNC_TOKEN)

        /**
         * Email / SMTP (Administration > Mail). Per-site, mirroring the Git Sync settings pattern: the
         * connection config is runtime-editable (not static yaml) so an admin can point the site at an
         * SMTP relay without a redeploy. Outbound mail (welcome, password reset, admin notifications) is
         * enqueued to [com.wikikt.db.EmailQueueTable] and drained by the EmailService worker.
         */
        const val MAIL_ENABLED = "mail.enabled"
        const val MAIL_SMTP_HOST = "mail.smtpHost"
        const val MAIL_SMTP_PORT = "mail.smtpPort"
        // starttls (587, upgrade in-band) | ssl (465, implicit TLS) | none (plaintext, dev/relay only).
        const val MAIL_SMTP_SECURITY = "mail.smtpSecurity"
        const val MAIL_SMTP_USERNAME = "mail.smtpUsername"
        // SECURITY: stored plaintext in app_settings, like the git-sync token — same data-dir trust domain.
        const val MAIL_SMTP_PASSWORD = "mail.smtpPassword"
        const val MAIL_FROM_ADDRESS = "mail.fromAddress"
        const val MAIL_FROM_NAME = "mail.fromName"
        /** Comma/space-separated extra recipients notified of admin events (new users, etc.); optional. */
        const val MAIL_ADMIN_RECIPIENTS = "mail.adminRecipients"
        /** Status of the last send attempt (written by EmailService, displayed read-only). */
        const val MAIL_LAST_RUN_AT = "mail.lastRunAt"
        const val MAIL_LAST_OK = "mail.lastOk"
        const val MAIL_LAST_RESULT = "mail.lastResult"
        val MAIL_SECURITY_OPTIONS = listOf("starttls", "ssl", "none")
        const val DEFAULT_MAIL_SMTP_PORT = 587
        const val DEFAULT_MAIL_SECURITY = "starttls"

        /**
         * Self-service registration (Administration > Settings > General). Off by default — the wiki is
         * private and accounts are created only by an admin. When on, anonymous visitors can register at
         * `/register`; because they must confirm their address, it also requires [MAIL_ENABLED] (the form
         * hides itself when mail is off, exactly like password reset).
         *  - [REGISTRATION_ALLOWED_DOMAINS] — optional comma/space/newline list of email domains allowed
         *    to sign up (empty = any). The key control for a semi-public single-org wiki.
         *  - [REGISTRATION_DEFAULT_GROUP] — group id new accounts are placed in (blank = none; they still
         *    get the implicit read-only "User" group). Point it at a write-capable group to let
         *    registrants edit.
         *  - [REGISTRATION_REQUIRE_APPROVAL] — when true, a confirmed account waits in PENDING_APPROVAL
         *    until an admin approves it before it can sign in.
         */
        const val REGISTRATION_ENABLED = "registration.enabled"
        const val REGISTRATION_ALLOWED_DOMAINS = "registration.allowedDomains"
        const val REGISTRATION_DEFAULT_GROUP = "registration.defaultGroup"
        const val REGISTRATION_REQUIRE_APPROVAL = "registration.requireApproval"

        /** Default product name used when [SITE_NAME] is unset, and the engine name in the default footer. */
        const val DEFAULT_SITE_NAME = "WikiKT"

        /** Allowed `<meta name="robots">` directives, and the default applied when unset. */
        const val DEFAULT_META_ROBOTS = "index,follow"
        val META_ROBOTS_OPTIONS = listOf("index,follow", "noindex,follow", "index,nofollow", "noindex,nofollow")

        /**
         * Whether a `#rgb`/`#rrggbb`/`#rrggbbaa` hex color is "dark" (so it needs light foreground text).
         * Uses perceived luminance (ITU-R BT.601); alpha is ignored. Malformed input is treated as light.
         */
        internal fun isDarkColor(hex: String): Boolean {
            val h = hex.removePrefix("#")
            val full = if (h.length == 3 || h.length == 4) h.take(3).flatMap { listOf(it, it) }.joinToString("") else h
            if (full.length < 6) return false
            return try {
                val r = full.substring(0, 2).toInt(16)
                val g = full.substring(2, 4).toInt(16)
                val b = full.substring(4, 6).toInt(16)
                (0.299 * r + 0.587 * g + 0.114 * b) < 140
            } catch (_: NumberFormatException) {
                false
            }
        }

        // Body backgrounds a brand color is judged against (Bootstrap's --bs-body-bg per theme), and the
        // WCAG AA text-contrast floor. Used to validate admin-chosen brand colors on save.
        const val BODY_BG_LIGHT = "#ffffff"
        const val BODY_BG_DARK = "#212529"
        const val MIN_AA_CONTRAST = 4.5

        /** `#rgb`/`#rrggbb`/`#rrggbbaa` to (r,g,b), or null if malformed. Alpha is ignored. */
        private fun hexToRgb(hex: String): Triple<Int, Int, Int>? {
            val h = hex.removePrefix("#")
            val full = if (h.length == 3 || h.length == 4) h.take(3).flatMap { listOf(it, it) }.joinToString("") else h
            if (full.length < 6) return null
            return try {
                Triple(full.substring(0, 2).toInt(16), full.substring(2, 4).toInt(16), full.substring(4, 6).toInt(16))
            } catch (_: NumberFormatException) {
                null
            }
        }

        /** WCAG relative luminance of an sRGB color (0..1). */
        private fun relativeLuminance(rgb: Triple<Int, Int, Int>): Double {
            fun channel(c: Int): Double {
                val s = c / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(rgb.first) + 0.7152 * channel(rgb.second) + 0.0722 * channel(rgb.third)
        }

        /** WCAG contrast ratio (1..21) between two hex colors, or null if either is malformed. */
        internal fun contrastRatio(hex1: String, hex2: String): Double? {
            val l1 = hexToRgb(hex1)?.let { relativeLuminance(it) } ?: return null
            val l2 = hexToRgb(hex2)?.let { relativeLuminance(it) } ?: return null
            val hi = maxOf(l1, l2)
            val lo = minOf(l1, l2)
            return (hi + 0.05) / (lo + 0.05)
        }

        /**
         * Validates the two brand colors for WCAG AA readability, returning a human error message or null
         * if both are fine. The light color must reach [MIN_AA_CONTRAST]:1 against a white body (it colors
         * links on white and is the button background with white text); the dark color must reach it
         * against the dark body. Blank means "unset" and always passes. Assumes each is already
         * hex-format-validated (a malformed value is treated as blank/cleared by the caller).
         */
        internal fun brandColorContrastError(lightColor: String, darkColor: String): String? {
            fun fmt(r: Double?) = if (r == null) "?" else (Math.round(r * 10) / 10.0).toString()
            if (lightColor.isNotBlank()) {
                val r = contrastRatio(lightColor, BODY_BG_LIGHT)
                if (r == null || r < MIN_AA_CONTRAST) {
                    return "Primary color $lightColor is too light: it needs a contrast of at least " +
                        "$MIN_AA_CONTRAST:1 against a white background for readable links and buttons " +
                        "(this is ${fmt(r)}:1). Choose a darker shade."
                }
            }
            if (darkColor.isNotBlank()) {
                val r = contrastRatio(darkColor, BODY_BG_DARK)
                if (r == null || r < MIN_AA_CONTRAST) {
                    return "Primary color (dark mode) $darkColor is too dark: it needs a contrast of at " +
                        "least $MIN_AA_CONTRAST:1 against the dark background for readable links " +
                        "(this is ${fmt(r)}:1). Choose a lighter shade."
                }
            }
            return null
        }

        /** Escapes text for safe interpolation into the (raw-HTML) default footer. */
        private fun htmlEscape(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
    }

    // Settings are per-site (one row per (site_id, key)); cached per site and invalidated on write.
    private val cache = java.util.concurrent.ConcurrentHashMap<UInt, Map<String, String>>()

    /**
     * Supplies the hostnames considered "internal to this instance" (every site's hostname plus the
     * configured public URL host), lowercased. Late-bound by [com.wikikt.AppContext] because
     * SettingsService is constructed before SiteService/config exist. Consulted only for the `instance`
     * external-link mode; null (unwired, e.g. in tests) yields an empty set — so every absolute link is
     * then treated as external, matching the simpler `site` mode.
     */
    @Volatile var instanceHostsProvider: (suspend () -> Set<String>)? = null

    /**
     * The site row that *instance-wide* (not per-site) settings are anchored to. `app_settings` is
     * keyed (siteId, key) with a real FK into `sites`, so instance-scoped values — like the update
     * check opt-in — need *some* site row; the catch-all is used because SiteService.delete refuses
     * to remove it (mirrors SiteService.catchAll: the flagged row, else the first). Caveat: an admin
     * re-flagging which site is the catch-all relocates the anchor, which for these keys just means
     * re-consenting once. Promote to a dedicated `system_settings` table if this grows past a few
     * keys (and remember BACKUP_TABLES + MigrationDriftTest if it does).
     */
    suspend fun instanceAnchorSiteId(): UInt = suspendTransaction(database) {
        com.wikikt.db.SitesTable.selectAll()
            .orderBy(com.wikikt.db.SitesTable.isCatchAll, org.jetbrains.exposed.v1.core.SortOrder.DESC)
            .orderBy(com.wikikt.db.SitesTable.id, org.jetbrains.exposed.v1.core.SortOrder.ASC)
            .limit(1)
            .map { it[com.wikikt.db.SitesTable.id].value }
            .toList()
            .firstOrNull()
    } ?: error("No sites exist; the seed should have created one")

    private suspend fun all(siteId: UInt): Map<String, String> {
        cache[siteId]?.let { return it }
        val loaded = suspendTransaction(database) {
            AppSettingsTable.selectAll().where { AppSettingsTable.siteId eq siteId }
                .map { it[AppSettingsTable.key] to it[AppSettingsTable.value] }
                .toList()
                .toMap()
        }
        cache[siteId] = loaded
        return loaded
    }

    suspend fun get(siteId: UInt, key: String): String? = all(siteId)[key]

    suspend fun getBool(siteId: UInt, key: String, default: Boolean = false): Boolean =
        get(siteId, key)?.toBooleanStrictOrNull() ?: default

    /** Reads an int setting, clamped to 1..[MAX_HISTORY_LIMIT]; returns [default] (also clamped) if unset/invalid. */
    suspend fun getHistoryLimit(siteId: UInt, key: String, default: Int): Int =
        (get(siteId, key)?.toIntOrNull() ?: default).coerceIn(1, MAX_HISTORY_LIMIT)

    /** The per-upload file-count limit, clamped to 1..[MAX_UPLOAD_FILE_LIMIT]; [default] (also clamped) if unset/invalid. */
    suspend fun uploadFileLimit(siteId: UInt, default: Int): Int =
        (get(siteId, ASSETS_MAX_FILES_PER_UPLOAD)?.toIntOrNull() ?: default).coerceIn(1, MAX_UPLOAD_FILE_LIMIT)

    /** The site's wiki-sidebar navigation mode (one of [NAV_MODE_OPTIONS]); [DEFAULT_NAV_MODE] if unset/invalid. */
    suspend fun navMode(siteId: UInt): String =
        get(siteId, NAV_MODE)?.ifBlank { null }?.takeIf { it in NAV_MODE_OPTIONS } ?: DEFAULT_NAV_MODE

    /**
     * The registration email-domain allowlist as a normalized lowercase set, parsed from the
     * comma/space/newline/semicolon-separated [REGISTRATION_ALLOWED_DOMAINS] value (a leading `@` on an
     * entry is tolerated). Empty = no restriction (any domain may register).
     */
    suspend fun registrationAllowedDomains(siteId: UInt): Set<String> =
        get(siteId, REGISTRATION_ALLOWED_DOMAINS).orEmpty()
            .split(',', ' ', '\n', '\r', '\t', ';')
            .map { it.trim().removePrefix("@").lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Whether [email]'s domain may self-register: true when the allowlist is empty, otherwise the
     * address's domain (case-insensitive) must appear in [registrationAllowedDomains].
     */
    suspend fun isRegistrationDomainAllowed(siteId: UInt, email: String): Boolean {
        val allowed = registrationAllowedDomains(siteId)
        if (allowed.isEmpty()) return true
        val domain = email.substringAfterLast('@', "").trim().lowercase()
        return domain.isNotEmpty() && domain in allowed
    }

    suspend fun set(siteId: UInt, key: String, value: String) {
        suspendTransaction(database) {
            val updated = AppSettingsTable.update({ (AppSettingsTable.siteId eq siteId) and (AppSettingsTable.key eq key) }) {
                it[AppSettingsTable.value] = value
            }
            if (updated == 0) {
                AppSettingsTable.insert {
                    it[AppSettingsTable.siteId] = siteId
                    it[AppSettingsTable.key] = key
                    it[AppSettingsTable.value] = value
                }
            }
        }
        cache.remove(siteId) // invalidate this site; next read reloads
    }

    suspend fun setBool(siteId: UInt, key: String, value: Boolean) = set(siteId, key, value.toString())

    /** Drops the in-memory cache. Call after writing settings rows outside this service (restore). */
    fun invalidateCache() {
        cache.clear()
    }

    /** The current render-settings version (0 when never bumped). Part of every render cache row's key. */
    suspend fun renderEpoch(siteId: UInt): Long = get(siteId, RENDER_EPOCH)?.toLongOrNull() ?: 0L

    /** Advances [renderEpoch], invalidating every cached page render. Call after saving `render.*` toggles. */
    suspend fun bumpRenderEpoch(siteId: UInt) {
        set(siteId, RENDER_EPOCH, (renderEpoch(siteId) + 1).toString())
    }

    /**
     * The current content-rendering toggles, assembled from stored settings with the [RenderOptions]
     * defaults for any unset key. Read on every page render (WikiRouting.renderContent).
     */
    suspend fun renderOptions(siteId: UInt): RenderOptions {
        val d = RenderOptions.DEFAULT
        val externalLinkMode = ExternalLinkMode.from(get(siteId, RENDER_EXTERNAL_LINK_ICON) ?: DEFAULT_EXTERNAL_LINK_ICON)
        // Only the `instance` mode needs the host set; skip the (cheap, cached) lookup otherwise.
        val internalHosts = if (externalLinkMode == ExternalLinkMode.INSTANCE) {
            instanceHostsProvider?.invoke().orEmpty()
        } else {
            emptySet()
        }
        return RenderOptions(
            allowIframes = getBool(siteId, RENDER_ALLOW_IFRAMES, d.allowIframes),
            allowStyleAttr = getBool(siteId, RENDER_ALLOW_STYLE, d.allowStyleAttr),
            autoLink = getBool(siteId, RENDER_AUTOLINK, d.autoLink),
            lineBreaks = getBool(siteId, RENDER_LINE_BREAKS, d.lineBreaks),
            externalLinkMode = externalLinkMode,
            internalHosts = internalHosts,
        )
    }

    /**
     * The content locales enabled for [siteId]: the configured [defaultLocale] (always first) plus any
     * [SITE_LOCALES] the admin added, canonicalized and de-duplicated. This is the source for the locale
     * dropdowns shown when creating, moving, or uploading content.
     */
    suspend fun enabledLocales(siteId: UInt, defaultLocale: String): List<String> {
        val default = com.wikikt.model.normalizeLocale(defaultLocale) ?: defaultLocale
        val extra = get(siteId, SITE_LOCALES).orEmpty()
            .split(',', ' ', '\n', '\t')
            .mapNotNull { com.wikikt.model.normalizeLocale(it) }
        return (listOf(default) + extra).distinct()
    }

    /**
     * Branding + footer values injected into every rendered template (see the send-pipeline
     * interceptor in `Application.module`). [SITE_NAME] falls back to [DEFAULT_SITE_NAME]; the rest
     * are null when unset. [footerHtml] is pre-rendered safe HTML; [markdown]/[year] are passed in
     * because this service has neither a renderer nor a clock.
     */
    suspend fun brandingModel(siteId: UInt, markdown: MarkdownRenderer, year: Int): Map<String, Any?> {
        val s = all(siteId)
        val (bodyStack, bodySpec) = resolveFont(s[APPEARANCE_BODY_FONT] ?: DEFAULT_BODY_FONT, s[APPEARANCE_BODY_FONT_CUSTOM])
        val (headStack, headSpec) = resolveFont(s[APPEARANCE_HEADING_FONT] ?: DEFAULT_HEADING_FONT, s[APPEARANCE_HEADING_FONT_CUSTOM])
        val baseSize = s[APPEARANCE_BASE_FONT_SIZE]?.toIntOrNull()?.coerceIn(12, 24) ?: DEFAULT_BASE_FONT_SIZE
        val emojiFont = s[APPEARANCE_EMOJI_FONT]?.toBooleanStrictOrNull() ?: DEFAULT_EMOJI_FONT
        return mapOf(
            "siteName" to (s[SITE_NAME]?.ifBlank { null } ?: DEFAULT_SITE_NAME),
            "siteLogoUrl" to (s[SITE_LOGO_URL]?.ifBlank { null } ?: DEFAULT_LOGO_URL),
            "siteBrandColor" to s[SITE_BRAND_COLOR]?.ifBlank { null },
            // Dark-mode link color; drives only dark-mode links (see brand-style.hbs). Unset → dark links
            // keep Bootstrap's default, so a light-tuned brand color never lands unreadable on the dark body.
            "siteBrandColorDark" to s[SITE_BRAND_COLOR_DARK]?.ifBlank { null },
            // Header colors per color mode; the *IsDark flags pick light vs dark navbar foregrounds
            // (unused unless the matching color is set).
            "siteHeaderColor" to (s[SITE_HEADER_COLOR]?.ifBlank { null }),
            "siteHeaderColorIsDark" to (s[SITE_HEADER_COLOR]?.ifBlank { null }?.let { isDarkColor(it) } ?: true),
            "siteHeaderColorDark" to (s[SITE_HEADER_COLOR_DARK]?.ifBlank { null }),
            "siteHeaderColorDarkIsDark" to (s[SITE_HEADER_COLOR_DARK]?.ifBlank { null }?.let { isDarkColor(it) } ?: true),
            // Sidebar colors per color mode; the *IsDark flags pick white vs black foreground tints
            // (default true — unused unless the matching color is set).
            "siteSidebarColor" to (s[SITE_SIDEBAR_COLOR]?.ifBlank { null }),
            "siteSidebarColorIsDark" to (s[SITE_SIDEBAR_COLOR]?.ifBlank { null }?.let { isDarkColor(it) } ?: true),
            "siteSidebarColorDark" to (s[SITE_SIDEBAR_COLOR_DARK]?.ifBlank { null }),
            "siteSidebarColorDarkIsDark" to (s[SITE_SIDEBAR_COLOR_DARK]?.ifBlank { null }?.let { isDarkColor(it) } ?: true),
            // Divider between the header bar and the sidebar, per color mode. A line, not a surface, so
            // (like the heading color) it needs no *IsDark companion.
            "siteSidebarHeaderLineColor" to (s[SITE_SIDEBAR_HEADER_LINE_COLOR]?.ifBlank { null }),
            "siteSidebarHeaderLineColorDark" to (s[SITE_SIDEBAR_HEADER_LINE_COLOR_DARK]?.ifBlank { null }),
            // Sidebar menu heading color per color mode. No *IsDark companion: this is a foreground, not a
            // surface, so nothing has to be flipped for contrast against it.
            "siteNavHeadingColor" to (s[SITE_NAV_HEADING_COLOR]?.ifBlank { null }),
            "siteNavHeadingColorDark" to (s[SITE_NAV_HEADING_COLOR_DARK]?.ifBlank { null }),
            "siteHeadingLineColor" to (s[SITE_HEADING_LINE_COLOR]?.ifBlank { null }),
            // Search box surface, resolved to a concrete "light"/"dark" per color mode (see
            // searchBoxSurface). The two land on data attributes in partials/header.hbs, so the CSS can
            // branch on the live root theme rather than on a server guess about which mode is showing.
            "searchBoxLightMode" to searchBoxSurface(s, darkMode = false),
            "searchBoxDarkMode" to searchBoxSurface(s, darkMode = true),
            "faviconUrl" to (s[SITE_FAVICON_URL]?.ifBlank { null } ?: DEFAULT_FAVICON_URL),
            "footerHtml" to footerHtml(s, markdown, year),
            // Site default color mode; the no-FOUC script in head-styles.hbs applies it. A logged-in
            // user's saved theme overrides this (set in Application.kt); a per-visitor localStorage choice
            // overrides it for guests.
            "themeMode" to (s[APPEARANCE_THEME]?.ifBlank { null }?.takeIf { it in THEME_OPTIONS } ?: DEFAULT_THEME),
            "showThemePicker" to (s[APPEARANCE_SHOW_THEME_PICKER]?.toBooleanStrictOrNull() ?: true),
            // Raw admin HTML injected verbatim into <head> / end of <body> (null when unset).
            "headHtml" to s[APPEARANCE_HEAD_HTML]?.ifBlank { null },
            "bodyHtml" to s[APPEARANCE_BODY_HTML]?.ifBlank { null },
            // Typography + custom styling, consumed by partials/head-styles.hbs on every page.
            "googleFontsUrl" to googleFontsUrl(listOfNotNull(bodySpec, headSpec)),
            "bodyFontStack" to withEmojiFont(bodyStack, emojiFont),
            "headingFontStack" to withEmojiFont(headStack, emojiFont),
            "baseFontSize" to baseSize,
            // Loads the Noto Color Emoji @font-face rules the stacks above now reference (head-styles.hbs).
            "emojiFont" to emojiFont,
            "customCss" to sanitizeCustomCss(s[APPEARANCE_CUSTOM_CSS]),
        )
    }

    /**
     * The header search box surface — `"light"` or `"dark"` — for one color mode, per
     * [SITE_SEARCH_BOX_THEME]. Both modes are resolved up front so the CSS can branch on the live root
     * theme; only `theme` actually varies between them. An unknown stored value falls back to `theme`,
     * so a bad write can't strand the box on one surface.
     */
    private fun searchBoxSurface(s: Map<String, String>, darkMode: Boolean): String =
        when (s[SITE_SEARCH_BOX_THEME]?.ifBlank { null } ?: DEFAULT_SEARCH_BOX_THEME) {
            "light" -> "light"
            "dark" -> "dark"
            else -> if (darkMode) "dark" else "light"
        }

    /** Resolves a stored (preset key, custom stack) pair to (css font-family stack, Google Fonts spec?). */
    private fun resolveFont(key: String?, custom: String?): Pair<String, String?> {
        val preset = FONT_PRESETS.firstOrNull { it.key == key }
        if (preset != null && preset.key != "custom") return preset.stack to preset.googleSpec
        // "custom" (or an unknown key): use the admin-entered stack; author loads it via Custom CSS.
        val stack = sanitizeFontStack(custom).ifBlank { "system-ui, sans-serif" }
        return stack to null
    }

    /** Appends the emoji webfont to a resolved font stack when the setting is on (see
     *  [APPEARANCE_EMOJI_FONT]). A blank stack can't happen for the presets, but a custom one falls back
     *  in [resolveFont], so this never produces a leading comma. */
    private fun withEmojiFont(stack: String, enabled: Boolean): String =
        if (enabled && stack.isNotBlank()) "$stack, $EMOJI_FONT_FAMILY" else stack

    /** Builds the Google Fonts stylesheet URL for the given `family=` specs, or null if there are none. */
    private fun googleFontsUrl(specs: List<String>): String? {
        val distinct = specs.distinct()
        if (distinct.isEmpty()) return null
        return "https://fonts.googleapis.com/css2?" + distinct.joinToString("&") { "family=$it" } + "&display=swap"
    }

    /** Keeps only characters valid in a CSS font-family value; guards the inline <style> from breakout. */
    private fun sanitizeFontStack(value: String?): String =
        value.orEmpty().filter { it.isLetterOrDigit() || it in " ,'\"._-" }.trim().take(200)

    /**
     * Author CSS for the <head>. Strips `<` (never valid in CSS) so the value can't close the injected
     * `<style>` or open a tag, and caps the length. Returns null when empty. Not a full CSS parser —
     * it's an admin-only field, so this just prevents HTML-context breakout, not malicious CSS.
     */
    private fun sanitizeCustomCss(value: String?): String? =
        value?.replace("<", "")?.take(MAX_CUSTOM_CSS_LENGTH)?.ifBlank { null }

    /** The header name + value to send for [siteId], the admin's extra sources merged into the baseline. */
    data class CspHeader(val name: String, val value: String)

    /** Builds the effective Content-Security-Policy for [siteId] (baseline + validated admin additions). */
    suspend fun contentSecurityPolicy(siteId: UInt): CspHeader {
        val s = all(siteId)
        val value = CSP_DIRECTIVES.joinToString("; ") { d ->
            val extra = d.settingKey?.let { sanitizeCspSources(s[it]) } ?: emptyList()
            "${d.name} ${(d.baseline + extra).distinct().joinToString(" ")}"
        }
        val reportOnly = s[SECURITY_CSP_REPORT_ONLY]?.toBooleanStrictOrNull() ?: false
        return CspHeader(if (reportOnly) "Content-Security-Policy-Report-Only" else "Content-Security-Policy", value)
    }

    /**
     * The site footer as safe HTML. When [SITE_FOOTER_OVERRIDE] is set, it's rendered as Markdown
     * (sanitized) and used verbatim. Otherwise the default is built from the org name + license:
     * `© {year} {Org}. {License} | Powered by WikiKT` (each piece omitted when blank).
     */
    private fun footerHtml(s: Map<String, String>, markdown: MarkdownRenderer, year: Int): String {
        s[SITE_FOOTER_OVERRIDE]?.ifBlank { null }?.let { return markdown.render(it, ContentFormat.MARKDOWN) }
        val org = s[SITE_ORG_NAME]?.ifBlank { null }
        val license = s[SITE_CONTENT_LICENSE]?.ifBlank { null }
        val copyright = "© $year" + (org?.let { " $it" } ?: "")
        val sentence = if (license != null) "$copyright. $license" else copyright
        return "<span>${htmlEscape("$sentence | Powered by $DEFAULT_SITE_NAME")}</span>"
    }
}
