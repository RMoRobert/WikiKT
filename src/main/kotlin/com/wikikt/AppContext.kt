package com.wikikt

import com.wikikt.config.WikiKtConfig
import com.wikikt.config.loadWikiKtConfig
import com.wikikt.db.DatabaseFactory
import com.wikikt.markdown.MarkdownRenderer
import com.wikikt.service.ApiKeyService
import com.wikikt.service.AssetService
import com.wikikt.service.BackupService
import com.wikikt.service.ContentImporter
import com.wikikt.service.EmailService
import com.wikikt.service.EmailTemplateService
import com.wikikt.service.EmailVerificationService
import com.wikikt.service.GitSyncService
import com.wikikt.service.GroupPageRuleService
import com.wikikt.service.GroupService
import com.wikikt.service.InfoboxService
import com.wikikt.service.MfaService
import com.wikikt.service.PageService
import com.wikikt.service.UpdateService
import com.wikikt.service.PageRenderService
import com.wikikt.service.PasswordResetService
import com.wikikt.service.FragmentService
import com.wikikt.service.MigrationService
import com.wikikt.service.NavService
import com.wikikt.service.PermissionService
import com.wikikt.service.SearchIndexService
import com.wikikt.service.SeedService
import com.wikikt.service.SessionService
import com.wikikt.service.SettingsService
import com.wikikt.service.SiteService
import com.wikikt.service.UserService
import com.wikikt.model.SiteRecord
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

class AppContext(
    val config: WikiKtConfig,
    val database: R2dbcDatabase,
    val sites: SiteService,
    val users: UserService,
    val groups: GroupService,
    val pages: PageService,
    val permissions: PermissionService,
    val sessions: SessionService,
    val passwordReset: PasswordResetService,
    val emailVerification: EmailVerificationService,
    val mfa: MfaService,
    val apiKeys: ApiKeyService,
    val nav: NavService,
    val fragments: FragmentService,
    val groupPageRules: GroupPageRuleService,
    val assets: AssetService,
    val settings: SettingsService,
    val searchIndex: SearchIndexService,
    val renderCache: PageRenderService,
    val infobox: InfoboxService,
    val gitSync: GitSyncService,
    val update: UpdateService,
    val backup: BackupService,
    val emailTemplates: EmailTemplateService,
    val email: EmailService,
    val markdown: MarkdownRenderer,
    val seed: SeedService,
)

private val AppContextKey = AttributeKey<AppContext>("AppContext")

val Application.appContext: AppContext
    get() = attributes[AppContextKey]

val ApplicationCall.appContext: AppContext
    get() = application.appContext

private val CurrentSiteKey = AttributeKey<SiteRecord>("wikikt.currentSite")

/**
 * The site serving THIS request, resolved from the request host (honoring X-Forwarded-Host via the
 * origin) with the catch-all as fallback. Cached in request attributes. Used for front-end (visitor)
 * requests; admin requests use the session-selected site (see ApplicationCall.adminSiteId).
 */
suspend fun ApplicationCall.currentSite(): SiteRecord {
    attributes.getOrNull(CurrentSiteKey)?.let { return it }
    val site = appContext.sites.resolve(request.origin.serverHost)
        ?: error("No site is configured (seeding should create a catch-all site)")
    attributes.put(CurrentSiteKey, site)
    return site
}

suspend fun ApplicationCall.siteId(): UInt = currentSite().id

/**
 * The site the ADMIN console is currently managing: the site-switcher selection from the session (when
 * it still points at a real site), else the request's site. Distinct from [currentSite] so an admin can
 * manage any site from one login regardless of the hostname they arrived on.
 */
suspend fun ApplicationCall.adminSiteId(): UInt {
    val selected = sessions.get<com.wikikt.auth.AdminSiteSession>()?.siteId
    if (selected != null && appContext.sites.byId(selected) != null) return selected
    return siteId()
}

