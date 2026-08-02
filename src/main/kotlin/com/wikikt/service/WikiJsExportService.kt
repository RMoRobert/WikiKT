package com.wikikt.service

import com.wikikt.db.ContentFormat
import com.wikikt.model.AssetRecord
import com.wikikt.model.AssetRef
import com.wikikt.model.PageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports a site's content as a tree WikiJS 2.x can ingest directly through
 * *Administration > Storage > Local File System > **Import Everything*** — the documented way into a
 * WikiJS instance, and the mirror image of the WikiJS git/disk export WikiKT already imports.
 *
 * The archive is deliberately **nothing but that tree**: WikiJS's importer walks every file under its
 * storage folder and treats `.md`/`.html`/`.adoc` as pages and *everything else* as an asset, so a
 * stray `manifest.json` or `README.md` at the root would import as junk. Fragments/navigation JSON and
 * the WikiKT manifest that a [content backup][BackupService] carries therefore have no place here —
 * use a content backup to move between WikiKT instances, and this to leave for WikiJS.
 *
 * Layout:
 *  - pages at `{locale}/{path}.md` (or `.html`), always locale-prefixed. WikiJS's own dump omits the
 *    folder for its default locale and reads one leading locale folder back on import, so prefixing
 *    every page is both understood and safer: it pins the locale instead of inheriting the target's
 *    default. WikiKT forbids a locale-shaped first path segment, so a real path is never eaten.
 *  - assets at `{locale}/{path}` in the same tree, exactly where WikiJS puts them relative to its
 *    storage root — so an `en` page's `/en/logo.png` still resolves after the import.
 *
 * Page bodies are rewritten so nothing WikiKT-specific is left dangling — see [transformBody].
 */
