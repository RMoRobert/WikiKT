package com.wikikt.service

import com.wikikt.db.ContentFormat
import com.wikikt.db.PageEditAclTable
import com.wikikt.db.PageRenderCacheTable
import com.wikikt.db.PageRevisionsTable
import com.wikikt.db.PageSearchIndexTable
import com.wikikt.db.PageStagedTable
import com.wikikt.db.PageTagsTable
import com.wikikt.db.PageViewAclTable
import com.wikikt.db.PagesTable
import com.wikikt.model.CreatePageRequest
import com.wikikt.model.PageAcl
import com.wikikt.model.PageRecord
import com.wikikt.model.PageRevisionRecord
import com.wikikt.model.PageStagedRecord
import com.wikikt.model.UpdatePageRequest
import com.wikikt.model.normalizePagePath
import com.wikikt.model.normalizeTags
import com.wikikt.model.nowMillis
import com.wikikt.model.parseId
import com.wikikt.model.toDto
import com.wikikt.model.toModel
import com.wikikt.model.toPageRecord
import com.wikikt.model.toPageRevisionRecord
import com.wikikt.model.toSearchPageRecord
import com.wikikt.model.toPageStagedRecord
import org.jetbrains.exposed.v1.core.inList
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.LikePattern
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

// Characters that can continue a URL path. A target match flanked by one of these on either side is
// part of a longer path (e.g. /en/foo inside /en/foobar or /fr/en/foo), not a link to the target.
private val PATH_CONTINUATION: Set<Char> =
    (('a'..'z') + ('A'..'Z') + ('0'..'9')).toSet() + setOf('-', '_', '/', '.', '~')

/**
 * A sortable column of the admin page list (`/a/pages`). [key] is what travels in the `?sort=` query
 * parameter, so it is part of the admin URL contract — keep the values stable.
 */
enum class PageSortColumn(val key: String) {
    TITLE("title"),
    LOCALE("locale"),
    PATH("path"),
    TAGS("tags"),
    UPDATED("updated"),
    ;

    companion object {
        /** The column [key] names, or null when it names nothing (unknown/absent → caller's default). */
        fun fromKey(key: String?): PageSortColumn? = entries.firstOrNull { it.key == key }
    }
}

/** One window of [PageService.listPaged]: the rows asked for, plus the unpaged total behind them. */
data class PagedPages(val pages: List<PageRecord>, val total: Long)

private fun contentLinksTo(content: String, target: String): Boolean {
    var i = content.indexOf(target)
    while (i >= 0) {
        val prev = if (i == 0) null else content[i - 1]
        val next = content.getOrNull(i + target.length)
        if ((prev == null || prev !in PATH_CONTINUATION) && (next == null || next !in PATH_CONTINUATION)) return true
        i = content.indexOf(target, i + 1)
    }
    return false
}

class PageService(private val database: R2dbcDatabase) {
    /**
     * Invoked with a page id after its LIVE content changes (create/update/move/promote/restore),
     * so the search index can be rebuilt. Late-bound by [com.wikikt.AppContext] to
     * SearchIndexService to avoid a PageService↔FragmentService dependency cycle. Failures here must
     * not fail the write, so the caller wraps it defensively.
     */
    var onContentChanged: (suspend (UInt) -> Unit)? = null

    private suspend fun notifyContentChanged(pageId: UInt) {
        // Bound history first (its own transaction, post-write), then fan out reindex/rerender.
        runCatching { prunePageRevisions(pageId) }
        val cb = onContentChanged ?: return
        runCatching { cb(pageId) }
    }

    /**
     * Returns the per-site cap on retained page revisions. Late-bound by [com.wikikt.AppContext] to the
     * settings service (so PageService needn't depend on it); null in tests that don't wire it, in which
     * case history is left unbounded. Called post-transaction, so reading settings can't nest a txn.
     */
    var pageRevisionLimit: (suspend (UInt) -> Int)? = null

    /** Trims [pageId]'s oldest revisions down to the per-site limit. No-op if the limit isn't wired. */
    suspend fun prunePageRevisions(pageId: UInt) {
        val provider = pageRevisionLimit ?: return
        val siteId = findById(pageId)?.siteId ?: return
        val limit = provider(siteId).coerceAtLeast(1)
        suspendTransaction(database) {
            val ids = PageRevisionsTable.selectAll().where { PageRevisionsTable.pageId eq pageId }
                .map { it[PageRevisionsTable.id].value to it[PageRevisionsTable.revisionNumber] }
                .toList().sortedByDescending { it.second }
            ids.drop(limit).forEach { (id, _) -> PageRevisionsTable.deleteWhere { PageRevisionsTable.id eq id } }
        }
    }

    /**
     * One-time purge: deletes page revisions on [siteId] created before [cutoffMillis] (use
     * [Long.MAX_VALUE] to clear all history). Returns how many revision rows were removed. Live page
     * content is untouched — only history is affected.
     */
    suspend fun purgeRevisionsOlderThan(siteId: UInt, cutoffMillis: Long): Int = suspendTransaction(database) {
        val pageIds = PagesTable.selectAll().where { PagesTable.siteId eq siteId }
            .map { it[PagesTable.id].value }.toList()
        var removed = 0
        for (pid in pageIds) {
            removed += PageRevisionsTable.deleteWhere {
                (PageRevisionsTable.pageId eq pid) and (PageRevisionsTable.createdAt less cutoffMillis)
            }
        }
        removed
    }

