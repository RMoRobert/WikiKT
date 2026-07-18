package com.wikikt.markdown

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * Allowlist-based HTML sanitizer. Everything rendered into a page (Markdown- or HTML-sourced) passes
 * through here, so stored `<script>`, `javascript:` URLs, event handlers, etc. are stripped. It also
 * applies callout classes (`{.is-info}`, ...), turns `{.tabset}` headings into Bootstrap
 * tab groups, and hardens embedded iframes (https-only, no `srcdoc`, sandboxed).
 */
object HtmlSanitizer {
    // Safelists vary only by the two admin-toggleable options (iframes, style=""), so there are at most
    // four; each is built once on first use and reused. jsoup Safelists are read-only during cleaning,
    // so sharing them across concurrent requests is safe.
    private val safelists = java.util.concurrent.ConcurrentHashMap<Pair<Boolean, Boolean>, Safelist>()

    private fun safelistFor(options: RenderOptions): Safelist =
        safelists.computeIfAbsent(options.allowIframes to options.allowStyleAttr) { (iframes, style) ->
            buildSafelist(allowIframes = iframes, allowStyleAttr = style)
        }

    private fun buildSafelist(allowIframes: Boolean, allowStyleAttr: Boolean): Safelist {
        val safelist = Safelist.relaxed()
            // GFM strikethrough renders <del>; allow the matching <ins> and horizontal rules too.
            // <kbd> = keyboard keys; <input> = task-list checkboxes; <section> = the footnotes block;
            // <details>/<summary> = native collapsible disclosure widget.
            .addTags("del", "ins", "hr", "kbd", "input", "section", "details", "summary")
            // <details open> renders expanded by default (boolean attribute, inert/safe).
            .addAttributes("details", "open")
            // Allow class (code blocks / tables / callouts / icons) and aria attributes; nothing executable.
            .addAttributes(":all", "class", "aria-hidden", "aria-label")
            .addAttributes("ol", "start")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan", "scope")
            // Task-list checkboxes (disabled; no name/value, so nothing submittable).
            .addAttributes("input", "type", "checked", "disabled")
            // Footnote jump targets: refs/definitions carry ids that the in-page links point at.
            .addAttributes("a", "id")
            .addAttributes("li", "id")
            // Only permit safe URL protocols; this drops javascript:, data:, etc.
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https")
            .preserveRelativeLinks(true)
        if (allowIframes) {
            // Embeds (videos, maps, …). `srcdoc` is deliberately NOT allowed (it can run inline scripts),
            // and src is restricted to https; every iframe is also sandboxed post-clean (hardenIframes).
            // Gated behind the Rendering setting because iframes load arbitrary third-party content.
            safelist.addTags("iframe")
                .addAttributes(
                    "iframe",
                    "src", "width", "height", "title", "frameborder",
                    "allow", "allowfullscreen", "loading", "referrerpolicy", "sandbox",
                )
                .addProtocols("iframe", "src", "https")
        }
        if (allowStyleAttr) {
            // WARNING: jsoup does NOT sanitize the CSS inside style=""; it only allows the attribute.
            // Gated behind the Rendering setting (off by default) because CSS is a clickjacking/overlay
            // vector and trusts page authors.
            safelist.addAttributes(":all", "style")
        }
        return safelist
    }

    // Callout classes -> the MDI icon shown in the box. Only these may be applied via
    // `{.x}`; anything else is ignored, so the syntax can't inject arbitrary classes.
    private val CALLOUT_ICONS = mapOf(
        "is-info" to "information-outline",
        "is-success" to "check-circle-outline",
        "is-warning" to "alert-outline",
        "is-danger" to "alert-octagon-outline",
        "is-error" to "alert-octagon-outline",
    )
    private val CALLOUT_CLASSES = CALLOUT_ICONS.keys
    // Classes that may be applied via the `{.x}` decorate syntax. Callouts get an icon; `links-list`
    // restyles a list of links into description blocks (styled in site.css). Anything else
    // is ignored, so the syntax can't inject arbitrary classes.
    private val DECORATE_CLASSES = CALLOUT_CLASSES + "links-list"
    private val STANDALONE = Regex("^\\{\\.([a-z0-9 .-]+)}$")
    private val TRAILING = Regex("\\s*\\{\\.([a-z0-9 .-]+)}\\s*$")