class WikiJsExportService(
    private val pages: PageService,
    private val assets: AssetService,
    private val fragments: FragmentService,
    private val infoboxes: InfoboxService,
    private val defaultLocale: String,
) {
    /**
     * What to do with a page's infobox data, which WikiJS has no equivalent for. Both modes that keep
     * the data write the WikiKT `infobox:` YAML block into the front-matter — WikiJS parses the block
     * and drops the unknown key, and a later re-import into WikiKT restores the infobox exactly. They
     * differ only in whether the data is *also* rendered where a WikiJS reader can see it.
     */
    enum class InfoboxMode(val label: String) {
        /** Front-matter, plus a table folded into the body so the data stays visible in WikiJS. */
        TABLE("Insert as table at top of page"),

        /** Front-matter only: machine-readable, but nothing a WikiJS reader can see. */
        FRONT_MATTER("Keep as front-matter only (not rendered on page)"),

        /** Drop it. The page exports as if it never had one. */
        OMIT("Omit"),

        ;

        companion object {
            val DEFAULT = TABLE

            fun from(value: String?): InfoboxMode =
                entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
        }
    }

    data class Options(
        val infoboxMode: InfoboxMode = InfoboxMode.DEFAULT,
        /** Include pages that aren't published. WikiJS's importer ignores the `published` front-matter
         *  key and marks every imported page published, so these go **live** over there. */
        val includeUnpublished: Boolean = true,
    )

    /** Streams the WikiJS-importable ZIP for [siteId] to [out]. */
    suspend fun write(siteId: UInt, out: OutputStream, options: Options) {
        val pageList = pages.list(siteId).filter { options.includeUnpublished || it.published }
        val assetList = assets.list(siteId)
        val byRef = assetList.associateBy { AssetRef(it.locale, it.path) }
        val matchTemplates = infoboxes.matcherFor(siteId)
        withContext(Dispatchers.IO) {
            ZipOutputStream(out, StandardCharsets.UTF_8).use { zip ->
                for (page in pageList) {
                    val html = page.contentFormat == ContentFormat.HTML
                    val body = transformBody(siteId, page, byRef, matchTemplates, options)
                    zip.putNextEntry(ZipEntry("${page.locale}/${page.path}.${if (html) "html" else "md"}"))
                    zip.write(pageFile(page, body, options).toByteArray(StandardCharsets.UTF_8))
                    zip.closeEntry()
                }
                for (asset in assetList) {
                    val source = assets.fileForId(asset.id)
                    if (!Files.exists(source)) continue // metadata row without bytes; skip rather than fail
                    zip.putNextEntry(ZipEntry("${asset.locale}/${asset.path}"))
                    Files.copy(source, zip)
                    zip.closeEntry()
                }
            }
        }
    }

    // --- Page file ---

    /**
     * A page file: WikiJS's front-matter block followed by [body]. The metadata is *exactly* the seven
     * keys WikiJS writes, in its order (`server/helpers/page.js#injectPageMetadata`) — no WikiKT
     * extensions, except the `infobox:` block, which rides along for every [InfoboxMode] but
     * [OMIT][InfoboxMode.OMIT].
     *
     * WikiJS parses the block with js-yaml, so scalars are quoted whenever they'd otherwise change
     * meaning (WikiJS writes them raw, which is why *its* exports break on a title containing a colon).
     * `tags` stays a single comma-joined *string*, never a YAML list: WikiJS's importer calls
     * `.split(', ')` on it, and a list would throw and drop the page.
     */
    private fun pageFile(page: PageRecord, body: String, options: Options): String {
        val html = page.contentFormat == ContentFormat.HTML
        val meta = buildString {
            appendLine("title: ${PageFileFormat.yamlScalar(page.title)}")
            appendLine("description: ${PageFileFormat.yamlScalar(page.description.orEmpty())}")
            appendLine("published: ${page.published}")
            appendLine("date: ${Instant.ofEpochMilli(page.updatedAt)}")
            // Empty stays bare (`tags:`) rather than `""`: js-yaml reads that as null, which WikiJS
            // treats as "no tags in this file" instead of importing one empty tag.
            appendLine("tags: " + if (page.tags.isEmpty()) "" else PageFileFormat.yamlScalar(page.tags.joinToString(", ")))
            appendLine("editor: ${if (html) "code" else "markdown"}")
            append("dateCreated: ${Instant.ofEpochMilli(page.createdAt)}")
            // Kept for every mode but OMIT — including TABLE, so folding the data into the body for a
            // WikiJS reader doesn't also cost the machine-readable copy.
            if (options.infoboxMode != InfoboxMode.OMIT) {
                PageFileFormat.infoboxToYaml(page.infobox)?.let { append("\n").append(it) }
            }
        }
        return if (html) "<!--\n$meta\n-->\n\n$body" else "---\n$meta\n---\n\n$body"
    }

    /**
     * Rewrites a page body so nothing WikiKT-only survives as dead syntax on the other side:
     *
     *  1. `{{fragment:key}}` transclusions are expanded **literally** into the body (Markdown pages
     *     only — WikiKT doesn't expand them in HTML pages either). WikiJS has no fragments, so the
     *     alternative is a visible `{{fragment:…}}` on every page that used one.
     *  2. Asset URLs are pinned to the locale that actually serves them. WikiKT resolves a
     *     locale-relative `/logo.png` against the page's locale and falls back to the default locale's
     *     bytes; WikiJS has neither rule, so the URL is rewritten to the explicit `/{locale}/{path}`
     *     of the asset the wiki *would* have served, which is where the export puts the file.
     *  3. The `{alt}` sentinel (`![{alt}](…)`, `<img alt="{alt}">`) is replaced with the asset's stored
     *     alt text, since WikiJS would render the literal token.
     *  4. `:mdi-icon:` shortcodes become `<i class="mdi mdi-icon">` (Markdown only). WikiJS bundles the
     *     same Material Design Icons font and allows inline HTML, so the icon still renders; left as a
     *     shortcode it would show up as literal text.
     *  5. The infobox is folded in per [Options.infoboxMode].
     *
     * Steps 2–4 run over [ContentMasking]-masked text, so a shortcode or URL that a page is only
     * *documenting* inside a code span or fence is left exactly as written.
     */
    private suspend fun transformBody(
        siteId: UInt,
        page: PageRecord,
        byRef: Map<AssetRef, AssetRecord>,
        matchTemplates: (String, List<String>) -> List<com.wikikt.model.InfoboxTemplate>,
        options: Options,
    ): String {
        val markdown = page.contentFormat == ContentFormat.MARKDOWN
        val expanded = if (markdown) {
            fragments.expand(siteId, page.content, page.locale, defaultLocale)
        } else {
            page.content
        }

        val (masked, codeSpans) = ContentMasking.mask(expanded)
        var work = rewriteAssetUrls(masked, page.locale, byRef)
        work = resolveAltTokens(work, page.locale, byRef)
        if (markdown) work = ICON_SHORTCODE.replace(work) { "<i class=\"mdi mdi-${it.groupValues[1]}\" aria-hidden=\"true\"></i>" }
        val body = ContentMasking.restore(work, codeSpans)

        if (options.infoboxMode != InfoboxMode.TABLE) return body
        val boxes = infoboxes.plainInfoboxes(matchTemplates(page.path, page.tags), page.infobox)
        if (boxes.isEmpty()) return body
        // Top of the page, where the card sits in WikiKT. HTML pages get an HTML table, since a
        // Markdown one wouldn't be parsed there.
        val tables = boxes.joinToString("\n\n") { if (markdown) markdownTable(it) else htmlTable(it) }
        return "$tables\n\n$body"
    }

    /**
     * One infobox as a Markdown table under a `### Template name` heading. `|` is escaped and newlines
     * become `<br>`, so a value can't break the table; section headings become a bold label row, since
     * a Markdown table has no way to span one.
     */
    private fun markdownTable(box: InfoboxService.PlainInfobox): String = buildString {
        append("### ").append(cell(box.templateName)).append("\n\n| Field | Value |\n| --- | --- |")
        for (section in box.sections) {
            section.heading?.let { append("\n| **").append(cell(it)).append("** | |") }
            for (row in section.rows) append("\n| ").append(cell(row.label)).append(" | ").append(cell(row.value)).append(" |")
        }
    }

    /** One infobox as an HTML table, for a page whose body is HTML. */
    private fun htmlTable(box: InfoboxService.PlainInfobox): String = buildString {
        append("<table><caption>").append(escapeHtml(box.templateName)).append("</caption><tbody>")
        for (section in box.sections) {
            section.heading?.let { append("<tr><th colspan=\"2\">").append(escapeHtml(it)).append("</th></tr>") }
            for (row in section.rows) {
                append("<tr><th scope=\"row\">").append(escapeHtml(row.label))
                append("</th><td>").append(escapeHtml(row.value)).append("</td></tr>")
            }
        }
        append("</tbody></table>")
    }

    /** Makes [raw] safe inside a Markdown table cell: no bare pipes, no line breaks. */
    private fun cell(raw: String): String =
        raw.replace("|", "\\|").replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>").trim()

    // --- Body rewrites ---

    /** The asset a local [url] resolves to for a page in [pageLocale], applying WikiKT's default-locale
     *  fallback — i.e. the file the wiki would actually serve. Null for anything that isn't an asset. */
    private fun assetFor(url: String, pageLocale: String, byRef: Map<AssetRef, AssetRecord>): AssetRecord? {
        val ref = assets.resolveLocalAssetUrl(url, pageLocale) ?: return null
        return byRef[ref] ?: byRef[AssetRef(defaultLocale, ref.path)]
    }

    /** Rewrites every local asset URL to the explicit `/{locale}/{path}` the archive stores it at.
     *  URLs that don't name a known asset (page links, external URLs, anchors) are left untouched. */
    private fun rewriteAssetUrls(text: String, pageLocale: String, byRef: Map<AssetRef, AssetRecord>): String =
        replaceGroups(text, listOf(MARKDOWN_URL, HTML_URL), group = 1) { url ->
            val asset = assetFor(url, pageLocale, byRef) ?: return@replaceGroups null
            val target = "/${asset.locale}/${asset.path}"
            // Keep any ?query/#fragment the author wrote, and don't churn URLs that already match.
            val suffix = url.dropWhile { it != '?' && it != '#' }
            (target + suffix).takeIf { it != url }
        }

    /** Replaces the `{alt}` sentinel with the resolved asset's stored alt text (empty when it has none),
     *  in both Markdown image syntax and raw `<img>` tags. */
    private fun resolveAltTokens(text: String, pageLocale: String, byRef: Map<AssetRef, AssetRecord>): String {
        if (!text.contains(DEFAULT_ALT_TOKEN)) return text
        val md = MARKDOWN_ALT.replace(text) { match ->
            val (lead, url) = match.destructured
            val alt = assetFor(url, pageLocale, byRef)?.altText.orEmpty()
            "![${escapeMarkdownAlt(alt)}]($lead$url"
        }
        return IMG_TAG.replace(md) { match ->
            val tag = match.value
            if (!tag.contains(DEFAULT_ALT_TOKEN)) return@replace tag
            val src = HTML_URL.find(tag)?.groupValues?.get(1) ?: return@replace tag
            val alt = assetFor(src, pageLocale, byRef)?.altText.orEmpty()
            IMG_ALT_TOKEN.replace(tag) { m -> "${m.groupValues[1]}${escapeHtmlAttr(alt)}${m.groupValues[2]}" }
        }
    }

    /**
     * Applies [transform] to capture [group] of every match of [patterns], right-to-left so earlier
     * offsets stay valid. Overlapping matches (the same URL seen by two patterns) are applied once.
     * A null from [transform] leaves that occurrence alone.
     */
    private fun replaceGroups(
        text: String,
        patterns: List<Regex>,
        group: Int,
        transform: (String) -> String?,
    ): String {
        val edits = patterns
            .flatMap { it.findAll(text) }
            .mapNotNull { m -> m.groups[group]?.let { g -> transform(g.value)?.let { g.range to it } } }
            .distinctBy { it.first.first }
            .sortedByDescending { it.first.first }
        if (edits.isEmpty()) return text
        val out = StringBuilder(text)
        var floor = text.length // start of the last edit applied; skip anything overlapping it
        for ((range, replacement) in edits) {
            if (range.last >= floor) continue
            out.replace(range.first, range.last + 1, replacement)
            floor = range.first
        }
        return out.toString()
    }

    private fun escapeMarkdownAlt(alt: String): String =
        alt.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("\n", " ").trim()

    private fun escapeHtmlAttr(value: String): String =
        value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace("\n", " ").trim()

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        /** The sentinel an author puts in the alt position to request the asset's default alt text
         *  (`com.wikikt.routing.DEFAULT_ALT_TOKEN`, duplicated here to keep the service routing-free). */
        private const val DEFAULT_ALT_TOKEN = "{alt}"

        // Same URL shapes AssetService scans for, so the export rewrites exactly what the wiki counts
        // as an asset reference.
        private val MARKDOWN_URL = Regex("!?\\[[^\\]]*]\\(\\s*<?([^)\\s>]+)")
        private val HTML_URL = Regex("(?:src|href)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

        // `![{alt}](url` — group 1 is everything up to and including the opening paren, group 2 the URL.
        private val MARKDOWN_ALT = Regex("!\\[\\{alt}]\\((\\s*<?)([^)\\s>]+)")
        private val IMG_TAG = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val IMG_ALT_TOKEN = Regex("(alt\\s*=\\s*[\"'])\\{alt}([\"'])", RegexOption.IGNORE_CASE)

        // Mirrors IconShortcodePostProcessor's pattern.
        private val ICON_SHORTCODE = Regex(":mdi-([a-z0-9]+(?:-[a-z0-9]+)*):")
    }
}