    /**
     * Invoked with a (locale, path) when a page starts or stops existing there — create, delete, or
     * move (fired for both the old and new path). Late-bound by [com.wikikt.AppContext] to the render
     * cache so it can re-render the pages that LINK to that path, flipping internal links red↔blue
     * (see [PageRenderService.invalidateBacklinks]). Wrapped so a failure never fails the write.
     */
    var onPageExistenceChanged: (suspend (UInt, String, String) -> Unit)? = null

    private suspend fun notifyPageExistenceChanged(siteId: UInt, locale: String, path: String) {
        val cb = onPageExistenceChanged ?: return
        runCatching { cb(siteId, locale, path) }
    }

    suspend fun list(siteId: UInt): List<PageRecord> = suspendTransaction(database) {
        val pages = PagesTable.selectAll().where { PagesTable.siteId eq siteId }.map { it.toPageRecord() }.toList()
        // Load tags for just this site's pages, not the whole (all-sites) PageTagsTable.
        val tagsByPage = loadTagsForPages(pages.map { it.id })
        pages.map { it.copy(tags = tagsByPage[it.id].orEmpty().sorted()) }
    }

    /**
     * One sorted, paginated window of a site's pages — what the admin page list (`/a/pages`) renders,
     * so only that window is fetched however large the wiki is. Ordering and the [limit]/[offset] both
     * happen in SQL; [PagedPages.total] is the unpaged row count, for the pager.
     *
     * Selects [SEARCH_PAGE_COLUMNS] — no page bodies, which is the point of paging this at all — so
     * the returned records have an empty `content` (see [toSearchPageRecord]). Tags come from one
     * batched side query over just this window.
     *
     * Text columns sort case-insensitively (`LOWER()`), matching how the list reads on screen rather
     * than the database's collation. Every sort ends with the page id so the order is total and the
     * windows stay stable across pages.
     */
    suspend fun listPaged(
        siteId: UInt,
        sort: PageSortColumn = PageSortColumn.TITLE,
        descending: Boolean = false,
        offset: Long = 0,
        limit: Int = 25,
    ): PagedPages = suspendTransaction(database) {
        val total = PagesTable.select(PagesTable.id).where { PagesTable.siteId eq siteId }.count()
        val order = if (descending) SortOrder.DESC else SortOrder.ASC
        val pages = if (sort == PageSortColumn.TAGS) {
            listPageIdsByTag(siteId, descending, offset, limit).let { ids ->
                val byId = PagesTable.select(SEARCH_PAGE_COLUMNS)
                    .where { PagesTable.id inList ids }
                    .map { it.toSearchPageRecord() }
                    .toList()
                    .associateBy { it.id }
                ids.mapNotNull(byId::get)
            }
        } else {
            val ordering = when (sort) {
                PageSortColumn.TITLE -> listOf(PagesTable.title.lowerCase() to order)
                PageSortColumn.LOCALE -> listOf(PagesTable.locale to order, PagesTable.title.lowerCase() to SortOrder.ASC)
                PageSortColumn.PATH -> listOf(PagesTable.path.lowerCase() to order)
                PageSortColumn.UPDATED -> listOf(PagesTable.updatedAt to order)
                PageSortColumn.TAGS -> error("handled above")
            } + (PagesTable.id to SortOrder.ASC)
            PagesTable.select(SEARCH_PAGE_COLUMNS)
                .where { PagesTable.siteId eq siteId }
                .orderBy(*ordering.toTypedArray())
                .limit(limit).offset(offset)
                .map { it.toSearchPageRecord() }
                .toList()
        }
        val tagsByPage = loadTagsForPages(pages.map { it.id })
        PagedPages(pages.map { it.copy(tags = tagsByPage[it.id].orEmpty().sorted()) }, total)
    }

    /**
     * Ids of one window of pages ordered by their tags. Tags live in a side table, so the window can't
     * be picked by a plain ORDER BY on [PagesTable]: this groups the (left-joined) tag rows per page
     * and orders on each page's alphabetically first tag — which is what the comma-joined tag cell
     * leads with. Untagged pages have no tag rows, so they sort last in both directions.
     *
     * Only ids are selected, because grouping and selecting the full row isn't portable across H2 and
     * Postgres; the caller re-reads the rows by id and restores this order. Call within a transaction.
     */
    private suspend fun listPageIdsByTag(siteId: UInt, descending: Boolean, offset: Long, limit: Int): List<UInt> {
        val firstTag = PageTagsTable.tag.min()
        val tagOrder = if (descending) SortOrder.DESC_NULLS_LAST else SortOrder.ASC_NULLS_LAST
        return (PagesTable leftJoin PageTagsTable)
            .select(PagesTable.id, firstTag)
            .where { PagesTable.siteId eq siteId }
            .groupBy(PagesTable.id)
            .orderBy(firstTag to tagOrder, PagesTable.id to SortOrder.ASC)
            .limit(limit).offset(offset)
            .map { it[PagesTable.id].value }
            .toList()
    }