    // Placeholder base URI used only so jsoup can resolve relative links (e.g. /my-path/my-document to an
    // absolute http: URL when validating href protocols. Because preserveRelativeLinks is true the output
    // keeps the original relative form; this value never appears in the rendered HTML. Without a base URI,
    // jsoup resolves relative links to "", matches none of the allowed protocols, and strips the href. Using
    // reserved TLD to avoid conflicts.
    private const val RELATIVE_LINK_BASE = "https://internal-placeholder.wikikt.invalid/"

    fun sanitize(html: String, options: RenderOptions = RenderOptions.DEFAULT): String {
        val doc = Jsoup.parseBodyFragment(html, RELATIVE_LINK_BASE)
        demoteHeadings(doc)
        applyDecorations(doc)
        val clean = Cleaner(safelistFor(options)).clean(doc)
        // These run AFTER cleaning: the tab markup we generate (buttons, data-bs-*, ids) is trusted
        // and must not be re-stripped, and we harden the (already allowlisted) iframes.
        applyTabsets(clean)
        if (options.allowIframes) hardenIframes(clean)
        if (options.externalLinkMode != ExternalLinkMode.OFF) markExternalLinks(clean, options)
        return clean.body().html()
    }

    /**
     * Demotes every content heading one level: h1→h2, … h5→h6, and h6 stays h6, so the page's
     * template-rendered title `<h1>` is the only h1 on the page (per accessibility recommendation to have
     * only one H1 per page). Applies to both Markdown-generated and author-supplied HTML, since both flow
     * through here. h5 and h6 both are h6 (since there is no h7); should document that do not recommend
     * this deep of nesting.
     */
    private fun demoteHeadings(doc: Document) {
        doc.select("h1, h2, h3, h4, h5").forEach { h ->
            val level = h.tagName().substring(1).toInt()
            h.tagName("h${level + 1}")
        }
    }

    private fun applyDecorations(doc: Document) {
        // `{.x}` on its own paragraph (blank line before it) -> the class goes on the preceding block.
        doc.select("p").forEach { p ->
            val match = STANDALONE.matchEntire(p.wholeText().trim()) ?: return@forEach
            p.previousElementSibling()?.let { applyClasses(it, match.groupValues[1]) }
            p.remove()
        }
        // Wiki.js compatibility form: `> quote {.is-x}`. The marker lands as trailing text in the blockquote's last
        // paragraph. Apply it to the blockquote and strip the marker.
        doc.select("blockquote").forEach { bq ->
            val container = bq.select("p").lastOrNull() ?: bq
            stripTrailingDecoration(bq, container.textNodes().lastOrNull { it.wholeText.isNotBlank() })
        }
        // Wiki.js compatiblity `{.links-list}` form: with no blank line the marker lands as trailing text in the
        // last <li>; apply it to the parent list and strip the marker.
        doc.select("ul, ol").forEach { list ->
            val lastLi = list.children().lastOrNull { it.tagName() == "li" } ?: return@forEach
            stripTrailingDecoration(list, lastLi.textNodes().lastOrNull { it.wholeText.isNotBlank() })
        }
    }

    /** Consumes a trailing `{.x}` decorate marker in [node]: applies any recognized class to [target]
     *  and always strips the marker (so unrecognized markers don't render as literal `{.x}` text). */
    private fun stripTrailingDecoration(target: Element, node: org.jsoup.nodes.TextNode?) {
        if (node == null) return
        val match = TRAILING.find(node.wholeText) ?: return
        applyClasses(target, match.groupValues[1])
        node.text(node.wholeText.substring(0, match.range.first).trimEnd())
    }

