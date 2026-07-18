package com.wikikt.service

import com.wikikt.db.ContentFormat
import com.wikikt.model.PageRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * The WikiJS-compatible on-file representation of a page, shared by git sync and content backups:
 * `{locale}/{path}.md` (or `.html`) with YAML front-matter carrying the DB-only metadata (title,
 * description, published, date, tags, editor, dateCreated). HTML pages carry the same block inside
 * an HTML comment, exactly as WikiJS exports them.
 */
object PageFileFormat {

    /** Relative file for a page: `{locale}/{path}.md|.html` (WikiJS locale-namespaced layout). */
    fun pageFilePath(page: PageRecord): String = "${page.locale}/${pageFileName(page)}"

    /** The locale-less part of a page's file: `{path}.md|.html`. */
    fun pageFileName(page: PageRecord): String {
        val ext = if (page.contentFormat == ContentFormat.HTML) "html" else "md"
        return "${page.path}.$ext"
    }

    /** The page's complete file content: front-matter followed by the body. */
    fun pageFileBody(page: PageRecord): String {
        val html = page.contentFormat == ContentFormat.HTML
        val meta = buildString {
            appendLine("title: ${yamlScalar(page.title)}")
            appendLine("description: ${yamlScalar(page.description.orEmpty())}")
            appendLine("published: ${page.published}")
            appendLine("date: ${isoDate(page.updatedAt)}")
            appendLine("tags: ${page.tags.joinToString(", ")}")
            appendLine("editor: ${if (html) "code" else "markdown"}")
            append("dateCreated: ${isoDate(page.createdAt)}")
            // WikiKT extension: the page's <meta name="robots"> override, when set. A short scalar; WikiJS
            // and other tools ignore unknown front-matter keys, so files stay import-compatible. (Per-page
            // custom CSS/JS is intentionally NOT emitted here — like WikiJS's scripts it's DB-only and
            // travels only in full/instance backups.)
            page.metaRobots?.takeIf { it.isNotBlank() }?.let { append("\nmetaRobots: ${yamlScalar(it)}") }
            // WikiKT extension: the page's infobox data as a nested `infobox:` mapping (block style, so
            // it reads as ordinary YAML in any tool). Omitted when the page has none.
            infoboxToYaml(page.infobox)?.let { append("\n").append(it) }
        }
        return if (html) "<!--\n$meta\n-->\n\n${page.content}" else "---\n$meta\n---\n\n${page.content}"
    }

    /** Metadata parsed from a page file's front-matter; null fields were absent from the block. */
    data class ParsedPageFile(
        val title: String?,
        val description: String?,
        val published: Boolean?,
        val tags: List<String>?,
        val content: String,
        val infobox: String? = null,
        val metaRobots: String? = null,
    )

    /**
     * Parses a page file: a YAML front-matter fence (or HTML comment block for `.html` files)
     * followed by the body. Files without front-matter import as body-only. Handles the fields
     * WikiKT round-trips (title, description, published, tags, metaRobots, infobox); `date`,
     * `dateCreated`, and `editor` are wiki-managed and ignored on import.
     */
    fun parsePageFile(raw: String, html: Boolean): ParsedPageFile {
        val open = if (html) "<!--\n" else "---\n"
        val close = if (html) "\n-->\n" else "\n---\n"
        val none = ParsedPageFile(null, null, null, null, raw)
        if (!raw.startsWith(open)) return none
        val end = raw.indexOf(close, open.length)
        if (end < 0) return none
        var body = raw.substring(end + close.length)
        if (body.startsWith("\n")) body = body.substring(1) // the blank separator line we export
        var title: String? = null
        var description: String? = null
        var published: Boolean? = null
        var tags: List<String>? = null
        var infobox: String? = null
        var metaRobots: String? = null
        val metaLines = raw.substring(open.length, end).lines()
        var i = 0
        while (i < metaLines.size) {
            val line = metaLines[i]
            // Skip indented lines at the top level — they belong to a block-style key (infobox), which
            // is consumed by its own case below.
            if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')) { i++; continue }
            val colon = line.indexOf(':')
            if (colon <= 0) { i++; continue }
            val value = line.substring(colon + 1).trim()
            when (line.substring(0, colon).trim()) {
                "title" -> title = unquote(value)
                "description" -> description = unquote(value)
                "metaRobots" -> metaRobots = unquote(value).ifBlank { null }
                "published" -> published = value.toBooleanStrictOrNull()
                // Accept both the comma-joined form we write and YAML flow style ([a, b]).
                "tags" -> tags = value.removePrefix("[").removeSuffix("]")
                    .split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
                // Block-style mapping: gather the following indented lines as the infobox's data — one
                // level deep for a single template (field: value), two levels for a page matching more
                // than one template (slug: then its own field: value lines) — see parseYamlBlock.
                "infobox" -> {
                    val sub = ArrayList<String>()
                    var j = i + 1
                    while (j < metaLines.size && metaLines[j].isNotBlank() &&
                        (metaLines[j][0] == ' ' || metaLines[j][0] == '\t')) {
                        sub.add(metaLines[j]); j++
                    }
                    val obj = parseYamlBlock(sub)
                    infobox = if (obj.isEmpty()) null else obj.toString()
                    i = j
                    continue
                }
            }
            i++
        }
        return ParsedPageFile(title, description, published, tags, body, infobox, metaRobots)
    }

