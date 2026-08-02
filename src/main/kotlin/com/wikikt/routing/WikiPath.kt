package com.wikikt.routing

/**
 * The reserved wiki path that serves as the site's landing page. `/` and a bare
 * locale root (`/en`) redirect to `/{locale}/home`. There is deliberately no config override — the
 * home page always lives at this path; create a page here to fill it in.
 */
const val HOME_PAGE_PATH = "home"

data class WikiPathRequest(
    val edit: Boolean,
    val locale: String,
    val pagePath: String,
    // Whether the URL actually carried a locale segment. When false, the locale defaulted, and the page
    // handler 301-redirects to the canonical locale-qualified URL.
    val localeExplicit: Boolean,
)

private val LOCALE_PATTERN = Regex("^[a-z]{2}(-[A-Z]{2})?$")

fun isLocaleSegment(segment: String): Boolean = LOCALE_PATTERN.matches(segment)

// A locale segment, case-insensitive on the region (e.g., `pt-br` matches `pt-BR`).
private val LOCALE_NORMALIZABLE = Regex("^([a-z]{2})(?:-([A-Za-z]{2}))?$")

/**
 * Canonicalizes a path segment to WikiKT's locale form (`en`, `pt-BR`) -- lowercase language, uppercase
 * region — or returns null if it isn't locale-shaped. Accepts a lowercase region so a WikiJS git export,
 * which writes folders like `pt-br`, is recognized. `isLocaleSegment(normalizeLocaleSegment(s)!!)` holds
 * for any non-null result.
 */
fun normalizeLocaleSegment(segment: String): String? {
    val m = LOCALE_NORMALIZABLE.matchEntire(segment) ?: return null
    val (lang, region) = m.destructured
    return if (region.isEmpty()) lang else "$lang-${region.uppercase()}"
}

fun parseWikiPath(segments: List<String>, defaultLocale: String): WikiPathRequest? {
    if (segments.isEmpty()) return null

    var remaining = segments
    var edit = false

    if (remaining.first() == "e") {
        edit = true
        remaining = remaining.drop(1)
        if (remaining.isEmpty()) return null
    }

    val localeExplicit = isLocaleSegment(remaining.first())
    val locale = if (localeExplicit) {
        remaining.first().also { remaining = remaining.drop(1) }
    } else {
        defaultLocale
    }

    if (remaining.isEmpty()) return null

    return WikiPathRequest(
        edit = edit,
        locale = locale,
        pagePath = remaining.joinToString("/"),
        localeExplicit = localeExplicit,
    )
}

// Canonical URLs always carry the locale segment; the unprefixed form 301-redirects here.
fun wikiViewUrl(locale: String, path: String): String = "/$locale/$path"

private val URL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*:")

/**
 * Resolves a directory-relative URL from a page body against the page that contains it, the way WikiJS
 * does: the containing page [pagePath] is treated as a *directory*, so `file1` on page
 * `dir1/dir2` targets `dir1/dir2/file1`, and `../foo` climbs out of it. `.`/`..`
 * segments are normalized and a climb past the locale root is clamped there. Returns a root-absolute
 * `/{locale}/{path}` (preserving any `?query`/`#fragment`), or null to leave the URL untouched — which
 * it is for empty, anchor-only (`#x`), root-absolute (`/x`), protocol-relative (`//x`), and scheme'd
 * (`https:`, `mailto:`, …) URLs. Without this the browser would resolve `file1` against the page as a
 * *file*, dropping its last segment and landing a directory too high.
 */
fun resolveRelativeWikiUrl(rawUrl: String, locale: String, pagePath: String): String? {
    val url = rawUrl.trim()
    if (url.isEmpty() || url[0] == '#' || url[0] == '/') return null // anchor, root-absolute, or protocol-relative
    if (URL_SCHEME.containsMatchIn(url)) return null                 // external scheme (http:, mailto:, tel:, …)
    val cut = url.indexOfFirst { it == '#' || it == '?' }
    val relPath = if (cut >= 0) url.substring(0, cut) else url
    val suffix = if (cut >= 0) url.substring(cut) else ""
    if (relPath.isEmpty()) return null                               // a bare "?query" on the current page — leave it
    val segments = pagePath.split('/').filter { it.isNotEmpty() }.toMutableList()
    for (seg in relPath.split('/')) when (seg) {
        "", "." -> {}
        ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
        else -> segments.add(seg)
    }
    if (segments.isEmpty()) return null
    return "/$locale/${segments.joinToString("/")}$suffix"
}

fun wikiEditUrl(locale: String, path: String): String = "/e/$locale/$path"

fun wikiHistoryUrl(locale: String, path: String): String = "/h/$locale/$path"

/**
 * Builds `<option>` models for a locale `<select>` from the [enabled] set, marking [current] selected.
 * If [current] isn't in the enabled set (e.g. an existing page on a since-removed locale), it's kept
 * as an extra option so editing never silently drops the page's locale.
 */
fun localeSelectOptions(enabled: List<String>, current: String): List<Map<String, Any?>> {
    val all = if (current.isNotBlank() && current !in enabled) enabled + current else enabled
    return all.map { mapOf("value" to it, "label" to it, "selected" to (it == current)) }
}

/** URL for the "pages tagged X" view. Encodes for a path segment (space -> %20, not '+'). */
fun tagUrl(tag: String): String =
    "/t/" + java.net.URLEncoder.encode(tag, Charsets.UTF_8).replace("+", "%20")
