package com.wikikt.service

import com.wikikt.db.ContentFormat
import com.wikikt.db.PageRenderCacheTable
import com.wikikt.markdown.MarkdownRenderer
import com.wikikt.model.PageRecord
import com.wikikt.model.isReservedFirstSegment
import com.wikikt.model.normalizeLocale
import com.wikikt.model.normalizePagePath
import com.wikikt.routing.resolveRelativeWikiUrl
import org.jsoup.Jsoup
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteAll
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * Server-side cache of each live page's rendered body HTML, backed by [PageRenderCacheTable]. Rendering
 * (expand fragments → [MarkdownRenderer] with the current [SettingsService.renderOptions]) is the
 * expensive step; caching it lets a page view serve stored HTML instead of re-rendering every request.
 *
 * What is cached is the *impersonal* body only — per-locale image/alt resolution and the page chrome
 * (header/footer/CSRF token) are applied per request by the caller, so no per-user or secret content is
 * ever stored. A cached row is valid only while it matches the page's current `updatedAt` **and** the
 * global render epoch ([SettingsService.renderEpoch]); otherwise it is re-rendered.
 *
 * Invalidation, wired by [com.wikikt.AppContext]:
 *  - write-through on a single page's content change ([rebuild], via [PageService.onContentChanged]);
 *  - lazy fill on a cache miss/stale row ([getOrRender]) — resilient if a write-through was skipped;
 *  - a `render.*` settings change bumps the epoch (no row touched; rows go stale by comparison);
 *  - a fragment change drops the affected pages' rows ([invalidateForFragmentKeys]), refilled lazily.
 */