    /** Reverses [yamlScalar]: strips double quotes and unescapes `\"`, `\\`, and `\n`. */
    internal fun unquote(value: String): String {
        if (value.length < 2 || !value.startsWith('"') || !value.endsWith('"')) return value
        val inner = value.substring(1, value.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                sb.append(if (inner[i + 1] == 'n') '\n' else inner[i + 1])
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    // --- Infobox front-matter (WikiKT extension) ---

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Serializes the infobox JSON object to an indented block-style `infobox:` mapping for the
     * front-matter (or null when there's no data). A nested object (a page matching more than one
     * template, keyed by template slug) recurses to a further-indented sub-block; strings are
     * YAML-quoted only when needed; arrays use flow style `[a, b]`; booleans/numbers are bare — the
     * inverse of [parseYamlBlock].
     */
    private fun infoboxToYaml(infoboxJson: String?): String? {
        if (infoboxJson.isNullOrBlank()) return null
        val obj = runCatching { json.parseToJsonElement(infoboxJson) as? JsonObject }.getOrNull() ?: return null
        if (obj.isEmpty()) return null
        return "infobox:" + yamlBlockLines(obj, indent = 1).joinToString("") { "\n$it" }
    }

    private fun yamlBlockLines(obj: JsonObject, indent: Int): List<String> {
        val pad = "  ".repeat(indent)
        return obj.entries.flatMap { (key, value) ->
            if (value is JsonObject) listOf("$pad$key:") + yamlBlockLines(value, indent + 1)
            else listOf("$pad$key: " + yamlValue(value))
        }
    }

    private fun yamlValue(value: JsonElement): String = when (value) {
        is JsonArray -> "[" + value.joinToString(", ") { scalarOf(it) } + "]"
        is JsonPrimitive -> if (value.isString) yamlScalar(value.content) else value.content
        else -> yamlScalar(value.toString())
    }

    private fun scalarOf(element: JsonElement): String {
        val p = element as? JsonPrimitive ?: return yamlScalar(element.toString())
        return if (p.isString) yamlScalar(p.content) else p.content
    }

    /**
     * Parses an already-dedented block of `key: value` YAML lines into a JSON object, recursing into
     * nested blocks: a line whose value is empty (just `key:`) introduces a further-indented sub-block,
     * which becomes a nested object — one level for a single-template page (`field: value` lines
     * directly), two for a page matching more than one template (`slug:` then its own `field: value`
     * lines). A `[a, b]` value becomes a string array, `true`/`false` a boolean, anything else a
     * (possibly quoted) string. Mirrors the editor's stored shape so a round-trip is stable.
     */
    private fun parseYamlBlock(lines: List<String>): JsonObject {
        var i = 0
        return buildJsonObject {
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) { i++; continue }
                val indent = line.length - line.trimStart().length
                val colon = line.indexOf(':')
                if (colon <= 0) { i++; continue }
                val key = line.substring(0, colon).trim()
                val raw = line.substring(colon + 1).trim()
                if (raw.isEmpty()) {
                    val sub = ArrayList<String>()
                    var j = i + 1
                    while (j < lines.size && lines[j].isNotBlank() && (lines[j].length - lines[j].trimStart().length) > indent) {
                        sub.add(lines[j]); j++
                    }
                    put(key, parseYamlBlock(sub))
                    i = j
                } else {
                    when {
                        raw.startsWith("[") && raw.endsWith("]") -> {
                            val items = raw.substring(1, raw.length - 1)
                                .split(',').map { unquote(it.trim()) }.filter { it.isNotEmpty() }
                            put(key, JsonArray(items.map { JsonPrimitive(it) }))
                        }
                        raw == "true" || raw == "false" -> put(key, raw.toBoolean())
                        else -> put(key, unquote(raw))
                    }
                    i++
                }
            }
        }
    }

    private fun isoDate(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    /** Double-quotes a YAML scalar when it could otherwise change meaning (matches js-yaml's caution). */
    internal fun yamlScalar(value: String): String {
        val safe = value.isNotEmpty() &&
            !value.first().isWhitespace() && !value.last().isWhitespace() &&
            value.none { it in "#:{}[]&*!|>'\"%@`\\\n" } &&
            !value.startsWith("- ")
        if (safe) return value
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