    /**
     * Lightweight page list for the editor's link-path autocomplete: only the columns needed to render
     * and permission-check a link target ([SEARCH_PAGE_COLUMNS], so NO page body), plus tags so
     * tag-scoped view rules still apply when the caller filters with `filterViewable`. Site-scoped
     * throughout — including the tag load. Backs `GET /u/v1/pages/paths`, which loads every page, so
     * keeping the body out of this query matters on large wikis.
     */
    suspend fun listLinkTargets(siteId: UInt): List<PageRecord> = suspendTransaction(database) {
        val pages = PagesTable.select(SEARCH_PAGE_COLUMNS)
            .where { PagesTable.siteId eq siteId }
            .map { it.toSearchPageRecord() }
            .toList()
        val tagsByPage = loadTagsForPages(pages.map { it.id })
        pages.map { it.copy(tags = tagsByPage[it.id].orEmpty().sorted()) }
    }

    /** Page count per stored locale — used by admin settings to warn before disabling a used locale. */
    suspend fun countsByLocale(siteId: UInt): Map<String, Int> = suspendTransaction(database) {
        PagesTable.selectAll().where { PagesTable.siteId eq siteId }
            .map { it[PagesTable.locale] }.toList().groupingBy { it }.eachCount()
    }

    /**
     * Pages whose content links to the page at ([targetLocale], [targetPath]) — i.e. references its
     * canonical `/{locale}/{path}` URL (the form every internal link uses). Computed on the fly by
     * scanning content; intended for the rare "what links here" / pre-move check, so it's not indexed.
     */
    suspend fun backlinks(siteId: UInt, targetLocale: String, targetPath: String, defaultLocale: String): List<PageRecord> = suspendTransaction(database) {
        // Internal links use the canonical "/{locale}/{path}". Links in the default locale are also often
        // written locale-less ("/{path}", which 301-redirects to the default), so match that form too.
        val targets = buildList {
            add("/$targetLocale/$targetPath")
            if (targetLocale == defaultLocale) add("/$targetPath")
        }
        PagesTable.selectAll().where { PagesTable.siteId eq siteId }.map { it.toPageRecord() }.toList()
            .filter { p ->
                !(p.locale == targetLocale && p.path == targetPath) && targets.any { contentLinksTo(p.content, it) }
            }
            .sortedBy { it.title.lowercase() }
    }

    /**
     * Published pages carrying [tag] (matched case-insensitively against the stored, already-lowercased
     * tags), optionally scoped to [locale], ordered by title and with their full tag list loaded.
     * Permission filtering happens in the caller (mirrors [search]).
     */
    suspend fun pagesByTag(siteId: UInt, tag: String, locale: String? = null): List<PageRecord> = suspendTransaction(database) {
        val normalized = tag.trim().lowercase()
        if (normalized.isEmpty()) return@suspendTransaction emptyList()
        (PagesTable innerJoin PageTagsTable).selectAll()
            .where {
                val match = (PageTagsTable.tag eq normalized) and (PagesTable.published eq true) and (PagesTable.siteId eq siteId)
                if (locale != null) match and (PagesTable.locale eq locale) else match
            }
            .orderBy(PagesTable.title)
            .map { it.toPageRecord() }
            .toList()
            .map { it.copy(tags = loadTags(it.id)) }
    }

    /** Every distinct tag in use on this site, sorted — powers the tag-input autocomplete in the editor. */
    suspend fun allTags(siteId: UInt): List<String> = suspendTransaction(database) {
        (PageTagsTable innerJoin PagesTable).selectAll().where { PagesTable.siteId eq siteId }
            .map { it[PageTagsTable.tag] }.toList().distinct().sorted()
    }