class PageRenderService(
    private val database: R2dbcDatabase,
    private val pages: PageService,
    private val fragments: FragmentService,
    private val markdown: MarkdownRenderer,
    private val settings: SettingsService,
    private val infobox: InfoboxService,
    private val defaultLocale: String,
) {
    /** A page's cached render: the body HTML plus its infobox card HTML (null when the page has none). */
    data class RenderedPage(val body: String, val infoboxHtml: String?)
    /**
     * Renders a page body to sanitized HTML: expand `{{fragment:key}}` (Markdown only), then render with
     * the current global [RenderOptions][com.wikikt.markdown.RenderOptions]. Stateless; no caching — used
     * directly for staged previews, revisions, and the editor live-preview, and as the fill for the cache.
     *
     * @param sourceLines stamp blocks with `data-line` for the editor's split-view scroll sync. Lines refer
     *   to the *expanded* source, so a multi-line `{{fragment:key}}` shifts everything after it; the sync
     *   interpolates between anchors, so that degrades the mapping below a fragment rather than breaking it.
     */
    suspend fun renderBody(
        siteId: UInt,
        content: String,
        format: ContentFormat,
        locale: String,
        pagePath: String,
        sourceLines: Boolean = false,
    ): String {
        val source = if (format == ContentFormat.MARKDOWN) fragments.expand(siteId, content, locale, defaultLocale) else content
        val html = markdown.render(source, format, settings.renderOptions(siteId), sourceLines)
        return markRedlinks(siteId, resolveRelativeLinks(html, locale, pagePath))
    }

    /**
     * Rewrites directory-relative link/image URLs in the rendered body to root-absolute
     * `/{locale}/{path}` resolved against [pagePath] as a directory, matching WikiJS (see
     * [resolveRelativeWikiUrl]). Runs before [markRedlinks] so the now-absolute internal links are
     * eligible for redlink tagging, and before the per-locale asset-ref pass so relative image sources
     * resolve too. Absolute, anchor, and external URLs are left untouched.
     */
    private fun resolveRelativeLinks(html: String, locale: String, pagePath: String): String {
        // Nothing to do for HTML with no relative-capable attributes, or when the page path is unknown.
        if (pagePath.isBlank() || (!html.contains("href=") && !html.contains("src="))) return html
        val doc = Jsoup.parseBodyFragment(html)
        var changed = false
        for (el in doc.select("a[href], img[src]")) {
            val isLink = el.tagName() == "a"
            val attr = if (isLink) "href" else "src"
            val resolved = resolveRelativeWikiUrl(el.attr(attr), locale, pagePath) ?: continue
            el.attr(attr, resolved)
            changed = true
        }
        return if (changed) doc.body().html() else html
    }

    /**
     * "Redlinks": tags internal page links whose target does not exist with the
     * `is-new-page` class (styled red in site.css). Computed here at render time, so the result is part
     * of the cached body. A link flips back to normal when its target page is created (or a valid link
     * goes red when its target is deleted/moved) — handled by [invalidateBacklinks], which re-renders the
     * pages that link to a path whenever a page appears/disappears there.
     *
     * TODO(scale): link resolution here scans each page's own links, and the flip relies on
     * [PageService.backlinks] scanning ALL page content on create/delete/move. A dedicated `page_links`
     * table (WikiJS-style: "which page links to which path", written on save) would turn both
     * "does this link resolve?" and "what links here?" into indexed lookups instead of scans. Worth
     * adding only if these scans become a bottleneck at scale; the scan approach is fine for now.
     */
    private suspend fun markRedlinks(siteId: UInt, html: String): String {
        // Only site-absolute hrefs ("/…") can be internal page links; skip the parse if there are none.
        if (!html.contains("href=\"/")) return html
        val doc = Jsoup.parseBodyFragment(html)
        val links = doc.select("a[href^=\"/\"]")
        if (links.isEmpty()) return html
        val exists = HashMap<Pair<String, String>, Boolean>() // dedupe repeated links to the same target
        for (a in links) {
            val target = internalPageTarget(a.attr("href")) ?: continue
            val found = exists.getOrPut(target) { pages.resolveByPath(siteId, target.first, target.second) != null }
            if (!found) a.addClass("is-new-page")
        }
        return doc.body().html()
    }

    /**
     * Resolves an href to the (locale, path) of the wiki page it targets, or null if it is not an
     * internal page link: external/relative/anchor links, reserved route prefixes (`/a`, `/e`, `/h`,
     * `/f`, `/login`, …), bare locale roots (`/en`), and asset-like paths (a dotted final segment) are
     * all skipped. Locale-less paths resolve against the default locale (matching the URL router).
     */
    private fun internalPageTarget(href: String): Pair<String, String>? {
        if (!href.startsWith("/") || href.startsWith("//")) return null
        val clean = href.substringBefore('?').substringBefore('#')
        val segs = clean.split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return null                                // "/" (home root)
        if (isReservedFirstSegment(segs[0])) return null               // /a, /e, /h, /s, /f, /login, /static, …
        val localePrefixed = normalizeLocale(segs[0]) != null
        if (localePrefixed && segs.size == 1) return null              // "/en" is a locale home, not a page
        val locale = if (localePrefixed) segs[0] else defaultLocale
        val pathSegs = if (localePrefixed) segs.drop(1) else segs
        if (pathSegs.last().contains('.')) return null                 // an asset/file, not a page
        return locale to normalizePagePath(pathSegs.joinToString("/"))
    }

    /**
     * Renders a page's infobox card to HTML with redlink tagging applied (so links in field values
     * flip red↔blue like body links), or null when the page has no infobox for its path. Uncached —
     * used for staged/preview views and as the fill for the cached row.
     */
    suspend fun infoboxFor(siteId: UInt, path: String, tags: List<String>, infoboxJson: String?): String? {
        val card = infobox.renderCard(siteId, path, tags, infoboxJson) ?: return null
        return markRedlinks(siteId, card)
    }

    /**
     * The cached body + infobox for a live [page], or — on a miss or a row that no longer matches the
     * page's `updatedAt` / the current render epoch — a fresh render that is stored and returned. Body
     * and infobox are cached in the same row, so they build and invalidate together.
     */
    suspend fun getOrRender(page: PageRecord): RenderedPage {
        val epoch = settings.renderEpoch(page.siteId)
        cachedRow(page.id)?.let { row ->
            if (row.renderEpoch == epoch && row.sourceUpdatedAt == page.updatedAt) return RenderedPage(row.html, row.infoboxHtml)
        }
        val rendered = RenderedPage(
            renderBody(page.siteId, page.content, page.contentFormat, page.locale, page.path),
            infoboxFor(page.siteId, page.path, page.tags, page.infobox),
        )
        upsert(page.id, rendered.body, rendered.infoboxHtml, epoch, page.updatedAt)
        return rendered
    }

    /** Write-through rebuild after a single page's live content changes (warms the row for the next view). */
    suspend fun rebuild(pageId: UInt) {
        val page = pages.findById(pageId) ?: return
        upsert(
            pageId,
            renderBody(page.siteId, page.content, page.contentFormat, page.locale, page.path),
            infoboxFor(page.siteId, page.path, page.tags, page.infobox),
            settings.renderEpoch(page.siteId),
            page.updatedAt,
        )
    }

    /**
     * Eagerly re-renders and stores every page under the current epoch, returning the count. Backs the
     * admin "Re-render all pages" action — pays the render cost up front instead of spreading it across
     * the next visitors. A single page failing to render is skipped (logged by the caller's success
     * count), never aborting the rest.
     */
    suspend fun rebuildAll(siteId: UInt): Int {
        val epoch = settings.renderEpoch(siteId)
        var count = 0
        pages.list(siteId).forEach { page ->
            runCatching {
                upsert(
                    page.id,
                    renderBody(page.siteId, page.content, page.contentFormat, page.locale, page.path),
                    infoboxFor(page.siteId, page.path, page.tags, page.infobox),
                    epoch,
                    page.updatedAt,
                )
            }.onSuccess { count++ }
        }
        return count
    }

    /** Drops the cached row for one page (e.g. on delete); it refills lazily if the page still exists. */
    suspend fun invalidate(pageId: UInt) {
        suspendTransaction(database) { PageRenderCacheTable.deleteWhere { PageRenderCacheTable.pageId eq pageId } }
    }

    /**
     * Re-renders (by dropping the cached rows of) the pages that link to ([locale], [path]), so their
     * internal links flip between normal and `is-new-page` styling when a page appears/disappears there.
     * Wired to [PageService.onPageExistenceChanged] (create/delete/move). Cheap because those events are
     * infrequent; see the TODO in [markRedlinks] about a `page_links` table if that ever changes.
     */
    suspend fun invalidateBacklinks(siteId: UInt, locale: String, path: String) {
        pages.backlinks(siteId, locale, path, defaultLocale).forEach { invalidate(it.id) }
    }

    /** Drops every cached row (admin "re-render all", or after a bulk import/restore). Rows refill lazily. */
    suspend fun invalidateAll() {
        suspendTransaction(database) { PageRenderCacheTable.deleteAll() }
    }

    /**
     * Drops cached rows for the pages whose body depends on a changed fragment. [changedKeys] are the
     * fragment keys touched (on rename, old + new). Mirrors [SearchIndexService.reindexForFragmentKeys]:
     * the affected set is closed over the fragment graph, then any page referencing an affected key is
     * dropped. Match is by key string, so it over-includes across locales rather than ever missing a page.
     */
    suspend fun invalidateForFragmentKeys(siteId: UInt, changedKeys: Set<String>) {
        if (changedKeys.isEmpty()) return
        val allFragments = fragments.list(siteId)
        val affected = changedKeys.toMutableSet()
        var grew = true
        while (grew) {
            grew = false
            for (f in allFragments) {
                if (f.key in affected) continue
                if (fragments.referencedKeys(f.content).any { it in affected }) {
                    affected.add(f.key)
                    grew = true
                }
            }
        }
        pages.list(siteId).forEach { page ->
            if (fragments.referencedKeys(page.content).any { it in affected }) invalidate(page.id)
        }
    }

    private data class CacheRow(val html: String, val infoboxHtml: String?, val renderEpoch: Long, val sourceUpdatedAt: Long)

    private suspend fun cachedRow(pageId: UInt): CacheRow? = suspendTransaction(database) {
        PageRenderCacheTable.selectAll()
            .where { PageRenderCacheTable.pageId eq pageId }
            .map { CacheRow(it[PageRenderCacheTable.html], it[PageRenderCacheTable.infoboxHtml], it[PageRenderCacheTable.renderEpoch], it[PageRenderCacheTable.sourceUpdatedAt]) }
            .singleOrNull()
    }

    private suspend fun upsert(pageId: UInt, html: String, infoboxHtml: String?, epoch: Long, sourceUpdatedAt: Long) {
        suspendTransaction(database) {
            val updated = PageRenderCacheTable.update({ PageRenderCacheTable.pageId eq pageId }) {
                it[PageRenderCacheTable.html] = html
                it[PageRenderCacheTable.infoboxHtml] = infoboxHtml
                it[renderEpoch] = epoch
                it[PageRenderCacheTable.sourceUpdatedAt] = sourceUpdatedAt
            }
            if (updated == 0) {
                PageRenderCacheTable.insert {
                    it[PageRenderCacheTable.pageId] = pageId
                    it[PageRenderCacheTable.html] = html
                    it[PageRenderCacheTable.infoboxHtml] = infoboxHtml
                    it[renderEpoch] = epoch
                    it[PageRenderCacheTable.sourceUpdatedAt] = sourceUpdatedAt
                }
            }
        }
    }
}