suspend fun Application.createAppContext(): AppContext {
    val config = loadWikiKtConfig()
    val database = DatabaseFactory.connect(config.database)
    MigrationService(database).migrate()

    val sites = SiteService(database)
    val users = UserService(database)
    val groups = GroupService(database)
    val pages = PageService(database)
    val groupPageRules = GroupPageRuleService(database)
    val permissions = PermissionService(users, groups, pages, groupPageRules)
    val sessions = SessionService(database)
    val passwordReset = PasswordResetService(database)
    val emailVerification = EmailVerificationService(database)
    val mfa = MfaService(database, com.wikikt.auth.MfaSecretCipher(config.mfaEncryptionKey))
    val apiKeys = ApiKeyService(database)
    val nav = NavService(database)
    val fragments = FragmentService(database)
    val settings = SettingsService(database)
    // "Instance-internal" hosts for the `instance` external-link mode: every site's hostname plus the
    // configured public URL host, lowercased. Recomputed on each render from the (cached) site list, so
    // adding/removing a site is reflected without a settings change. Late-bound because SettingsService
    // is built before SiteService here.
    val publicUrlHost = config.publicUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.lowercase()
    settings.instanceHostsProvider = {
        (sites.all().mapNotNull { it.hostname?.lowercase() } + listOfNotNull(publicUrlHost)).toSet()
    }
    val markdown = MarkdownRenderer()
    val infobox = InfoboxService(database, markdown, settings)

    // Asset storage: create the dir (+ tmp) up front and fail fast if it isn't writable.
    val assetDir = config.assets.storageDir
    java.nio.file.Files.createDirectories(assetDir.resolve("tmp"))
    require(java.nio.file.Files.isWritable(assetDir)) { "Asset storage dir is not writable: $assetDir" }
    val assets = AssetService(database, assetDir)

    val seed = SeedService(database, config, sites, pages)
    seed.seedIfEmpty()

    // Search index + render cache: both are rebuilt when a page's live content changes (one page) or a
    // fragment changes (only the pages that transclude it). The render cache holds each live page's
    // rendered body so views serve stored HTML instead of re-rendering. Late-bound callbacks avoid a
    // service dependency cycle; each side is wrapped so one failing never blocks the save or the other.
    val searchIndex = SearchIndexService(pages, fragments, config.defaultLocale)
    val renderCache = PageRenderService(database, pages, fragments, markdown, settings, infobox, config.defaultLocale)
    pages.onContentChanged = { pageId ->
        runCatching { searchIndex.reindex(pageId) }
        runCatching { renderCache.rebuild(pageId) }
    }
    // Per-site cap on retained page revisions; pruned after each content change.
    pages.pageRevisionLimit = { site ->
        settings.getHistoryLimit(site, SettingsService.HISTORY_MAX_PAGE_REVISIONS, SettingsService.DEFAULT_MAX_PAGE_REVISIONS)
    }
    fragments.onFragmentsChanged = { siteId, keys ->
        runCatching { searchIndex.reindexForFragmentKeys(siteId, keys) }
        runCatching { renderCache.invalidateForFragmentKeys(siteId, keys) }
    }
    // When a page appears/disappears at a path (create/delete/move), re-render the pages that LINK to
    // it so their internal links flip red↔blue (redlinks). Cheap: these events are infrequent.
    pages.onPageExistenceChanged = { siteId, locale, path -> runCatching { renderCache.invalidateBacklinks(siteId, locale, path) } }
    // Build search-index rows for any page that lacks one — notably the pages just seeded above, since
    // onContentChanged (which indexes on write) was only wired after seedIfEmpty() ran.
    searchIndex.reindexMissing()

    // External content ingestion (git pulls + backup restores) shares one upsert path, so both
    // land in revision history and never delete. Git sync clone dir is created on first run.
    val importer = ContentImporter(
        pages, assets,
        maxAssetVersionsFor = { site ->
            settings.getHistoryLimit(site, SettingsService.HISTORY_MAX_ASSET_REVISIONS, config.assets.maxAssetVersions)
        },
        allowedAssetMimeTypes = config.assets.allowedMimeTypes,
    )
    val gitSync = GitSyncService(settings, sites, pages, assets, config.gitSync.dir, importer, config.defaultLocale)
    // Deleting a site cascades through the content services and drops its git-sync clone; wire them now
    // that they all exist (gitSync is the last, so this can't move earlier alongside the content services).
    sites.wireCascade(pages, assets, fragments, nav, settings, gitSync)
    val backup = BackupService(database, sites, pages, assets, fragments, nav, importer, settings, searchIndex, assetDir)
    // Release update check (Administration > Updates). Lazy: only fetches when a root admin views the
    // page after opting in; WIKIKT_UPDATE_CHECK=off makes the network path unreachable entirely.
    val update = UpdateService(settings, config.updateCheckAllowed)

    // Email: templates (defaults + per-site overrides) render into messages that the queue-backed
    // EmailService enqueues; a background worker (Application.startEmailWorker) drains the queue over SMTP.
    val emailTemplates = EmailTemplateService(database)
    val email = EmailService(database, emailTemplates, settings, com.wikikt.service.email.SmtpEmailSender())

    return AppContext(
        config = config,
        database = database,
        sites = sites,
        users = users,
        groups = groups,
        pages = pages,
        permissions = permissions,
        sessions = sessions,
        passwordReset = passwordReset,
        emailVerification = emailVerification,
        mfa = mfa,
        apiKeys = apiKeys,
        nav = nav,
        fragments = fragments,
        groupPageRules = groupPageRules,
        assets = assets,
        settings = settings,
        searchIndex = searchIndex,
        renderCache = renderCache,
        infobox = infobox,
        gitSync = gitSync,
        update = update,
        backup = backup,
        emailTemplates = emailTemplates,
        email = email,
        markdown = markdown,
        seed = seed,
    ).also { attributes[AppContextKey] = it }
}