    /**
     * Case-insensitive substring search over PUBLISHED pages, optionally scoped to [locale]. Free
     * text is matched against the single [PageSearchIndexTable] column (left-joined), which holds the
     * page's title + description + path + fragment-expanded body assembled by [SearchIndexService] —
     * so a match inside a transcluded `{{fragment:key}}`, or in the title/path, all resolve against
     * one column. On Postgres a `pg_trgm` GIN index (migration V2) makes this `LOWER() LIKE '%q%'`
     * index-backed; H2 keeps the portable single-column scan. Fetches up to [SEARCH_FETCH_CAP] rows,
     * ranks in-memory (title > path > description > body) and trims to [limit]. Each hit carries the
     * text used for snippeting. Permission filtering happens in the caller.
     *
     * Because the search reads only the index column, a page is findable once its index row exists;
     * rows are maintained on every content/metadata change and backfilled at startup by
     * `SearchIndexService.reindexMissing()`, so this holds in normal operation.
     *
     * `#tag` tokens in [query] are extracted and applied as an AND filter over the candidates' tags;
     * the remaining words are the free-text search. A query of ONLY tags (no text) returns pages
     * carrying every tag, ordered by title.
     *
     * TODO (deferred): for better relevance/stemming, a `tsvector` column with `ts_rank` ranking
     * (plus optional per-locale dictionaries / pgroonga for CJK) on Postgres — the trigram index here
     * already covers substring-match latency. H2 keeps the `LOWER() LIKE` path as the simple default.
     */
    suspend fun search(siteId: UInt, query: String, locale: String?, limit: Int = 50): List<PageSearchHit> {
        val parsed = parseSearchQuery(query)
        val hasText = parsed.text.length >= MIN_SEARCH_LENGTH
        if (!hasText && parsed.tags.isEmpty()) return emptyList()
        // Escape LIKE metacharacters so a query of "50%" or "a_b" matches literally.
        val pattern = if (hasText) {
            val escaped = parsed.text.lowercase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
            LikePattern("%$escaped%", '\\')
        } else {
            null
        }
        return suspendTransaction(database) {
            // Project only the columns search needs — notably NOT the page body (content) or the other
            // heavy text fields — so a broad match doesn't drag every matched page's full body over the
            // wire. The snippet comes from the index text below, which we already fetch.
            val rows = (PagesTable leftJoin PageSearchIndexTable)
                .select(SEARCH_PAGE_COLUMNS + PageSearchIndexTable.text)
                .where {
                    val published = (PagesTable.siteId eq siteId) and (PagesTable.published eq true)
                    var cond = if (locale != null) (PagesTable.locale eq locale) and published else published
                    if (pattern != null) {
                        // Free text matches the one search-index column, which holds the page's
                        // title, description, path and fragment-expanded body (see SearchIndexService).
                        // Matching a single column — rather than OR-ing LIKEs across several columns in
                        // two tables — lets Postgres use the trigram index (migration V2) instead of a
                        // sequential scan; on H2 it stays a plain (single-column) scan. Ranking below
                        // still uses the individual title/path/description fields.
                        cond = cond and (PageSearchIndexTable.text.lowerCase() like pattern)
                    }
                    cond
                }
                .orderBy(PagesTable.title)
                .limit(SEARCH_FETCH_CAP)
                .map { it.toSearchPageRecord() to it.getOrNull(PageSearchIndexTable.text) }
                .toList()

            // One batched tag load for the candidate set — powers the #tag filter and each hit's tags.
            val tagsByPage = loadTagsForPages(rows.map { it.first.id })
            val filtered = if (parsed.tags.isEmpty()) rows
            else rows.filter { (p, _) -> parsed.tags.all { it in tagsByPage[p.id].orEmpty() } }

            val needle = parsed.text.lowercase()
            val ranked = if (hasText) {
                filtered.sortedBy { (p, _) ->
                    when {
                        p.title.lowercase().contains(needle) -> 0
                        p.path.lowercase().contains(needle) -> 1
                        (p.description ?: "").lowercase().contains(needle) -> 2
                        else -> 3
                    }
                }
            } else {
                filtered
            }
            ranked.take(limit).map { (p, indexText) ->
                // searchText (for snippeting) is the index document; content isn't selected here.
                PageSearchHit(page = p.copy(tags = tagsByPage[p.id].orEmpty().sorted()), searchText = indexText.orEmpty())
            }
        }
    }

    /** Tags for each of [ids] in one query (empty when [ids] is empty). Call within a transaction. */
    private suspend fun loadTagsForPages(ids: List<UInt>): Map<UInt, Set<String>> {
        if (ids.isEmpty()) return emptyMap()
        return PageTagsTable.selectAll()
            .where { PageTagsTable.pageId inList ids }
            .toList()
            .groupBy({ it[PageTagsTable.pageId].value }, { it[PageTagsTable.tag] })
            .mapValues { it.value.toSet() }
    }

    /**
     * Rebuilds a page's [PageSearchIndexTable] row to [document] — the page's full searchable text
     * (title + description + path + fragment-expanded body, assembled by [SearchIndexService]).
     * Holding every searched field in this one column lets [search] match a single, trigram-indexable
     * (on Postgres) column instead of scanning several. Call after the page's content or those
     * metadata fields change; the [document] is built by the caller so this service stays decoupled
     * from FragmentService.
     */
    suspend fun reindexSearchText(pageId: UInt, document: String) {
        suspendTransaction(database) {
            val updated = PageSearchIndexTable.update({ PageSearchIndexTable.pageId eq pageId }) {
                it[text] = document
            }
            if (updated == 0) {
                PageSearchIndexTable.insert {
                    it[PageSearchIndexTable.pageId] = pageId
                    it[text] = document
                }
            }
        }
    }

    /** Page ids that currently have no search-index row (indexes freshly seeded/restored pages). */
    suspend fun pageIdsMissingSearchIndex(): List<UInt> = suspendTransaction(database) {
        val indexed = PageSearchIndexTable.selectAll().map { it[PageSearchIndexTable.pageId].value }.toList().toSet()
        PagesTable.selectAll().map { it[PagesTable.id].value }.toList().filter { it !in indexed }
    }

    suspend fun findById(id: UInt): PageRecord? = suspendTransaction(database) {
        PagesTable.selectAll()
            .where { PagesTable.id eq id }
            .map { it.toPageRecord() }
            .singleOrNull()
            ?.copy(tags = loadTags(id))
    }

    suspend fun findByLocaleAndPath(siteId: UInt, locale: String, path: String): PageRecord? = suspendTransaction(database) {
        val normalizedPath = normalizePagePath(path)
        PagesTable.selectAll()
            .where { (PagesTable.siteId eq siteId) and (PagesTable.locale eq locale) and (PagesTable.path eq normalizedPath) }
            .map { it.toPageRecord() }
            .singleOrNull()
            ?.let { it.copy(tags = loadTags(it.id)) }
    }

    /** Resolves the page at [path], or null. */
    suspend fun resolveByPath(siteId: UInt, locale: String, path: String): PageRecord? =
        findByLocaleAndPath(siteId, locale, path)

    suspend fun viewAcl(pageId: UInt): PageAcl = loadAcl(pageId, view = true)

    suspend fun editAcl(pageId: UInt): PageAcl = loadAcl(pageId, view = false)

