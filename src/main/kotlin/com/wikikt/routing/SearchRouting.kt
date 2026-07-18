package com.wikikt.routing

import com.wikikt.AppContext
import com.wikikt.appContext
import com.wikikt.siteId
import com.wikikt.model.NavItemRecord
import com.wikikt.model.SearchResultDto
import com.wikikt.service.NavService
import com.wikikt.service.PageService
import io.ktor.server.application.call
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.net.URLEncoder

/**
 * Runs a page search and shapes it for the UI: [PageService.search] (DB-filtered + ranked) →
 * permission filter → [SearchResultDto] with a snippet. Fetches extra rows before the permission
 * filter so a few hidden pages don't shrink the result below [limit].
 */
internal suspend fun searchResults(
    siteId: UInt,
    ctx: AppContext,
    query: String,
    locale: String?,
    limit: Int,
    userId: UInt?,
): List<SearchResultDto> {
    // Snippet on the free-text part only (a `#tag` token shouldn't drive the excerpt window).
    val text = PageService.parseSearchQuery(query).text
    // Resolve the viewer's permissions once for the whole candidate set (not once per hit).
    val hits = ctx.permissions.filterViewable(userId, ctx.pages.search(siteId, query, locale, limit * 3)) { it.page }
        .take(limit)
    // Likewise the nav labels: resolved for the whole set up front, not once per hit (see below).
    val parentLabels = parentLabels(siteId, ctx, hits.map { it.page.path })
    return hits.map { hit ->
        val p = hit.page
        SearchResultDto(
            locale = p.locale,
            path = p.path,
            title = p.title,
            description = p.description,
            // Snippet from the fragment-expanded text so a fragment-only match still excerpts well.
            snippet = PageService.searchSnippet(hit.searchText, text),
            tags = p.tags,
            url = wikiViewUrl(p.locale, p.path),
            parentLabel = parentLabels.getValue(p.path),
        )
    }
}

/**
 * Breadcrumb-ish label for each of [paths]: the page's *full* ancestor trail rendered friendly and
 * joined by [PATH_SEPARATOR] — each ancestor segment shown as a curated nav label if some menu item
 * targets it, else the humanized segment. The page's own leaf segment is excluded (it's already the
 * result title). Top-level pages have no ancestors, so they get an empty label — the implicit "Home"
 * root is *not* shown — except the home page itself, which gets [ROOT_PARENT_LABEL] so it isn't blank.
 *
 * Resolved for the whole result set in one go: a per-hit nav lookup means two round-trips *per hit*
 * (a 50-hit search page = 100 of them), and those are sequential, which is free on in-process H2 but
 * seconds of latency on Postgres. This costs one query for the menus plus one per distinct menu
 * actually referenced — normally two in total, and none at all when every hit is top-level.
 *
 * Not localized yet: [ROOT_PARENT_LABEL] and [humanizePathSegment] both assume English for now.
 */
private suspend fun parentLabels(siteId: UInt, ctx: AppContext, paths: List<String>): Map<String, String> {
    // Every ancestor prefix of a path, leaf excluded: "a/b/c" -> ["a", "a/b"]; top-level -> [].
    fun ancestorsOf(path: String): List<String> {
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        return (1 until segments.size).map { segments.subList(0, it).joinToString("/") }
    }

    val prefixes = paths.flatMap(::ancestorsOf).distinct()
    val menus = if (prefixes.isEmpty()) emptyList() else ctx.nav.listMenus(siteId)
    val itemsByMenu = mutableMapOf<UInt, List<NavItemRecord>>()
    // Friendly label for a single ancestor prefix (the last segment carries the display text).
    val labels = prefixes.associateWith { prefix ->
        val target = "/$prefix"
        val items = NavService.menuFor(menus, target)
            ?.let { menu -> itemsByMenu.getOrPut(menu.id) { ctx.nav.items(menu.id) } }
            .orEmpty()
        items.find { it.target == target }?.label ?: humanizePathSegment(prefix.substringAfterLast('/'))
    }
    return paths.associateWith { path ->
        val ancestors = ancestorsOf(path)
        when {
            ancestors.isNotEmpty() -> ancestors.joinToString(PATH_SEPARATOR) { labels.getValue(it) }
            path.trim('/') == HOME_PAGE_PATH -> ROOT_PARENT_LABEL
            else -> ""
        }
    }
}

private const val ROOT_PARENT_LABEL = "Home"

