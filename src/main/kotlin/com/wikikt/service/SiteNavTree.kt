package com.wikikt.service

import com.wikikt.model.PageRecord
import com.wikikt.routing.humanizePathSegment
import kotlinx.serialization.Serializable

/**
 * A node in the site navigation tree: a folder derived from a page path, a page, or both (a page that
 * also has descendants). [path] is the full slash-joined wiki path of the node; [label] is the page
 * title when a page exists here, otherwise a humanized last path segment. [hasPage]/[url] are set only
 * when a real page lives at exactly this path — a pure container folder has `hasPage = false` and no
 * [url], and is navigated into (drilled) rather than opened. [children] are the immediate sub-nodes.
 *
 * Serialized to JSON and embedded in the sidebar for the client-side drill-down browser (wk-nav-browser.js).
 */
@Serializable
data class WikiTreeNode(
    val path: String,
    val label: String,
    val hasPage: Boolean,
    val url: String? = null,
    val children: List<WikiTreeNode> = emptyList(),
)

/**
 * Builds a folder/page tree from the flat wiki page paths (WikiKT stores paths as slash-joined strings
 * with no parent column, so the hierarchy is derived here by splitting each path on `/`). Intermediate
 * path prefixes that have no page of their own become container folders labelled from the segment; a
 * prefix that also happens to be a real page is both.
 *
 * The caller is responsible for passing only pages the viewer may see (see
 * [PermissionService.readablePages]) and only one locale's pages — the tree is locale-scoped and URLs
 * are built as the canonical `/{locale}/{path}` (mirroring `wikiViewUrl`).
 */
object SiteNavTree {
    private class MutableNode(val path: String, val segment: String) {
        var hasPage = false
        var title: String? = null
        var url: String? = null

        // Insertion-order preserved; final sort happens on conversion.
        val children = LinkedHashMap<String, MutableNode>()
    }

    fun build(pages: List<PageRecord>): List<WikiTreeNode> {
        val root = MutableNode("", "")
        for (page in pages) {
            val segments = page.path.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) continue
            var cur = root
            var acc = ""
            for (segment in segments) {
                acc = if (acc.isEmpty()) segment else "$acc/$segment"
                val prefix = acc
                cur = cur.children.getOrPut(segment) { MutableNode(prefix, segment) }
            }
            cur.hasPage = true
            cur.title = page.title
            // Canonical page URL — the locale-qualified form every internal link uses (see wikiViewUrl).
            cur.url = "/${page.locale}/${page.path}"
        }
        return convert(root.children.values)
    }

    private fun convert(nodes: Collection<MutableNode>): List<WikiTreeNode> =
        nodes.map { n ->
            WikiTreeNode(
                path = n.path,
                label = n.title ?: humanizePathSegment(n.segment),
                hasPage = n.hasPage,
                url = n.url,
                children = convert(n.children.values),
            )
        }.sortedWith(
            // Folders (anything with children) first, then leaf pages; each alphabetized case-insensitively —
            // a predictable file-browser ordering independent of page insertion order.
            compareBy({ if (it.children.isEmpty()) 1 else 0 }, { it.label.lowercase() }),
        )
}