    suspend fun create(siteId: UInt, request: CreatePageRequest, updatedBy: UInt?): PageRecord {
        val page = suspendTransaction(database) {
            val normalizedPath = normalizePagePath(request.path)
            val format = ContentFormat.valueOf(request.contentFormat.uppercase())
            val now = nowMillis()

            val id = PagesTable.insert {
                it[PagesTable.siteId] = siteId
                it[locale] = request.locale
                it[path] = normalizedPath
                it[title] = request.title
                it[description] = request.description?.ifBlank { null }
                it[metaRobots] = request.metaRobots?.ifBlank { null }
                it[content] = request.content
                it[contentFormat] = format.name
                it[published] = request.published
                it[publishAt] = request.publishAt
                it[infobox] = request.infobox?.ifBlank { null }
                it[customCss] = request.customCss?.ifBlank { null }
                it[customJs] = request.customJs?.ifBlank { null }
                it[createdAt] = now
                it[updatedAt] = now
                it[PagesTable.updatedBy] = updatedBy
            }[PagesTable.id].value

            replaceAcl(id, request.viewAcl.toModel(), view = true)
            replaceAcl(id, request.editAcl.toModel(), view = false)
            val tags = normalizeTags(request.tags)
            replaceTags(id, tags)

            PagesTable.selectAll().where { PagesTable.id eq id }.map { it.toPageRecord() }.singleOrNull()!!.copy(tags = tags)
        }
        notifyContentChanged(page.id)
        // A new page now exists here → re-render pages that link to it (their red links may turn blue).
        notifyPageExistenceChanged(page.siteId, page.locale, page.path)
        return page
    }

    suspend fun update(id: UInt, request: UpdatePageRequest, updatedBy: UInt?): PageRecord? {
        val updated = suspendTransaction(database) {
            val existing = PagesTable.selectAll()
                .where { PagesTable.id eq id }
                .map { it.toPageRecord() }
                .singleOrNull() ?: return@suspendTransaction null

            // Snapshot the current (pre-update) content as a revision before overwriting it.
            snapshotCurrent(existing, updatedBy)

            PagesTable.update({ PagesTable.id eq id }) {
                request.title?.let { value -> it[title] = value }
                request.description?.let { value -> it[description] = value.ifBlank { null } }
                request.metaRobots?.let { value -> it[metaRobots] = value.ifBlank { null } }
                request.content?.let { value -> it[content] = value }
                request.contentFormat?.let { value -> it[contentFormat] = ContentFormat.valueOf(value.uppercase()).name }
                // Null = leave unchanged; a value (blank → null) replaces the stored infobox JSON.
                request.infobox?.let { value -> it[infobox] = value.ifBlank { null } }
                // Per-page custom code: null = leave unchanged; a value (blank → null) replaces it.
                request.customCss?.let { value -> it[customCss] = value.ifBlank { null } }
                request.customJs?.let { value -> it[customJs] = value.ifBlank { null } }
                // published and publishAt move together: setting the published state also sets the
                // schedule (the editor sends a definite value — a timestamp for a scheduled draft, or
                // null to clear once published or unscheduled).
                request.published?.let { value ->
                    it[published] = value
                    it[publishAt] = request.publishAt
                }
                it[updatedAt] = nowMillis()
                it[PagesTable.updatedBy] = updatedBy
            }

            request.viewAcl?.let { replaceAcl(id, it.toModel(), view = true) }
            request.editAcl?.let { replaceAcl(id, it.toModel(), view = false) }
            request.tags?.let { replaceTags(id, normalizeTags(it)) }
            // A staged version only exists for a live page; if this update unpublishes it, drop the staged row.
            if (request.published == false) PageStagedTable.deleteWhere { PageStagedTable.pageId eq id }

            PagesTable.selectAll().where { PagesTable.id eq id }.map { it.toPageRecord() }.singleOrNull()
                ?.copy(tags = loadTags(id))
        }
        if (updated != null) notifyContentChanged(id)
        return updated
    }

    /** Snapshots a page's current content into history (call within a transaction). */
    private suspend fun snapshotCurrent(existing: PageRecord, by: UInt?) {
        // Number from the current max, not the row count: pruning removes old rows, so a count-based
        // number would collide with a surviving revision. Mirrors AssetService's versionNumber.
        val nextNumber = (
            PageRevisionsTable.selectAll()
                .where { PageRevisionsTable.pageId eq existing.id }
                .map { it[PageRevisionsTable.revisionNumber] }.toList().maxOrNull() ?: 0
            ) + 1
        PageRevisionsTable.insert {
            it[PageRevisionsTable.pageId] = existing.id
            it[title] = existing.title
            it[description] = existing.description
            it[content] = existing.content
            it[contentFormat] = existing.contentFormat.name
            it[infobox] = existing.infobox
            it[revisionNumber] = nextNumber
            it[createdAt] = nowMillis()
            it[createdBy] = by
        }
    }

    /**
     * Writes a complete content version to the live page after snapshotting the current one to history.
     * Uses direct field assignment (so a cleared description actually clears). Call within a transaction.
     */
    private suspend fun applyContentToLive(
        pageId: UInt,
        title: String,
        description: String?,
        content: String,
        contentFormat: ContentFormat,
        infobox: String?,
        by: UInt?,
    ) {
        val existing = PagesTable.selectAll().where { PagesTable.id eq pageId }
            .map { it.toPageRecord() }.singleOrNull() ?: return
        snapshotCurrent(existing, by)
        PagesTable.update({ PagesTable.id eq pageId }) {
            it[PagesTable.title] = title
            it[PagesTable.description] = description
            it[PagesTable.content] = content
            it[PagesTable.contentFormat] = contentFormat.name
            it[PagesTable.infobox] = infobox
            it[updatedAt] = nowMillis()
            it[PagesTable.updatedBy] = by
        }
    }