    /** Adds recognized decorate class(es) to [element] (callouts also get a leading icon). Returns
     *  whether any class was applied. */
    private fun applyClasses(element: Element, raw: String): Boolean {
        val classes = raw.split(Regex("[ .]+")).filter { it in DECORATE_CLASSES }
        if (classes.isEmpty()) return false
        classes.forEach { element.addClass(it) }

        // Callout classes (not links-list) get a leading info/warning/etc. glyph, prepended once.
        val callout = classes.firstOrNull { it in CALLOUT_CLASSES }
        if (callout != null && element.children().firstOrNull()?.hasClass("callout-icon") != true) {
            CALLOUT_ICONS[callout]?.let { icon ->
                element.prependElement("i")
                    .addClass("mdi").addClass("mdi-$icon").addClass("callout-icon")
                    .attr("aria-hidden", "true")
            }
        }
        return true
    }

    private val TABSET_MARKER = Regex("\\{\\.tabset}\\s*$")

    /**
     * Content tabs: a heading marked `{.tabset}` becomes a Bootstrap tab group. Its one-level-
     * deeper child headings are the tab labels; the content under each child is that tab's pane. The
     * `{.tabset}` heading itself is dropped (per Wiki.js compatibility). The section ends at the next heading of
     * the parent's level or higher. Runs after the Cleaner so the generated `data-bs-*`/`button`/`id`
     * markup is trusted; the pane content was already sanitized. Bootstrap's data API does the switching.
     */
    private fun applyTabsets(doc: Document) {
        val markers = doc.select("h1, h2, h3, h4, h5, h6")
            .filter { TABSET_MARKER.containsMatchIn(it.wholeText().trim()) }
        var setIndex = 0
        for (parent in markers) {
            if (parent.parent() == null) continue // moved/removed by an earlier (e.g. nested) tabset
            val level = parent.tagName().drop(1).toIntOrNull() ?: continue
            if (level >= 6) { // no deeper level for child tabs; just drop the marker text
                parent.text(TABSET_MARKER.replace(parent.wholeText(), "").trim())
                continue
            }
            val childName = "h${level + 1}"

            // Following siblings up to the next heading at the parent's level or higher.
            val section = ArrayList<Element>()
            var sib = parent.nextElementSibling()
            while (sib != null) {
                val t = sib.tagName()
                if (t.length == 2 && t[0] == 'h') {
                    val l = t[1].digitToIntOrNull()
                    if (l != null && l <= level) break
                }
                section.add(sib)
                sib = sib.nextElementSibling()
            }

            // Split into tabs delimited by the child headings (leading content before the first is dropped).
            val labels = ArrayList<String>()
            val panes = ArrayList<ArrayList<Element>>()
            for (el in section) {
                if (el.tagName() == childName) {
                    labels.add(el.wholeText().trim())
                    panes.add(ArrayList())
                } else if (panes.isNotEmpty()) {
                    panes.last().add(el)
                }
            }
            if (labels.isEmpty()) {
                parent.text(TABSET_MARKER.replace(parent.wholeText(), "").trim())
                continue
            }

            setIndex++
            val tabset = doc.createElement("div").addClass("wk-tabset")
            val nav = doc.createElement("ul").addClass("nav").addClass("nav-tabs").attr("role", "tablist")
            val content = doc.createElement("div").addClass("tab-content")
            for (i in labels.indices) {
                val id = "wk-tab-$setIndex-${i + 1}"
                val li = doc.createElement("li").addClass("nav-item").attr("role", "presentation")
                val btn = doc.createElement("button")
                    .addClass("nav-link").attr("type", "button")
                    .attr("data-bs-toggle", "tab").attr("data-bs-target", "#$id")
                    .attr("role", "tab").attr("aria-selected", if (i == 0) "true" else "false")
                btn.text(labels[i])
                if (i == 0) btn.addClass("active")
                li.appendChild(btn)
                nav.appendChild(li)

                val pane = doc.createElement("div").addClass("tab-pane").addClass("fade")
                    .attr("id", id).attr("role", "tabpanel")
                if (i == 0) pane.addClass("show").addClass("active")
                for (el in panes[i]) pane.appendChild(el) // reparents el into the pane
                content.appendChild(pane)
            }
            tabset.appendChild(nav)
            tabset.appendChild(content)
            parent.before(tabset)

            // Drop leftovers (child headings + any leading content not moved into a pane), then the marker.
            val moved = panes.flatten().toHashSet()
            for (el in section) if (el !in moved) el.remove()
            parent.remove()
        }
    }

