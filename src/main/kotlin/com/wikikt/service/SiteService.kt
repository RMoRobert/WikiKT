package com.wikikt.service

import com.wikikt.db.AppSettingsTable
import com.wikikt.db.SitesTable
import com.wikikt.model.SiteRecord
import com.wikikt.model.nowMillis
import com.wikikt.model.toSiteRecord
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * The sites hosted by this instance. Sites are few and read on nearly every request (host → site
 * resolution), so the whole set is cached in memory and invalidated on write. Exactly one site is the
 * catch-all (fallback for any host not matched by hostname); this service keeps that invariant.
 */
class SiteService(private val database: R2dbcDatabase) {
    @Volatile private var cache: List<SiteRecord>? = null

    // Content services used to cascade-delete a site. Wired by AppContext once they exist (they don't
    // depend on SiteService, but SiteService is constructed early for host resolution); mirrors the
    // late-bound callback wiring elsewhere in AppContext. Only [delete] needs them.
    private class CascadeDeps(
        val pages: PageService,
        val assets: AssetService,
        val fragments: FragmentService,
        val nav: NavService,
        val settings: SettingsService,
        val gitSync: GitSyncService,
    )
    private var cascade: CascadeDeps? = null

    fun wireCascade(
        pages: PageService,
        assets: AssetService,
        fragments: FragmentService,
        nav: NavService,
        settings: SettingsService,
        gitSync: GitSyncService,
    ) {
        cascade = CascadeDeps(pages, assets, fragments, nav, settings, gitSync)
    }

    suspend fun all(): List<SiteRecord> {
        cache?.let { return it }
        val loaded = suspendTransaction(database) {
            SitesTable.selectAll().map { it.toSiteRecord() }.toList().sortedBy { it.position }
        }
        cache = loaded
        return loaded
    }

    suspend fun byId(id: UInt): SiteRecord? = all().firstOrNull { it.id == id }

    /** The site claiming [host] (case-insensitive, port stripped), or null if none matches by hostname. */
    suspend fun byHostname(host: String): SiteRecord? {
        val h = host.substringBefore(':').trim().lowercase()
        if (h.isEmpty()) return null
        return all().firstOrNull { it.hostname?.lowercase() == h }
    }

    /** The catch-all/fallback site (the one flagged, else the first by position). */
    suspend fun catchAll(): SiteRecord? = all().let { sites -> sites.firstOrNull { it.isCatchAll } ?: sites.firstOrNull() }

    /** Resolve the site serving [host]: exact hostname match wins, else the catch-all. */
    suspend fun resolve(host: String?): SiteRecord? {
        if (!host.isNullOrBlank()) byHostname(host)?.let { return it }
        return catchAll()
    }

    suspend fun create(name: String, hostname: String?, isCatchAll: Boolean): SiteRecord {
        val record = suspendTransaction(database) {
            val isFirst = SitesTable.selectAll().map { 1 }.toList().isEmpty()
            val makeCatchAll = isCatchAll || isFirst
            if (makeCatchAll) SitesTable.update({ SitesTable.isCatchAll eq true }) { it[SitesTable.isCatchAll] = false }
            val maxPos = SitesTable.selectAll().map { it[SitesTable.position] }.toList().maxOrNull() ?: -1
            val id = SitesTable.insert {
                it[SitesTable.name] = name
                it[SitesTable.hostname] = hostname?.ifBlank { null }
                it[SitesTable.isCatchAll] = makeCatchAll
                it[SitesTable.position] = maxPos + 1
                it[SitesTable.createdAt] = nowMillis()
            }[SitesTable.id].value
            SitesTable.selectAll().where { SitesTable.id eq id }.map { it.toSiteRecord() }.singleOrNull()!!
        }
        invalidateCache()
        return record
    }

    suspend fun update(id: UInt, name: String, hostname: String?, isCatchAll: Boolean): SiteRecord? {
        val record = suspendTransaction(database) {
            SitesTable.selectAll().where { SitesTable.id eq id }.singleOrNull() ?: return@suspendTransaction null
            // Setting this site as catch-all clears the flag on every other site (keeps exactly one).
            if (isCatchAll) SitesTable.update({ SitesTable.isCatchAll eq true }) { it[SitesTable.isCatchAll] = false }
            SitesTable.update({ SitesTable.id eq id }) {
                it[SitesTable.name] = name
                it[SitesTable.hostname] = hostname?.ifBlank { null }
                if (isCatchAll) it[SitesTable.isCatchAll] = true
            }
            SitesTable.selectAll().where { SitesTable.id eq id }.map { it.toSiteRecord() }.singleOrNull()
        }
        invalidateCache()
        return record
    }

    /**
     * Deletes [id] and cascades away everything it owns — pages (with their revisions, ACLs, aliases,
     * tags, search/render rows), assets (including on-disk bytes and revisions), fragments, nav menus,
     * and settings. Refuses only to delete the catch-all (the instance must always keep a fallback).
     *
     * Cross-site references don't exist: a link from one site to a page on another is treated like an
     * external link and isn't tracked, so nothing outside the site can be left dangling. The per-row
     * deletes are reused so this stays in step with single-page / single-asset deletion (file cleanup,
     * search/render invalidation) rather than re-implementing the child-table sweep. The site's git-sync
     * clone (whose config holds the push credential) is removed too, so no secret lingers on disk.
     */
    suspend fun delete(id: UInt): SiteDeleteResult {
        val site = byId(id) ?: return SiteDeleteResult.NOT_FOUND
        if (site.isCatchAll) return SiteDeleteResult.IS_CATCHALL
        val c = cascade ?: error("SiteService.wireCascade() was never called")
        for (page in c.pages.list(id)) c.pages.delete(page.id)
        for (asset in c.assets.list(id)) c.assets.delete(asset.id)
        for (fragment in c.fragments.list(id)) c.fragments.delete(fragment.id)
        for (menu in c.nav.listMenus(id)) c.nav.deleteMenu(menu.id)
        suspendTransaction(database) {
            AppSettingsTable.deleteWhere { AppSettingsTable.siteId eq id }
            SitesTable.deleteWhere { SitesTable.id eq id }
        }
        c.settings.invalidateCache() // this site's cached settings map is now stale
        invalidateCache()
        c.gitSync.deleteClone(id) // drop the local clone + its embedded credential now the site is gone
        return SiteDeleteResult.DELETED
    }

    fun invalidateCache() {
        cache = null
    }
}

enum class SiteDeleteResult { DELETED, NOT_FOUND, IS_CATCHALL }