    // --- Staged (future) versions ---

    suspend fun stagedFor(pageId: UInt): PageStagedRecord? = suspendTransaction(database) {
        PageStagedTable.selectAll().where { PageStagedTable.pageId eq pageId }
            .map { it.toPageStagedRecord() }.singleOrNull()
    }

    /**
     * Every staged (future) version belonging to [pageIds]. Used by the asset usage scan, which must
     * see references that exist only in not-yet-published content. The table holds at most one row per
     * page, so it's read whole and filtered in memory rather than building an id-list query.
     */
    suspend fun listStaged(pageIds: Collection<UInt>): List<PageStagedRecord> {
        if (pageIds.isEmpty()) return emptyList()
        val wanted = pageIds.toSet()
        return suspendTransaction(database) {
            PageStagedTable.selectAll().map { it.toPageStagedRecord() }.toList()
                .filter { it.pageId in wanted }
        }
    }

    suspend fun upsertStaged(
        pageId: UInt,
        title: String,
        description: String?,
        content: String,
        contentFormat: ContentFormat,
        publishAt: Long?,
        by: UInt?,
        infobox: String? = null,
    ): PageStagedRecord = suspendTransaction(database) {
        PageStagedTable.deleteWhere { PageStagedTable.pageId eq pageId }
        val id = PageStagedTable.insert {
            it[PageStagedTable.pageId] = pageId
            it[PageStagedTable.title] = title
            it[PageStagedTable.description] = description
            it[PageStagedTable.content] = content
            it[PageStagedTable.contentFormat] = contentFormat.name
            it[PageStagedTable.infobox] = infobox
            it[PageStagedTable.publishAt] = publishAt
            it[updatedAt] = nowMillis()
            it[PageStagedTable.updatedBy] = by
        }[PageStagedTable.id].value
        PageStagedTable.selectAll().where { PageStagedTable.id eq id }.map { it.toPageStagedRecord() }.singleOrNull()!!
    }

    suspend fun discardStaged(pageId: UInt): Boolean = suspendTransaction(database) {
        PageStagedTable.deleteWhere { PageStagedTable.pageId eq pageId } > 0
    }

    /** Promotes a page's staged version to live now (snapshotting the current content to history). */
    suspend fun promoteStaged(pageId: UInt, by: UInt?): Boolean {
        val ok = suspendTransaction(database) {
            val staged = PageStagedTable.selectAll().where { PageStagedTable.pageId eq pageId }
                .map { it.toPageStagedRecord() }.singleOrNull() ?: return@suspendTransaction false
            applyContentToLive(pageId, staged.title, staged.description, staged.content, staged.contentFormat, staged.infobox, by)
            PageStagedTable.deleteWhere { PageStagedTable.pageId eq pageId }
            true
        }
        if (ok) notifyContentChanged(pageId)
        return ok
    }

    /** Promotes staged versions whose scheduled time has arrived; returns how many were promoted. */
    suspend fun promoteScheduledStaged(now: Long): Int {
        val due = suspendTransaction(database) {
            PageStagedTable.selectAll()
                .where { PageStagedTable.publishAt.isNotNull() and (PageStagedTable.publishAt lessEq now) }
                .map { it[PageStagedTable.pageId].value }
                .toList()
        }
        var promoted = 0
        for (pageId in due) {
            val ok = runCatching { promoteStagedIfDue(pageId, now) }.getOrDefault(false)
            if (ok) {
                promoted++
                notifyContentChanged(pageId)
            }
        }
        return promoted
    }

    /** Per-row transaction that re-checks the staged row is still due before promoting (discard/reschedule race). */
    private suspend fun promoteStagedIfDue(pageId: UInt, now: Long): Boolean = suspendTransaction(database) {
        val staged = PageStagedTable.selectAll().where { PageStagedTable.pageId eq pageId }
            .map { it.toPageStagedRecord() }.singleOrNull() ?: return@suspendTransaction false
        if (staged.publishAt == null || staged.publishAt > now) return@suspendTransaction false
        applyContentToLive(pageId, staged.title, staged.description, staged.content, staged.contentFormat, staged.infobox, staged.updatedBy)
        PageStagedTable.deleteWhere { PageStagedTable.pageId eq pageId }
        true
    }

    /** Restores a page's history revision as the live content (snapshotting current first). */
    suspend fun restoreRevision(pageId: UInt, revisionNumber: Int, by: UInt?): Boolean {
        val ok = suspendTransaction(database) {
            val rev = PageRevisionsTable.selectAll()
                .where { (PageRevisionsTable.pageId eq pageId) and (PageRevisionsTable.revisionNumber eq revisionNumber) }
                .map { it.toPageRevisionRecord() }
                .singleOrNull() ?: return@suspendTransaction false
            applyContentToLive(pageId, rev.title, rev.description, rev.content, rev.contentFormat, rev.infobox, by)
            true
        }
        if (ok) notifyContentChanged(pageId)
        return ok
    }

