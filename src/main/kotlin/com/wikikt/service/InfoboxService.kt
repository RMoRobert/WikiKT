package com.wikikt.service

import com.wikikt.db.ContentFormat
import com.wikikt.db.InfoboxPathRulesTable
import com.wikikt.db.InfoboxTemplatesTable
import com.wikikt.markdown.MarkdownRenderer
import com.wikikt.model.InfoboxFieldDef
import com.wikikt.model.InfoboxTemplate
import com.wikikt.model.nowMillis
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * Infoboxes: admin-defined [templates][InfoboxTemplate] (a named, ordered list of fields, optionally
 * broken up by section headings — see [InfoboxFieldDef]) are bound to pages by rules
 * ([InfoboxPathRulesTable], matched by path or tag). A page can match more than one rule/template at
 * once. Every infobox is always optional: a page fills in whichever fields it wants (stored as a JSON
 * object in `pages.infobox`, keyed by template slug); a template with nothing filled in simply doesn't
 * render. This service resolves the templates matched for a page and renders its data to the infobox
 * card HTML.
 *
 * Field values may carry inline Markdown (bold/italic/links); [renderCard] runs each through the
 * Markdown pipeline and unwraps the block `<p>` so only inline markup survives — matching the
 * "metadata, not prose" intent. Rendering is defensive: malformed JSON yields no card, never an error,
 * so a bad value can't break the page view. Redlink tagging is applied by the caller
 * ([PageRenderService]) so it stays in one place.
 */
