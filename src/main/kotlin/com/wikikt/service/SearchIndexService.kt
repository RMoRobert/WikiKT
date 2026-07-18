package com.wikikt.service

import com.wikikt.model.PageRecord

/**
 * Keeps [com.wikikt.db.PageSearchIndexTable] in sync: each page's row holds its body with
 * `{{fragment:key}}` references expanded inline, so search can match text that lives in a
 * transcluded fragment as if it were part of the page.
 *
 * Wired by [com.wikikt.AppContext] as the late-bound callback target of [PageService.onContentChanged]
 * (one page) and [FragmentService.onFragmentsChanged] (only the pages that transclude the changed
 * fragment, directly or through other fragments), which avoids a service dependency cycle.
 */
class SearchIndexService(
    private val pages: PageService,
    private val fragments: FragmentService,
    private val defaultLocale: String,
) {
    /** Rebuilds the index row for a single page (no-op if the page no longer exists). */
    suspend fun reindex(pageId: UInt) {
        val page = pages.findById(pageId) ?: return
        reindexPage(page)
    }

    /** Rebuilds every page's index row for [siteId] (the admin "rebuild search index" action). */
    suspend fun reindexAll(siteId: UInt) {
        pages.list(siteId).forEach { reindexPage(it) }
    }

    /**
     * Reindexes only the pages whose expanded body depends on a changed fragment. [changedKeys] are
     * the fragment keys touched by the edit (on a rename, both the old and new key). The affected set
     * is closed over the fragment graph — a fragment that transcludes an affected fragment is itself
     * affected — then any page referencing an affected key is rebuilt. Matching is by key string
     * (the `{{fragment:key}}` token), so it over-includes across locales rather than ever missing a
     * page; that's safe and far cheaper than a full rebuild when few pages use fragments.
     */
    suspend fun reindexForFragmentKeys(siteId: UInt, changedKeys: Set<String>) {
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
            if (fragments.referencedKeys(page.content).any { it in affected }) reindexPage(page)
        }
    }

    /** Backfills any page that has no index row yet (one-time cost), across all sites. */
    suspend fun reindexMissing() {
        pages.pageIdsMissingSearchIndex().forEach { reindex(it) }
    }

    private suspend fun reindexPage(page: PageRecord) {
        val expandedBody = fragments.expand(page.siteId, page.content, page.locale, defaultLocale)
        pages.reindexSearchText(page.id, buildSearchDocument(page, expandedBody))
    }

    /**
     * The searchable document for a page: its title, description and path folded in ahead of the
     * fragment-expanded body. Holding every field the search matches on in this one column lets the
     * free-text query hit a single (trigram-indexable on Postgres) column instead of OR-ing LIKEs
     * across several columns in two tables — the latter forces a sequential scan. In-memory ranking
     * in [PageService.search] still uses the individual title/path/description fields for ordering.
     */
    private fun buildSearchDocument(page: PageRecord, expandedBody: String): String =
        listOf(page.title, page.description.orEmpty(), page.path, expandedBody)
            .filter { it.isNotBlank() }
            .joinToString("\n")
}