    /**
     * Moves a page to a new (locale, path). Returns false if the target is already taken (the page is
     * left unchanged). No redirect is created; inbound links to the old path will break (handle at the
     * reverse proxy if needed).
     */
    suspend fun move(pageId: UInt, newLocale: String, newPath: String, by: UInt?): Boolean {
        var movedFrom: Pair<String, String>? = null
        var siteId: UInt? = null
        val ok = suspendTransaction(database) {
            val existing = PagesTable.selectAll().where { PagesTable.id eq pageId }
                .map { it.toPageRecord() }.singleOrNull() ?: return@suspendTransaction false
            siteId = existing.siteId
            if (existing.locale == newLocale && existing.path == newPath) return@suspendTransaction true
            val taken = PagesTable.selectAll()
                .where { (PagesTable.siteId eq existing.siteId) and (PagesTable.locale eq newLocale) and (PagesTable.path eq newPath) }
                .toList().isNotEmpty()
            if (taken) return@suspendTransaction false
            movedFrom = existing.locale to existing.path
            PagesTable.update({ PagesTable.id eq pageId }) {
                it[locale] = newLocale
                it[path] = newPath
                it[updatedAt] = nowMillis()
                it[PagesTable.updatedBy] = by
            }
            true
        }
        // Reindex on any real move: the search document embeds the page path, and a locale change
        // additionally alters fragment resolution (locale→default fallback) — so both a rename and a
        // locale change must refresh the search-index row. `movedFrom` is non-null only when the path
        // or locale actually changed (not a no-op move to the same location).
        if (ok && movedFrom != null) notifyContentChanged(pageId)
        // The page left its old path (links there now dangle) and appeared at the new one (links there
        // may resolve) → re-render pages that link to either. Only fires when an actual move happened.
        movedFrom?.let { (oldLocale, oldPath) ->
            notifyPageExistenceChanged(siteId!!, oldLocale, oldPath)
            notifyPageExistenceChanged(siteId!!, newLocale, newPath)
        }
        return ok
    }

    /** Publishes any drafts whose scheduled time has arrived; returns how many were published. */
    suspend fun publishScheduled(now: Long): Int = suspendTransaction(database) {
        PagesTable.update({
            (PagesTable.published eq false) and PagesTable.publishAt.isNotNull() and (PagesTable.publishAt lessEq now)
        }) {
            it[published] = true
            it[publishAt] = null
        }
    }

    suspend fun delete(id: UInt): Boolean {
        // Capture the path before deleting so pages that link to it can be re-rendered (links go red).
        val gone = findById(id)
        val ok = suspendTransaction(database) {
            PageViewAclTable.deleteWhere { PageViewAclTable.pageId eq id }
            PageEditAclTable.deleteWhere { PageEditAclTable.pageId eq id }
            PageRevisionsTable.deleteWhere { PageRevisionsTable.pageId eq id }
            PageStagedTable.deleteWhere { PageStagedTable.pageId eq id }
            PageTagsTable.deleteWhere { PageTagsTable.pageId eq id }
            PageSearchIndexTable.deleteWhere { PageSearchIndexTable.pageId eq id }
            PageRenderCacheTable.deleteWhere { PageRenderCacheTable.pageId eq id }
            PagesTable.deleteWhere { PagesTable.id eq id } > 0
        }
        if (ok && gone != null) notifyPageExistenceChanged(gone.siteId, gone.locale, gone.path)
        return ok
    }

    suspend fun revisions(pageId: UInt): List<PageRevisionRecord> = suspendTransaction(database) {
        PageRevisionsTable.selectAll()
            .where { PageRevisionsTable.pageId eq pageId }
            .map { it.toPageRevisionRecord() }
            .toList()
            .sortedByDescending { it.revisionNumber }
    }

    suspend fun revision(pageId: UInt, revisionNumber: Int): PageRevisionRecord? = suspendTransaction(database) {
        PageRevisionsTable.selectAll()
            .where { (PageRevisionsTable.pageId eq pageId) and (PageRevisionsTable.revisionNumber eq revisionNumber) }
            .map { it.toPageRevisionRecord() }
            .singleOrNull()
    }

    suspend fun toDto(page: PageRecord) = page.toDto(viewAcl(page.id), editAcl(page.id))

    /** Loads a page's tags (sorted). Call within an existing transaction. */
    private suspend fun loadTags(pageId: UInt): List<String> =
        PageTagsTable.selectAll()
            .where { PageTagsTable.pageId eq pageId }
            .map { it[PageTagsTable.tag] }
            .toList()
            .sorted()

    /** Replaces a page's tags with [tags] (assumed already normalized). Call within a transaction. */
    private suspend fun replaceTags(pageId: UInt, tags: List<String>) {
        PageTagsTable.deleteWhere { PageTagsTable.pageId eq pageId }
        tags.forEach { t ->
            PageTagsTable.insert {
                it[PageTagsTable.pageId] = pageId
                it[PageTagsTable.tag] = t
            }
        }
    }

    private suspend fun loadAcl(pageId: UInt, view: Boolean): PageAcl = suspendTransaction(database) {
        if (view) {
            val rows = PageViewAclTable.selectAll().where { PageViewAclTable.pageId eq pageId }.toList()
            PageAcl(
                groupIds = rows.mapNotNull { it[PageViewAclTable.groupId]?.value }.toSet(),
                userIds = rows.mapNotNull { it[PageViewAclTable.userId]?.value }.toSet(),
            )
        } else {
            val rows = PageEditAclTable.selectAll().where { PageEditAclTable.pageId eq pageId }.toList()
            PageAcl(
                groupIds = rows.mapNotNull { it[PageEditAclTable.groupId]?.value }.toSet(),
                userIds = rows.mapNotNull { it[PageEditAclTable.userId]?.value }.toSet(),
            )
        }
    }

