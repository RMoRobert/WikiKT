package com.wikikt.service

import com.wikikt.db.FragmentsTable
import com.wikikt.model.FragmentRecord
import com.wikikt.model.nowMillis
import com.wikikt.model.toFragmentRecord
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

class FragmentService(private val database: R2dbcDatabase) {
    /**
     * Invoked after a fragment is created/updated/deleted with the fragment keys affected by the
     * edit (on a rename, both the old and new key), so the search index can rebuild only the pages
     * that transclude them. Late-bound by [com.wikikt.AppContext] to SearchIndexService (avoids a
     * FragmentService↔PageService cycle). Wrapped defensively so it can't fail the write.
     */
    var onFragmentsChanged: (suspend (UInt, Set<String>) -> Unit)? = null

    private suspend fun notifyFragmentsChanged(siteId: UInt, keys: Set<String>) {
        val cb = onFragmentsChanged ?: return
        runCatching { cb(siteId, keys) }
    }

    suspend fun list(siteId: UInt): List<FragmentRecord> = suspendTransaction(database) {
        FragmentsTable.selectAll().where { FragmentsTable.siteId eq siteId }
            .map { it.toFragmentRecord() }.toList().sortedWith(compareBy({ it.key }, { it.locale }))
    }

    suspend fun findById(id: UInt): FragmentRecord? = suspendTransaction(database) {
        FragmentsTable.selectAll().where { FragmentsTable.id eq id }.map { it.toFragmentRecord() }.singleOrNull()
    }

    suspend fun create(siteId: UInt, locale: String, key: String, title: String, content: String, updatedBy: UInt?): FragmentRecord {
        val fragment = suspendTransaction(database) {
            val now = nowMillis()
            val id = FragmentsTable.insert {
                it[FragmentsTable.siteId] = siteId
                it[FragmentsTable.locale] = locale
                it[FragmentsTable.key] = key
                it[FragmentsTable.title] = title
                it[FragmentsTable.content] = content
                it[createdAt] = now
                it[updatedAt] = now
                it[FragmentsTable.updatedBy] = updatedBy
            }[FragmentsTable.id].value
            FragmentsTable.selectAll().where { FragmentsTable.id eq id }.map { it.toFragmentRecord() }.singleOrNull()!!
        }
        notifyFragmentsChanged(fragment.siteId, setOf(fragment.key))
        return fragment
    }

    suspend fun update(id: UInt, locale: String, key: String, title: String, content: String, updatedBy: UInt?): FragmentRecord? {
        var oldKey: String? = null
        var siteId: UInt? = null
        val updated = suspendTransaction(database) {
            val existing = FragmentsTable.selectAll().where { FragmentsTable.id eq id }
                .map { it.toFragmentRecord() }.singleOrNull() ?: return@suspendTransaction null
            oldKey = existing.key
            siteId = existing.siteId
            FragmentsTable.update({ FragmentsTable.id eq id }) {
                it[FragmentsTable.locale] = locale
                it[FragmentsTable.key] = key
                it[FragmentsTable.title] = title
                it[FragmentsTable.content] = content
                it[updatedAt] = nowMillis()
                it[FragmentsTable.updatedBy] = updatedBy
            }
            FragmentsTable.selectAll().where { FragmentsTable.id eq id }.map { it.toFragmentRecord() }.singleOrNull()
        }
        // On a rename the old key's referencing pages also change (their {{fragment:oldKey}} now misses).
        if (updated != null) notifyFragmentsChanged(siteId!!, setOfNotNull(oldKey, key))
        return updated
    }

    suspend fun delete(id: UInt): Boolean {
        var deletedKey: String? = null
        var siteId: UInt? = null
        val ok = suspendTransaction(database) {
            FragmentsTable.selectAll().where { FragmentsTable.id eq id }
                .map { it.toFragmentRecord() }.singleOrNull()?.let { deletedKey = it.key; siteId = it.siteId }
            FragmentsTable.deleteWhere { FragmentsTable.id eq id } > 0
        }
        if (ok && deletedKey != null) notifyFragmentsChanged(siteId!!, setOf(deletedKey!!))
        return ok
    }

    /**
     * Expands `{{fragment:key}}` references in Markdown [source]. Resolution prefers the page's
     * [locale], falling back to [defaultLocale]; unknown keys render a visible marker. References
     * inside code spans/blocks are left untouched, and recursion is bounded with cycle + depth
     * guards. The result is plain Markdown to be rendered + sanitized by the normal pipeline.
     */
    suspend fun expand(siteId: UInt, source: String, locale: String, defaultLocale: String): String {
        if (!source.contains(REFERENCE_PREFIX)) return source
        val byKey = list(siteId).associateBy { it.locale + KEY_SEP + it.key }
        return expand(source, locale, defaultLocale, byKey, emptySet(), 0)
    }

    /**
     * The set of fragment keys [source] references via `{{fragment:key}}`, ignoring references inside
     * code spans/blocks (same masking as [expand]). Used to compute which pages use a fragment.
     */
    fun referencedKeys(source: String): Set<String> {
        if (!source.contains(REFERENCE_PREFIX)) return emptySet()
        return REFERENCE.findAll(ContentMasking.maskedText(source)).map { it.groupValues[1] }.toSet()
    }

    private fun expand(
        source: String,
        locale: String,
        defaultLocale: String,
        byKey: Map<String, FragmentRecord>,
        visiting: Set<String>,
        depth: Int,
    ): String {
        if (depth >= MAX_DEPTH || !source.contains(REFERENCE_PREFIX)) return source
        val (masked, codeSpans) = ContentMasking.mask(source)
        val expanded = REFERENCE.replace(masked) { match ->
            val key = match.groupValues[1]
            if (key in visiting) return@replace "[fragment cycle: $key]"
            val fragment = byKey["$locale$KEY_SEP$key"] ?: byKey["$defaultLocale$KEY_SEP$key"]
            if (fragment == null) {
                "[missing fragment: $key]"
            } else {
                expand(fragment.content, locale, defaultLocale, byKey, visiting + key, depth + 1)
            }
        }
        return ContentMasking.restore(expanded, codeSpans)
    }

    companion object {
        private const val MAX_DEPTH = 10
        private const val KEY_SEP = " "
        private const val REFERENCE_PREFIX = "{{fragment:"
        private val REFERENCE = Regex("\\{\\{fragment:([a-zA-Z0-9._/-]+)}}")
    }
}