    /**
     * Appends the "opens elsewhere" marker to links that leave the wiki, per [RenderOptions.externalLinkMode]
     * (already known to be non-off). Runs after the Cleaner so the icon `<i>` we add is trusted markup.
     * Baked into the cached body, no cost per view.
     *
     * A link is external only when its href is an absolute web URL (`http://`, `https://`, or the
     * protocol-relative `//host`). Relative and root-absolute (`/…`) links, and non-web schemes
     * (`mailto:`, `tel:`, `#anchor`), are not consided external. In [ExternalLinkMode.INSTANCE] a candidate whose
     * host is one of [RenderOptions.internalHosts] is treated as internal too. Image-only links and links
     * already marked are skipped.
     */
    private fun markExternalLinks(doc: Document, options: RenderOptions) {
        for (a in doc.select("a[href]")) {
            if (a.hasClass("wk-external-link")) continue // already decorated
            val href = a.attr("href").trim()
            if (!isExternalHref(href, options)) continue
            if (a.selectFirst("img") != null) continue // an image link — appending an icon reads oddly
            a.addClass("wk-external-link")
            a.appendElement("i")
                .addClass("mdi").addClass("mdi-open-in-new").addClass("wk-external-link-icon")
                .attr("aria-hidden", "true")
        }
    }

    /** Whether [href] points outside the wiki given the active [RenderOptions.externalLinkMode]. */
    private fun isExternalHref(href: String, options: RenderOptions): Boolean {
        val lower = href.lowercase()
        val isAbsoluteWeb = lower.startsWith("http://") || lower.startsWith("https://") || href.startsWith("//")
        if (!isAbsoluteWeb) return false // relative, root-absolute, mailto:, tel:, #anchor (don't mark)
        if (options.externalLinkMode != ExternalLinkMode.INSTANCE) return true // SITE: any absolute link is external
        val host = hostOf(href) ?: return true // unparseable host; treat as external
        return host !in options.internalHosts
    }

    /** The lowercased host of an absolute (or protocol-relative) URL, or null if it can't be parsed. */
    private fun hostOf(url: String): String? =
        try {
            java.net.URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        }

    /**
     * Defense-in-depth for embeds: every iframe (already restricted to https, no `srcdoc`) gets a
     * restrictive `sandbox` (no top-navigation/downloads/modals -- but enough for video/map embeds),
     * plus lazy loading and a privacy-preserving referrer policy. Author-set values are respected.
     */
    private fun hardenIframes(doc: Document) {
        doc.select("iframe").forEach { f ->
            // No usable src (e.g. a javascript:/data: src was just stripped) -> drop the empty frame.
            if (f.attr("src").isBlank()) {
                f.remove()
                return@forEach
            }
            if (!f.hasAttr("sandbox")) {
                f.attr(
                    "sandbox",
                    "allow-scripts allow-same-origin allow-popups allow-popups-to-escape-sandbox " +
                        "allow-presentation allow-forms",
                )
            }
            if (!f.hasAttr("loading")) f.attr("loading", "lazy")
            if (!f.hasAttr("referrerpolicy")) f.attr("referrerpolicy", "strict-origin-when-cross-origin")
        }
    }
}