    private suspend fun replaceAcl(pageId: UInt, acl: PageAcl, view: Boolean) {
        if (view) {
            PageViewAclTable.deleteWhere { PageViewAclTable.pageId eq pageId }
            acl.groupIds.forEach { groupId ->
                PageViewAclTable.insert {
                    it[PageViewAclTable.pageId] = pageId
                    it[PageViewAclTable.groupId] = groupId
                }
            }
            acl.userIds.forEach { userId ->
                PageViewAclTable.insert {
                    it[PageViewAclTable.pageId] = pageId
                    it[PageViewAclTable.userId] = userId
                }
            }
        } else {
            PageEditAclTable.deleteWhere { PageEditAclTable.pageId eq pageId }
            acl.groupIds.forEach { groupId ->
                PageEditAclTable.insert {
                    it[PageEditAclTable.pageId] = pageId
                    it[PageEditAclTable.groupId] = groupId
                }
            }
            acl.userIds.forEach { userId ->
                PageEditAclTable.insert {
                    it[PageEditAclTable.pageId] = pageId
                    it[PageEditAclTable.userId] = userId
                }
            }
        }
    }

    companion object {
        /** Below this many characters a search returns nothing (avoids matching everything). */
        const val MIN_SEARCH_LENGTH = 2

        /** Max rows pulled from the DB before in-memory ranking/trim (bounds cost on large wikis). */
        const val SEARCH_FETCH_CAP = 200

        /**
         * Columns [search] projects from PagesTable — every field except the heavy text ones (content,
         * infobox, customCss, customJs), which search never renders. Selecting these instead of the
         * whole row keeps a broad match from transferring every matched page's full body. Maps via
         * [toSearchPageRecord]; keep the two in sync.
         */
        private val SEARCH_PAGE_COLUMNS: List<Expression<*>> = listOf(
            PagesTable.id, PagesTable.siteId, PagesTable.locale, PagesTable.path,
            PagesTable.title, PagesTable.description, PagesTable.metaRobots,
            PagesTable.contentFormat, PagesTable.published, PagesTable.publishAt,
            PagesTable.createdAt, PagesTable.updatedAt, PagesTable.updatedBy,
        )

        private val MD_NOISE = Regex("""[#>*_`~\[\]()!]|\{\{[^}]*}}""")

        /**
         * A plain-text excerpt (~[maxLen] chars) of [content] centered on the first case-insensitive
         * occurrence of [query], with light Markdown stripping and ellipsis padding. Falls back to
         * the content head when there's no match (e.g. a title-only hit).
         */
        fun searchSnippet(content: String, query: String, maxLen: Int = 200): String {
            val flat = MD_NOISE.replace(content, " ").replace(Regex("""\s+"""), " ").trim()
            if (flat.isEmpty()) return ""
            val idx = flat.lowercase().indexOf(query.trim().lowercase())
            if (idx < 0 || query.isBlank()) {
                return if (flat.length <= maxLen) flat else flat.take(maxLen).trimEnd() + "…"
            }
            val half = maxLen / 2
            var start = (idx - half).coerceAtLeast(0)
            var end = (idx + query.length + half).coerceAtMost(flat.length)
            // Snap to word boundaries so we don't cut mid-word.
            if (start > 0) {
                val sp = flat.indexOf(' ', start)
                if (sp in start until idx) start = sp + 1
            }
            if (end < flat.length) {
                val sp = flat.lastIndexOf(' ', end)
                if (sp > idx + query.length) end = sp
            }
            val core = flat.substring(start, end).trim()
            return (if (start > 0) "…" else "") + core + (if (end < flat.length) "…" else "")
        }

        /** A raw query split into free text and its `#tag` filters. */
        data class ParsedSearchQuery(val text: String, val tags: List<String>)

        private val TAG_TOKEN = Regex("#([\\p{L}\\p{N}][\\p{L}\\p{N}_-]*)")
        private val WHITESPACE = Regex("""\s+""")

        /**
         * Splits a raw query into free text and `#tag` filters (WikiJS-style). Tags are normalized the
         * same way stored tags are (so they match), and the tag tokens are stripped from the free text.
         */
        fun parseSearchQuery(raw: String): ParsedSearchQuery {
            val tags = normalizeTags(TAG_TOKEN.findAll(raw).map { it.groupValues[1] }.toList())
            val text = TAG_TOKEN.replace(raw, " ").replace(WHITESPACE, " ").trim()
            return ParsedSearchQuery(text, tags)
        }

        /** HTML-escapes [text], then wraps case-insensitive occurrences of any [terms] in `<mark>`. */
        fun highlightMatches(text: String?, terms: List<String>): String {
            val escaped = escapeHtml(text.orEmpty())
            val cleaned = terms.filter { it.isNotBlank() }.map { escapeHtml(it) }
            if (cleaned.isEmpty() || escaped.isEmpty()) return escaped
            val alternation = cleaned.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
            return Regex(alternation, RegexOption.IGNORE_CASE).replace(escaped) { "<mark>${it.value}</mark>" }
        }

        private fun escapeHtml(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}

/** A search match: the page plus the text it matched against (raw content or fragment-expanded), used for snippeting. */
data class PageSearchHit(val page: PageRecord, val searchText: String)