/** Separator between ancestor labels in a search result's path trail; matches the breadcrumb divider. */
private const val PATH_SEPARATOR = " › "

/** The full HTML search results page at `/s` (the dropdown's "see all results" target). */
fun Route.configureSearchRouting() {
    get("/s") {
        val ctx = call.appContext
        val siteId = call.siteId()
        val userId = call.currentUserId()
        val q = call.request.queryParameters["q"]?.trim().orEmpty()
        val allLocales = call.request.queryParameters["allLocales"] == "1"
        val localeParam = call.request.queryParameters["locale"]?.takeIf { it.isNotBlank() }
            ?: ctx.config.defaultLocale
        val scope = if (allLocales) null else localeParam

        val results = if (q.length >= PageService.MIN_SEARCH_LENGTH) {
            searchResults(siteId, ctx, q, scope, limit = 50, userId = userId)
        } else {
            emptyList()
        }
        val username = userId?.let { ctx.users.findById(it)?.username }
        val enc = URLEncoder.encode(q, Charsets.UTF_8)
        val parsed = PageService.parseSearchQuery(q)
        // Highlight the free-text words (not the #tag tokens) in each result's title/snippet/description.
        val terms = parsed.text.split(Regex("\\s+")).filter { it.length >= 2 }

        // --- Tag facet: tags present in the current results (minus already-active ones), with counts,
        //     each linking to a query with that tag added; active tags link to the query with it removed. ---
        fun searchUrl(tags: List<String>): String {
            val query = (parsed.text + " " + tags.joinToString(" ") { "#$it" }).trim().replace(Regex("\\s+"), " ")
            return "/s?q=${URLEncoder.encode(query, Charsets.UTF_8)}&locale=$localeParam" + if (allLocales) "&allLocales=1" else ""
        }
        val facetTags = results.flatMap { it.tags }
            .filter { it !in parsed.tags }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { (tag, count) -> mapOf("name" to tag, "count" to count, "url" to searchUrl(parsed.tags + tag)) }
        val activeTags = parsed.tags.map { tag -> mapOf("name" to tag, "url" to searchUrl(parsed.tags - tag)) }

        call.respond(
            MustacheContent(
                "search.hbs",
                mapOf(
                    "searchQ" to q,
                    "searchLocale" to localeParam,
                    "query" to q,
                    "hasQuery" to q.isNotEmpty(),
                    "tooShort" to (q.isNotEmpty() && q.length < PageService.MIN_SEARCH_LENGTH),
                    "hasResults" to results.isNotEmpty(),
                    "resultCount" to results.size,
                    "results" to results.map {
                        mapOf(
                            "title" to it.title,
                            // Pre-escaped HTML with <mark> around matches; rendered with {{{ }}}.
                            "titleHtml" to PageService.highlightMatches(it.title, terms),
                            "url" to it.url,
                            "path" to it.path,
                            "parentLabel" to it.parentLabel,
                            "hasParentLabel" to it.parentLabel.isNotBlank(),
                            "snippet" to it.snippet,
                            "snippetHtml" to PageService.highlightMatches(it.snippet, terms),
                            "description" to it.description,
                            "descriptionHtml" to PageService.highlightMatches(it.description, terms),
                            "hasDescription" to !it.description.isNullOrBlank(),
                            "tags" to it.tags.map { t -> mapOf("name" to t, "url" to tagUrl(t)) },
                            "hasTags" to it.tags.isNotEmpty(),
                            "locale" to it.locale,
                        )
                    },
                    "facetTags" to facetTags,
                    "hasFacetTags" to facetTags.isNotEmpty(),
                    "activeTags" to activeTags,
                    "hasActiveTags" to activeTags.isNotEmpty(),
                    "hasFacets" to (facetTags.isNotEmpty() || activeTags.isNotEmpty()),
                    "allLocales" to allLocales,
                    "localeScope" to localeParam,
                    "allLocalesUrl" to "/s?q=$enc&locale=$localeParam&allLocales=1",
                    "currentLocaleUrl" to "/s?q=$enc&locale=$localeParam",
                    "loggedIn" to (userId != null),
                    "canAdmin" to ctx.permissions.canAccessAdmin(userId),
                    "username" to username,
                    "canCreate" to ctx.permissions.canCreatePagesOnSite(userId, siteId),
                ) + call.navModel(),
            ),
        )
    }
}