class InfoboxService(
    private val database: R2dbcDatabase,
    private val markdown: MarkdownRenderer,
    private val settings: SettingsService,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // --- Template / rule resolution ---

    /** A rule row for admin listing. */
    data class RuleRow(val id: UInt, val matchType: String, val pattern: String, val templateId: UInt)

    private data class Rule(
        val id: UInt, val matchType: String, val pattern: String,
        val templateId: UInt, val position: Int,
    )

    private suspend fun rulesFor(siteId: UInt): List<Rule> = suspendTransaction(database) {
        InfoboxPathRulesTable.selectAll().where { InfoboxPathRulesTable.siteId eq siteId }
            .map {
                Rule(
                    it[InfoboxPathRulesTable.id].value,
                    it[InfoboxPathRulesTable.matchType],
                    it[InfoboxPathRulesTable.pattern],
                    it[InfoboxPathRulesTable.templateId].value,
                    it[InfoboxPathRulesTable.position],
                )
            }.toList()
    }

    /**
     * Resolves EVERY distinct template bound to a page at [path] carrying [tags] — a page can match more
     * than one rule (e.g. a path rule and a tag rule pointing at different templates), and each match
     * gets its own tab/card. All are equally optional: which template(s) apply is decided here, but
     * whether anything is shown/nudged for a template depends only on whether the page has data for it
     * (see [unfilledTemplateNames]). A PATH rule matches by the path glob; a TAG rule matches when the
     * page carries the tag. Results are ordered by the most-specific path (tag rules rank below path
     * rules), then position: if the SAME template is bound by more than one matching rule, it appears once.
     */
    suspend fun resolveAllFor(siteId: UInt, path: String, tags: List<String>): List<InfoboxTemplate> {
        val rules = rulesFor(siteId)
        if (rules.none { ruleMatches(it, path, tags) }) return emptyList()
        return resolveFrom(rules, listTemplates(siteId).associateBy { it.id }, path, tags)
    }

    /**
     * [resolveAllFor] as a reusable function, with this site's rules and templates read **once**: the
     * returned lambda answers `(path, tags) -> matched templates` with no further database work. For
     * callers that ask the same question of every page on the site (the Wiki.js export) and would
     * otherwise re-read both tables per page.
     */
    suspend fun matcherFor(siteId: UInt): (String, List<String>) -> List<InfoboxTemplate> {
        val rules = rulesFor(siteId)
        val byId = listTemplates(siteId).associateBy { it.id }
        return { path, tags -> resolveFrom(rules, byId, path, tags) }
    }

    /**
     * [resolveAllFor]'s matching and ordering against rules and templates the caller already holds.
     * Split out for [usageReport], which asks the same question of every page on the site and would
     * otherwise re-read both tables per page.
     */
    private fun resolveFrom(
        rules: List<Rule>,
        templatesById: Map<UInt, InfoboxTemplate>,
        path: String,
        tags: List<String>,
    ): List<InfoboxTemplate> {
        val matching = rules.filter { ruleMatches(it, path, tags) }
        if (matching.isEmpty()) return emptyList()
        val ordered = matching.groupBy { it.templateId }.entries.sortedWith(
            compareByDescending<Map.Entry<UInt, List<Rule>>> { (_, rs) -> rs.maxOf { specificity(it) } }
                .thenBy { (_, rs) -> rs.minOf { it.position } }
                .thenBy { (templateId, _) -> templateId },
        )
        return ordered.mapNotNull { (templateId, _) -> templatesById[templateId] }
    }

    private fun ruleMatches(rule: Rule, path: String, tags: List<String>): Boolean = when (rule.matchType) {
        MATCH_TAG -> tags.any { it.equals(rule.pattern.trim(), ignoreCase = true) }
        else -> matches(rule.pattern, path)
    }

    // Path rules rank by literal-prefix length; a tag rule ranks below every path rule.
    private fun specificity(rule: Rule): Int = if (rule.matchType == MATCH_TAG) -1 else ruleBase(rule.pattern).length

    // Strips a pattern down to its literal prefix (drops a trailing single- or double-star glob suffix).
    // Used only for specificity ranking (longer prefix = more specific), never for matching itself.
    private fun ruleBase(pattern: String): String =
        pattern.trim('/').removeSuffix("/**").removeSuffix("/*")

    // Path-rule matching grammar.
    //
    // A pattern is a literal wiki path optionally ending in ONE glob suffix. A leading/trailing slash on
    // either side is ignored (pattern and path are both trim('/')-normalized first). There are exactly
    // three forms — this is a deliberately tiny GLOB, NOT a regex and NOT a raw character prefix:
    //
    //   apps/**   (double-star)  -> every DESCENDANT of apps: apps/x, apps/x/y, ...  but NOT apps itself
    //   apps/*    (single-star)  -> only DIRECT CHILDREN of apps: apps/x  (not apps/x/y, not apps itself)
    //   apps      (no glob)      -> ONLY the exact page apps
    //
    // So "everything under apps" is apps/** — apps/* is one level only, and bare apps is the single page.
    // To bind apps AND its subtree, add two rules (apps and apps/**). The stars are segment-aware: they
    // only match at a "/" boundary, so apps/** never matches a sibling like apps-legacy/x.
    //
    // This is intentionally a different, simpler model from the permission layer (AccessResolver), whose
    // Match.START is a raw character prefix and which also offers END/REGEX/EXACT/TAG. We do NOT share
    // that code: infobox binding wants readable, segment-aware globs an admin can reason about, not the
    // security layer's fuller (and regex-carrying) grammar — the two have deliberately divergent needs.
    // (Tag rules never reach here; matchType == TAG is handled in ruleMatches.)
    internal fun matches(pattern: String, path: String): Boolean {
        val p = pattern.trim('/')
        val target = path.trim('/')
        return when {
            p.endsWith("/**") -> target.startsWith(p.removeSuffix("/**") + "/")
            p.endsWith("/*") -> {
                val base = p.removeSuffix("/*")
                target.startsWith("$base/") && !target.removePrefix("$base/").contains('/')
            }
            else -> target == p
        }
    }

    suspend fun templateById(id: UInt): InfoboxTemplate? = suspendTransaction(database) {
        InfoboxTemplatesTable.selectAll().where { InfoboxTemplatesTable.id eq id }
            .map { it.toTemplate() }.singleOrNull()
    }

    suspend fun templateBySlug(siteId: UInt, slug: String): InfoboxTemplate? = suspendTransaction(database) {
        InfoboxTemplatesTable.selectAll()
            .where { (InfoboxTemplatesTable.siteId eq siteId) and (InfoboxTemplatesTable.slug eq slug) }
            .map { it.toTemplate() }.singleOrNull()
    }

    suspend fun listTemplates(siteId: UInt): List<InfoboxTemplate> = suspendTransaction(database) {
        InfoboxTemplatesTable.selectAll().where { InfoboxTemplatesTable.siteId eq siteId }
            .map { it.toTemplate() }.toList().sortedBy { it.name.lowercase() }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toTemplate(): InfoboxTemplate = InfoboxTemplate(
        id = this[InfoboxTemplatesTable.id].value,
        siteId = this[InfoboxTemplatesTable.siteId].value,
        slug = this[InfoboxTemplatesTable.slug],
        name = this[InfoboxTemplatesTable.name],
        description = this[InfoboxTemplatesTable.description],
        fields = runCatching { json.decodeFromString<List<InfoboxFieldDef>>(this[InfoboxTemplatesTable.fieldsJson]) }
            .getOrDefault(emptyList()),
    )

    suspend fun createTemplate(
        siteId: UInt,
        slug: String,
        name: String,
        description: String?,
        fields: List<InfoboxFieldDef>,
    ): UInt = suspendTransaction(database) {
        val now = nowMillis()
        InfoboxTemplatesTable.insert {
            it[InfoboxTemplatesTable.siteId] = siteId
            it[InfoboxTemplatesTable.slug] = slug
            it[InfoboxTemplatesTable.name] = name
            it[InfoboxTemplatesTable.description] = description
            it[InfoboxTemplatesTable.fieldsJson] = json.encodeToString(fields)
            it[createdAt] = now
            it[updatedAt] = now
        }[InfoboxTemplatesTable.id].value
    }

    suspend fun updateTemplate(
        id: UInt,
        slug: String,
        name: String,
        description: String?,
        fields: List<InfoboxFieldDef>,
    ) {
        val siteId = templateById(id)?.siteId
        suspendTransaction(database) {
            InfoboxTemplatesTable.update({ InfoboxTemplatesTable.id eq id }) {
                it[InfoboxTemplatesTable.slug] = slug
                it[InfoboxTemplatesTable.name] = name
                it[InfoboxTemplatesTable.description] = description
                it[InfoboxTemplatesTable.fieldsJson] = json.encodeToString(fields)
                it[updatedAt] = nowMillis()
            }
        }
        siteId?.let { invalidateRenders(it) }
    }

    /** Deletes a template and its path rules (pages keep their now-orphaned infobox data, unrendered). */
    suspend fun deleteTemplate(id: UInt) {
        val siteId = templateById(id)?.siteId
        suspendTransaction(database) {
            InfoboxPathRulesTable.deleteWhere { InfoboxPathRulesTable.templateId eq id }
            InfoboxTemplatesTable.deleteWhere { InfoboxTemplatesTable.id eq id }
        }
        siteId?.let { invalidateRenders(it) }
    }

    suspend fun deletePathRule(id: UInt) {
        val siteId = suspendTransaction(database) {
            InfoboxPathRulesTable.selectAll().where { InfoboxPathRulesTable.id eq id }
                .map { it[InfoboxPathRulesTable.siteId].value }.singleOrNull()
        }
        suspendTransaction(database) {
            InfoboxPathRulesTable.deleteWhere { InfoboxPathRulesTable.id eq id }
        }
        siteId?.let { invalidateRenders(it) }
    }

    /**
     * Bumps the render epoch, marking every cached page render stale (see [PageRenderService]). Template
     * and rule edits change the infobox HTML baked into that cache — labels, help text, field order, or
     * whether a template applies to the page at all — but touch no page's `updatedAt`, which is the only
     * other thing the cache keys on. Without this an admin's edit would reach a page only the next time
     * someone saved it. Creating a template needs no bump: nothing renders it until a rule points at it.
     */
    private suspend fun invalidateRenders(siteId: UInt) {
        settings.bumpRenderEpoch(siteId)
    }

    suspend fun createPathRule(
        siteId: UInt,
        pattern: String,
        templateId: UInt,
        matchType: String = MATCH_PATH,
        position: Int = 0,
    ) {
        // A path pattern is slash-trimmed; a tag is trimmed + lowercased (tags are stored lowercased).
        val normalized = if (matchType == MATCH_TAG) pattern.trim().lowercase() else pattern.trim('/')
        suspendTransaction(database) {
            InfoboxPathRulesTable.insert {
                it[InfoboxPathRulesTable.siteId] = siteId
                it[InfoboxPathRulesTable.matchType] = matchType
                it[InfoboxPathRulesTable.pattern] = normalized
                it[InfoboxPathRulesTable.templateId] = templateId
                it[InfoboxPathRulesTable.position] = position
            }
        }
        invalidateRenders(siteId)
    }

    suspend fun listPathRules(siteId: UInt): List<RuleRow> = suspendTransaction(database) {
        InfoboxPathRulesTable.selectAll().where { InfoboxPathRulesTable.siteId eq siteId }
            .map {
                RuleRow(
                    it[InfoboxPathRulesTable.id].value,
                    it[InfoboxPathRulesTable.matchType],
                    it[InfoboxPathRulesTable.pattern],
                    it[InfoboxPathRulesTable.templateId].value,
                )
            }.toList()
    }

    // --- Rendering ---

    /**
     * Splits the page's stored infobox JSON into each matched template's own field data, keyed by
     * template id. Storage is keyed by template SLUG at the top level — `{"app_info": {...}, "person":
     * {...}}` — so multiple templates on one page never collide. Only keys that are a currently-matched
     * template's slug are read; any other top-level data — a template no longer applied here, or a page
     * saved before multi-template support (a flat, un-nested object) — is ignored and simply doesn't
     * render. [hasOrphanedData] surfaces such leftover data to editors, and saving (via
     * [com.wikikt.routing.infoboxFromParams]) rewrites the keyed shape from the matched templates only.
     */
    private fun perTemplateData(infoboxJson: String?, matches: List<InfoboxTemplate>): Map<UInt, JsonObject> {
        if (infoboxJson.isNullOrBlank() || matches.isEmpty()) return emptyMap()
        val obj = runCatching { json.parseToJsonElement(infoboxJson) as? JsonObject }.getOrNull() ?: return emptyMap()
        val bySlug = matches.associateBy { it.slug }
        return obj.mapNotNull { (slug, value) -> bySlug[slug]?.let { it.id to (value as? JsonObject) } }
            .mapNotNull { (id, value) -> value?.let { id to it } }
            .toMap()
    }

    /**
     * Names of templates matched at [path]/[tags] that still have no data in [infoboxJson] — every
     * infobox is always optional (never required to save), but this drives the editor-only "you could
     * fill this in" note (page banner, Page Info's General tab, and the per-tab marker) so an editor
     * knows a page is eligible for an infobox it hasn't filled in yet. Empty when nothing matches, or
     * everything matched already has data.
     */
    suspend fun unfilledTemplateNames(siteId: UInt, path: String, tags: List<String>, infoboxJson: String?): List<String> {
        val matches = resolveAllFor(siteId, path, tags)
        if (matches.isEmpty()) return emptyList()
        val data = perTemplateData(infoboxJson, matches)
        return matches.filter { (data[it.id] ?: JsonObject(emptyMap())).isEmpty() }.map { it.name }
    }

    /**
     * True when the page's stored infobox JSON holds data under top-level keys that are NOT the slug of
     * any template currently matched at [path]/[tags] — data that no longer renders anywhere. Arises when
     * a template was deleted, its path/tag rule changed, the page moved, or the data predates
     * multi-template support (a flat, un-nested object). Drives an editor-only "leftover data you can
     * clear" note. Saving while at least one template still matches rewrites the JSON from the matched
     * templates only, dropping the orphaned keys (see [com.wikikt.routing.infoboxFromParams]); when
     * nothing matches, the data is left untouched, so the note is the only signal it's still there.
     */
    suspend fun hasOrphanedData(siteId: UInt, path: String, tags: List<String>, infoboxJson: String?): Boolean {
        if (infoboxJson.isNullOrBlank()) return false
        val obj = runCatching { json.parseToJsonElement(infoboxJson) as? JsonObject }.getOrNull() ?: return false
        if (obj.isEmpty()) return false
        val matchedSlugs = resolveAllFor(siteId, path, tags).map { it.slug }.toSet()
        return obj.any { (slug, value) -> slug !in matchedSlugs && !value.isEmptyContent() }
    }

    // --- Usage report ---

    /** How one page stands against one template it matches. [missingRequired] holds field LABELS. */
    data class PageUsage(
        val locale: String,
        val path: String,
        val title: String,
        val templateSlug: String,
        val templateName: String,
        val filledFields: Int,
        val totalFields: Int,
        val missingRequired: List<String>,
    ) {
        /** Matched but not filled in at all — the page shows no card for this template. */
        val isUnfilled: Boolean get() = filledFields == 0

        /** Filled in, but a field the template marks required was left blank. */
        val isIncomplete: Boolean get() = !isUnfilled && missingRequired.isNotEmpty()
    }

    /** A page carrying infobox data under a key no template applied there claims. */
    data class OrphanUsage(val locale: String, val path: String, val title: String, val keys: List<String>)

    /** Per-template totals for the report's summary table. */
    data class TemplateUsage(
        val slug: String,
        val name: String,
        val ruleCount: Int,
        val matched: Int,
        val filled: Int,
        val unfilled: Int,
        val incomplete: Int,
    )

    data class UsageReport(
        val templates: List<TemplateUsage>,
        val pages: List<PageUsage>,
        val orphans: List<OrphanUsage>,
    )

    /**
     * Which pages use each infobox, which are eligible and haven't filled one in, and which filled one
     * in but left a required field blank — the questions the per-page editor nudges can't answer,
     * because each page only ever sees itself. Backs the admin usage report.
     *
     * [pages] is passed in rather than looked up so this service stays independent of PageService; the
     * caller supplies the site's pages (each needing path/tags/infobox). Rules and templates are read
     * once and matched in memory, so the cost is one pass over the pages, not a query per page.
     *
     * "Required" is advisory throughout WikiKT — an infobox is never enforced at save time (see
     * [unfilledTemplateNames]) — so a missing required field is reported here, not prevented there.
     */
    suspend fun usageReport(siteId: UInt, pages: List<com.wikikt.model.PageRecord>): UsageReport {
        val rules = rulesFor(siteId)
        val templates = listTemplates(siteId)
        val byId = templates.associateBy { it.id }
        val ruleCounts = rules.groupingBy { it.templateId }.eachCount()

        val usages = mutableListOf<PageUsage>()
        val orphans = mutableListOf<OrphanUsage>()
        for (page in pages) {
            val matched = resolveFrom(rules, byId, page.path, page.tags)
            val stored = page.infobox?.takeIf { it.isNotBlank() }
                ?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val perTemplate = matched.associate { it.slug to (stored?.get(it.slug) as? JsonObject) }
            for (template in matched) {
                val data = perTemplate[template.slug] ?: JsonObject(emptyMap())
                // Headings hold nothing, so they're neither filled nor fillable: counting them would
                // make a fully completed page read as "4 of 6 fields".
                val fields = template.fields.filter { it.isValueField }
                val filled = fields.count { !(data[it.name] ?: JsonNull).isEmptyContent() }
                usages += PageUsage(
                    locale = page.locale,
                    path = page.path,
                    title = page.title,
                    templateSlug = template.slug,
                    templateName = template.name,
                    filledFields = filled,
                    totalFields = fields.size,
                    missingRequired = fields
                        .filter { it.required && (data[it.name] ?: JsonNull).isEmptyContent() }
                        .map { it.label },
                )
            }
            val matchedSlugs = matched.map { it.slug }.toSet()
            val leftover = stored.orEmpty().filter { (slug, value) -> slug !in matchedSlugs && !value.isEmptyContent() }
            if (leftover.isNotEmpty()) {
                orphans += OrphanUsage(page.locale, page.path, page.title, leftover.keys.sorted())
            }
        }

        val bySlug = usages.groupBy { it.templateSlug }
        return UsageReport(
            templates = templates.map { t ->
                val rows = bySlug[t.slug].orEmpty()
                TemplateUsage(
                    slug = t.slug,
                    name = t.name,
                    ruleCount = ruleCounts[t.id] ?: 0,
                    matched = rows.size,
                    filled = rows.count { !it.isUnfilled },
                    unfilled = rows.count { it.isUnfilled },
                    incomplete = rows.count { it.isIncomplete },
                )
            },
            pages = usages.sortedWith(compareBy({ it.locale }, { it.path }, { it.templateName })),
            orphans = orphans.sortedWith(compareBy({ it.locale }, { it.path })),
        )
    }

    /** True when a JSON value carries no meaningful content: null, a blank string, or an empty object/array. */
    private fun JsonElement.isEmptyContent(): Boolean = when (this) {
        is JsonNull -> true
        is JsonObject -> isEmpty()
        is JsonArray -> isEmpty()
        is JsonPrimitive -> content.isBlank()
    }

    /**
     * Renders every matched template that has data as its own complete infobox card (a titled
     * definition list, wrapped in its own `<aside>`), concatenated — or null when no template applies,
     * there's no data, or nothing in it renders to anything. A template with no data simply contributes
     * no card — every infobox is optional, so an editor opting out is just leaving it blank. Field values
     * are rendered as inline Markdown. Redlink tagging is applied by the caller ([PageRenderService]) so
     * it stays in one place. Never throws: malformed data simply yields no card for that template.
     */
    suspend fun renderCard(siteId: UInt, path: String, tags: List<String>, infoboxJson: String?): String? {
        if (infoboxJson.isNullOrBlank()) return null
        val matches = resolveAllFor(siteId, path, tags)
        if (matches.isEmpty()) return null
        val perTemplate = perTemplateData(infoboxJson, matches)
        val options = settings.renderOptions(siteId)
        val cards = matches.mapNotNull { renderOneCard(it, perTemplate[it.id], options) }
        return cards.takeIf { it.isNotEmpty() }?.joinToString("")
    }

    /** A page's filled-in data for one template, flattened for a plain two-column rendering.
     *  [sections] mirrors the card's: the first has a null heading, each `# Heading` field starts
     *  another, and a section with nothing filled in is left out. */
    data class PlainInfobox(val templateName: String, val sections: List<PlainSection>)

    data class PlainSection(val heading: String?, val rows: List<PlainRow>)

    /** One label/value pair. [value] is the stored text — it may carry inline Markdown, which is what
     *  an infobox field holds anyway; booleans arrive as `Yes`/`No` and multi-values comma-joined. */
    data class PlainRow(val label: String, val value: String)

    /**
     * The same data [renderCard] draws, as plain label/value pairs instead of HTML — one entry per
     * template in [templates] (resolve them once with [matcherFor]) that the page has data for.
     * Empty when nothing is filled in.
     *
     * Written for the Wiki.js export, where infoboxes have no equivalent, so the caller can fold them
     * into the page body as an ordinary table rather than drop them. Never throws: malformed data
     * simply yields no entry for that template.
     */
    fun plainInfoboxes(templates: List<InfoboxTemplate>, infoboxJson: String?): List<PlainInfobox> {
        if (infoboxJson.isNullOrBlank() || templates.isEmpty()) return emptyList()
        val perTemplate = perTemplateData(infoboxJson, templates)
        return templates.mapNotNull { template ->
            val data = perTemplate[template.id] ?: return@mapNotNull null
            val sections = mutableListOf<Pair<String?, MutableList<PlainRow>>>(null to mutableListOf())
            for (field in template.fields) {
                if (field.isHeading) {
                    sections.add(field.label to mutableListOf())
                    continue
                }
                val value = data[field.name] ?: continue
                val plain = plainValue(field, value) ?: continue
                sections.last().second.add(PlainRow(field.label, plain))
            }
            val filled = sections.filter { it.second.isNotEmpty() }.map { PlainSection(it.first, it.second) }
            if (filled.isEmpty()) null else PlainInfobox(template.name, filled)
        }
    }

    /** One infobox value as plain text, or null when it holds nothing. */
    private fun plainValue(field: InfoboxFieldDef, value: JsonElement): String? = when (field.type.lowercase()) {
        "boolean" -> when ((value as? JsonPrimitive)?.booleanOrNull) {
            true -> "Yes"
            false -> "No"
            null -> null
        }
        "multi" -> (value as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
            ?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        else -> (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

    /** A run of rendered rows under an optional heading — the unit [renderOneCard] emits. */
    private class Section(val heading: String?) {
        val rows = StringBuilder()
    }

    /**
     * One template's complete `<aside>` card, or null if [data] is null/has nothing that renders.
     *
     * The card is a sequence of sections: any fields before the first heading form an unheaded one,
     * and each `# Heading` in the template starts another. A section whose fields are all blank is
     * dropped entirely, heading and all — so a heading never appears over nothing, and a template that
     * uses no headings renders exactly the single unheaded `<dl>` it always did.
     *
     * Each section is its own `<dl>` rather than one list with headings interleaved: a `<dl>` may only
     * contain `<dt>`/`<dd>` (optionally in wrapper `<div>`s), so a heading between rows would have no
     * valid home inside it.
     */
    private fun renderOneCard(template: InfoboxTemplate, data: JsonObject?, options: com.wikikt.markdown.RenderOptions): String? {
        if (data == null) return null
        val sections = mutableListOf(Section(heading = null))
        for (field in template.fields) {
            if (field.isHeading) {
                sections.add(Section(heading = field.label))
                continue
            }
            val value = data[field.name] ?: continue
            val cell = renderValue(field, value, options) ?: continue
            sections.last().rows
                .append("<div class=\"wk-infobox-row\"><dt>")
                .append(labelHtml(field))
                .append("</dt><dd>")
                .append(cell)
                .append("</dd></div>")
        }
        val filled = sections.filter { it.rows.isNotEmpty() }
        if (filled.isEmpty()) return null
        return buildString {
            append("<aside class=\"page-card wk-infobox\" aria-label=\"Page information\">")
            append("<p class=\"page-toc-title wk-infobox-title\">").append(escape(template.name)).append("</p>")
            for (section in filled) {
                section.heading?.let {
                    append("<p class=\"wk-infobox-section\">").append(escape(it)).append("</p>")
                }
                append("<dl class=\"wk-infobox-list\">").append(section.rows).append("</dl>")
            }
            append("</aside>")
        }
    }

    /**
     * A field's `<dt>` content. Without help text that's just the escaped label, exactly as before; a
     * field the template gave help text becomes a button that reveals that text — so a *reader* can
     * find out what a label means, not just the editor filling it in. One `help` string serves both
     * audiences (the editor form shows it under the input, this shows it on the card), so an admin
     * writes the explanation once.
     *
     * The popup itself is a Bootstrap popover, wired up in page-view.js from these data attributes:
     * hover on a pointer, tap or keyboard focus everywhere else. `title` carries the same text as a
     * plain-HTML fallback — Bootstrap consumes and removes the attribute when it initializes the
     * popover, so it only ever surfaces (as a native tooltip) if the script never runs.
     */
    private fun labelHtml(field: InfoboxFieldDef): String {
        val label = escape(field.label)
        val help = field.help?.trim()?.takeIf { it.isNotEmpty() }?.let { escape(it) } ?: return label
        return buildString {
            append("<button type=\"button\" class=\"wk-infobox-help\" data-bs-toggle=\"popover\"")
            append(" data-bs-trigger=\"hover focus\" data-bs-placement=\"top\"")
            append(" data-bs-custom-class=\"wk-infobox-popover\"")
            append(" data-bs-title=\"").append(label).append("\"")
            append(" data-bs-content=\"").append(help).append("\"")
            append(" title=\"").append(help).append("\">")
            append("<span class=\"wk-infobox-help-label\">").append(label).append("</span>")
            append("</button>")
        }
    }

    /**
     * The editor form model: one entry per template matched for [path]/[tags] (empty list if none), each
     * with the template's name/slug, an `unfilled` flag (true when this specific template has no data
     * yet — every infobox is optional, this only drives the "you could fill this in" note), and a
     * Mustache-ready list of fields with type flags, current value decoded from [currentJson], and (for
     * enum/multi) its options with the current selection marked. Input names are
     * `infobox.<slug>.<field>` — namespaced by template so two templates' same-named fields never
     * collide — collected back by [com.wikikt.routing.infoboxFromParams] on save.
     */
    suspend fun formFor(siteId: UInt, path: String, tags: List<String>, currentJson: String?): List<Map<String, Any?>> {
        val matches = resolveAllFor(siteId, path, tags)
        if (matches.isEmpty()) return emptyList()
        val perTemplate = perTemplateData(currentJson, matches)
        return matches.map { template ->
            val data = perTemplate[template.id] ?: JsonObject(emptyMap())
            val fields = template.fields.map { f -> fieldModel(template.slug, f, data[f.name]) }
            mapOf(
                "tabId" to template.slug,
                "templateName" to template.name,
                "fields" to fields,
                "unfilled" to data.isEmpty(),
            )
        }
    }

    /** One field's editor-form model (input name, current value, and enum/multi options if any). */
    private fun fieldModel(templateSlug: String, f: InfoboxFieldDef, el: JsonElement?): Map<String, Any?> {
        val type = f.type.lowercase()
        // A heading has no input of any kind — the form just prints it above the fields it groups.
        if (f.isHeading) return mapOf("label" to f.label, "isHeading" to true)
        val curStr = (el as? JsonPrimitive)?.contentOrNull ?: ""
        // Booleans are tri-state in the editor: unset ("") / true / false, so a field can be left blank.
        val curBool = when ((el as? JsonPrimitive)?.booleanOrNull) {
            true -> "true"
            false -> "false"
            null -> ""
        }
        val curArr = (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()
        return mapOf<String, Any?>(
            "label" to f.label,
            "help" to f.help,
            "hasHelp" to !f.help.isNullOrBlank(),
            "inputName" to "infobox.$templateSlug.${f.name}",
            "isString" to (type == "string"),
            // Select and boolean both render as a dropdown (a boolean is a fixed —/Yes/No choice).
            "isChoice" to (type == "enum" || type == "boolean"),
            "isMulti" to (type == "multi"),
            "value" to curStr,
            "options" to when (type) {
                "enum" -> listOf(mapOf("value" to "", "label" to "—", "selected" to curStr.isEmpty())) +
                    f.options.map { o -> mapOf("value" to o, "label" to o, "selected" to (o == curStr)) }
                "boolean" -> listOf(
                    mapOf("value" to "", "label" to "—", "selected" to curBool.isEmpty()),
                    mapOf("value" to "true", "label" to "Yes", "selected" to (curBool == "true")),
                    mapOf("value" to "false", "label" to "No", "selected" to (curBool == "false")),
                )
                "multi" -> f.options.map { o -> mapOf("value" to o, "label" to o, "selected" to (o in curArr)) }
                else -> emptyList()
            },
        )
    }

    /** Renders one field's value to an HTML cell, or null if it carries no displayable content. */
    private fun renderValue(field: InfoboxFieldDef, value: kotlinx.serialization.json.JsonElement, options: com.wikikt.markdown.RenderOptions): String? {
        if (value is JsonNull) return null
        return when (field.type.lowercase()) {
            "boolean" -> when ((value as? JsonPrimitive)?.booleanOrNull) {
                true -> "<span class=\"wk-infobox-bool wk-infobox-bool--yes\"><i class=\"mdi mdi-check\" aria-hidden=\"true\"></i> Yes</span>"
                false -> "<span class=\"wk-infobox-bool wk-infobox-bool--no\"><i class=\"mdi mdi-close\" aria-hidden=\"true\"></i> No</span>"
                null -> null
            }
            "multi" -> {
                val items = (value as? JsonArray)?.mapNotNull { el ->
                    (el as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { inline(it, options) }
                }.orEmpty()
                if (items.isEmpty()) null
                else items.joinToString("") { "<span class=\"wk-infobox-tag\">$it</span>" }
            }
            else -> { // string, enum, or unknown → treat as inline text
                val raw = (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
                inline(raw, options)
            }
        }
    }

    /** Renders [raw] as Markdown and unwraps a single wrapping `<p>` so only inline markup remains. */
    private fun inline(raw: String, options: com.wikikt.markdown.RenderOptions): String {
        val html = markdown.render(raw, ContentFormat.MARKDOWN, options).trim()
        val m = ONE_PARAGRAPH.matchEntire(html)
        return (m?.groupValues?.get(1) ?: html).trim()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        const val MATCH_PATH = "PATH"
        const val MATCH_TAG = "TAG"
        private val ONE_PARAGRAPH = Regex("""^<p>(.*)</p>$""", RegexOption.DOT_MATCHES_ALL)
    }
}
