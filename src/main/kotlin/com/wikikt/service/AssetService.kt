package com.wikikt.service

import com.wikikt.db.AssetRevisionsTable
import com.wikikt.db.AssetScheduledTable
import com.wikikt.db.AssetsTable
import com.wikikt.model.AssetRecord
import com.wikikt.model.AssetRef
import com.wikikt.model.AssetRevisionRecord
import com.wikikt.model.AssetScheduledRecord
import com.wikikt.model.nowMillis
import com.wikikt.model.toAssetRecord
import com.wikikt.model.toAssetRevisionRecord
import com.wikikt.model.toAssetScheduledRecord
import com.wikikt.routing.isLocaleSegment
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Metadata + on-disk storage for uploaded assets. Bytes live under [storageRoot] named by the DB id
 * (sharded), never by the user-supplied path, so the virtual path can't escape the storage dir.
 */
class AssetService(private val database: R2dbcDatabase, private val storageRoot: Path) {
    suspend fun list(siteId: UInt): List<AssetRecord> = suspendTransaction(database) {
        AssetsTable.selectAll().where { AssetsTable.siteId eq siteId }
            .map { it.toAssetRecord() }.toList().sortedWith(compareBy({ it.path }, { it.locale }))
    }

    /** Asset count per stored locale — used by admin settings to warn before disabling a used locale. */
    suspend fun countsByLocale(siteId: UInt): Map<String, Int> = suspendTransaction(database) {
        AssetsTable.selectAll().where { AssetsTable.siteId eq siteId }
            .map { it[AssetsTable.locale] }.toList().groupingBy { it }.eachCount()
    }

    suspend fun findById(id: UInt): AssetRecord? = suspendTransaction(database) {
        AssetsTable.selectAll().where { AssetsTable.id eq id }.map { it.toAssetRecord() }.singleOrNull()
    }

    suspend fun findByLocaleAndPath(siteId: UInt, locale: String, path: String): AssetRecord? = suspendTransaction(database) {
        AssetsTable.selectAll()
            .where { (AssetsTable.siteId eq siteId) and (AssetsTable.locale eq locale) and (AssetsTable.path eq path) }
            .map { it.toAssetRecord() }
            .singleOrNull()
    }

    /** Resolves an asset by (locale, path), optionally falling back to [defaultLocale] when missing. */
    suspend fun resolve(siteId: UInt, locale: String, path: String, fallback: Boolean, defaultLocale: String): AssetRecord? {
        findByLocaleAndPath(siteId, locale, path)?.let { return it }
        return if (fallback && locale != defaultLocale) findByLocaleAndPath(siteId, defaultLocale, path) else null
    }

    /** Updates the editor-facing metadata (default alt text + description). Blank values clear the field. */
    suspend fun updateMeta(id: UInt, altText: String?, description: String?): Unit = suspendTransaction(database) {
        AssetsTable.update({ AssetsTable.id eq id }) {
            it[AssetsTable.altText] = altText?.trim()?.ifBlank { null }
            it[AssetsTable.description] = description?.trim()?.ifBlank { null }
        }
        Unit
    }

    /** Persists a validated upload: insert row, then move the temp file into place (before commit). */
    suspend fun create(
        siteId: UInt,
        locale: String,
        path: String,
        originalFilename: String,
        mime: String,
        sizeBytes: Long,
        tempFile: Path,
        uploadedBy: UInt?,
    ): AssetRecord = suspendTransaction(database) {
        val now = nowMillis()
        val id = AssetsTable.insert {
            it[AssetsTable.siteId] = siteId
            it[AssetsTable.locale] = locale
            it[AssetsTable.path] = path
            it[AssetsTable.originalFilename] = originalFilename
            it[AssetsTable.mime] = mime
            it[AssetsTable.sizeBytes] = sizeBytes
            it[createdAt] = now
            it[updatedAt] = now
            it[AssetsTable.uploadedBy] = uploadedBy
        }[AssetsTable.id].value
        val dest = fileForId(id)
        Files.createDirectories(dest.parent)
        Files.move(tempFile, dest, StandardCopyOption.ATOMIC_MOVE)
        AssetRecord(id, siteId, locale, path, originalFilename, mime, sizeBytes, now, now, uploadedBy)
    }

    suspend fun delete(id: UInt): Boolean = suspendTransaction(database) {
        // Delete revision rows first (FK), then the asset row; then their files.
        val revIds = AssetRevisionsTable.selectAll()
            .where { AssetRevisionsTable.assetId eq id }
            .map { it[AssetRevisionsTable.id].value }
            .toList()
        AssetRevisionsTable.deleteWhere { AssetRevisionsTable.assetId eq id }
        AssetScheduledTable.deleteWhere { AssetScheduledTable.assetId eq id }
        val deleted = AssetsTable.deleteWhere { AssetsTable.id eq id } > 0
        if (deleted) {
            runCatching { Files.deleteIfExists(fileForId(id)) }
            runCatching { Files.deleteIfExists(pendingFileForId(id)) }
            revIds.forEach { runCatching { Files.deleteIfExists(revFileForId(it)) } }
        }
        deleted
    }

    /** Prior versions of an asset, newest first. */
    suspend fun revisions(assetId: UInt): List<AssetRevisionRecord> = suspendTransaction(database) {
        AssetRevisionsTable.selectAll()
            .where { AssetRevisionsTable.assetId eq assetId }
            .map { it.toAssetRevisionRecord() }
            .toList()
            .sortedByDescending { it.versionNumber }
    }

    /**
     * Replaces an asset's bytes in place (same path → all references update automatically). The
     * current bytes are archived as a new revision first; revisions beyond [maxVersions] are pruned.
     */
    suspend fun replace(
        assetId: UInt,
        newMime: String,
        newSize: Long,
        newOriginalFilename: String,
        tempFile: Path,
        replacedBy: UInt?,
        maxVersions: Int,
    ): AssetRecord? = suspendTransaction(database) {
        val asset = AssetsTable.selectAll().where { AssetsTable.id eq assetId }
            .map { it.toAssetRecord() }.singleOrNull() ?: return@suspendTransaction null
        val now = nowMillis()
        val nextVersion = (
            AssetRevisionsTable.selectAll().where { AssetRevisionsTable.assetId eq assetId }
                .map { it[AssetRevisionsTable.versionNumber] }.toList().maxOrNull() ?: 0
            ) + 1
        // Archive the current bytes as a revision.
        val revId = AssetRevisionsTable.insert {
            it[AssetRevisionsTable.assetId] = assetId
            it[versionNumber] = nextVersion
            it[originalFilename] = asset.originalFilename
            it[mime] = asset.mime
            it[sizeBytes] = asset.sizeBytes
            it[createdAt] = now
            it[createdBy] = asset.uploadedBy
        }[AssetRevisionsTable.id].value
        val revFile = revFileForId(revId)
        Files.createDirectories(revFile.parent)
        val currentFile = fileForId(assetId)
        if (Files.exists(currentFile)) Files.move(currentFile, revFile, StandardCopyOption.ATOMIC_MOVE)
        // Install the new bytes at the asset's path.
        Files.createDirectories(currentFile.parent)
        Files.move(tempFile, currentFile, StandardCopyOption.ATOMIC_MOVE)
        AssetsTable.update({ AssetsTable.id eq assetId }) {
            it[mime] = newMime
            it[sizeBytes] = newSize
            it[originalFilename] = newOriginalFilename
            it[updatedAt] = now
            it[uploadedBy] = replacedBy
        }
        pruneRevisions(assetId, maxVersions)
        AssetRecord(assetId, asset.siteId, asset.locale, asset.path, newOriginalFilename, newMime, newSize, asset.createdAt, now, replacedBy)
    }

    /** Restores a prior version's bytes as the asset's current file (snapshotting the current first). */
    suspend fun restore(assetId: UInt, revisionId: UInt, restoredBy: UInt?, maxVersions: Int): AssetRecord? {
        val rev = suspendTransaction(database) {
            AssetRevisionsTable.selectAll()
                .where { (AssetRevisionsTable.id eq revisionId) and (AssetRevisionsTable.assetId eq assetId) }
                .map { it.toAssetRevisionRecord() }.singleOrNull()
        } ?: return null
        val revFile = revFileForId(revisionId)
        if (!Files.exists(revFile)) return null
        val temp = newTempFile()
        Files.copy(revFile, temp, StandardCopyOption.REPLACE_EXISTING)
        return replace(assetId, rev.mime, rev.sizeBytes, rev.originalFilename, temp, restoredBy, maxVersions)
    }

    /**
     * One-time purge: deletes asset revisions on [siteId] created before [cutoffMillis] (use
     * [Long.MAX_VALUE] to clear all history), removing their on-disk bytes too. Returns how many
     * revision rows were removed. Current asset bytes are untouched — only archived history is affected.
     */
    suspend fun purgeRevisionsOlderThan(siteId: UInt, cutoffMillis: Long): Int {
        val doomed = suspendTransaction(database) {
            val assetIds = AssetsTable.selectAll().where { AssetsTable.siteId eq siteId }
                .map { it[AssetsTable.id].value }.toList()
            val ids = mutableListOf<UInt>()
            for (aid in assetIds) {
                ids += AssetRevisionsTable.selectAll()
                    .where { (AssetRevisionsTable.assetId eq aid) and (AssetRevisionsTable.createdAt less cutoffMillis) }
                    .map { it[AssetRevisionsTable.id].value }.toList()
            }
            ids.forEach { id -> AssetRevisionsTable.deleteWhere { AssetRevisionsTable.id eq id } }
            ids
        }
        doomed.forEach { id -> runCatching { Files.deleteIfExists(revFileForId(id)) } }
        return doomed.size
    }

    private suspend fun pruneRevisions(assetId: UInt, maxVersions: Int) {
        val revs = AssetRevisionsTable.selectAll().where { AssetRevisionsTable.assetId eq assetId }
            .map { it.toAssetRevisionRecord() }.toList().sortedByDescending { it.versionNumber }
        revs.drop(maxVersions).forEach { old ->
            AssetRevisionsTable.deleteWhere { AssetRevisionsTable.id eq old.id }
            runCatching { Files.deleteIfExists(revFileForId(old.id)) }
        }
    }

    // --- Scheduled replacements ---

    suspend fun pendingFor(assetId: UInt): AssetScheduledRecord? = suspendTransaction(database) {
        AssetScheduledTable.selectAll().where { AssetScheduledTable.assetId eq assetId }
            .map { it.toAssetScheduledRecord() }.singleOrNull()
    }

    /** Schedules a file replacement: store the validated bytes as pending + record the publish time. */
    suspend fun schedule(
        assetId: UInt,
        mime: String,
        sizeBytes: Long,
        originalFilename: String,
        tempFile: Path,
        publishAt: Long,
        by: UInt?,
    ) {
        suspendTransaction(database) {
            AssetScheduledTable.deleteWhere { AssetScheduledTable.assetId eq assetId }
            AssetScheduledTable.insert {
                it[AssetScheduledTable.assetId] = assetId
                it[AssetScheduledTable.mime] = mime
                it[AssetScheduledTable.sizeBytes] = sizeBytes
                it[AssetScheduledTable.originalFilename] = originalFilename
                it[AssetScheduledTable.publishAt] = publishAt
                it[createdAt] = nowMillis()
                it[createdBy] = by
            }
        }
        val dest = pendingFileForId(assetId)
        Files.createDirectories(dest.parent)
        Files.move(tempFile, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    suspend fun discardPending(assetId: UInt): Boolean {
        val deleted = suspendTransaction(database) {
            AssetScheduledTable.deleteWhere { AssetScheduledTable.assetId eq assetId } > 0
        }
        if (deleted) runCatching { Files.deleteIfExists(pendingFileForId(assetId)) }
        return deleted
    }

    /**
     * Promotes pending replacements whose time has arrived. Re-validates the pending bytes; a
     * corrupted/now-disallowed file is dropped (not retried). Returns how many were applied.
     */
    suspend fun promoteScheduledReplacements(
        now: Long,
        allowedMimeTypes: Set<String>,
        maxVersionsFor: suspend (siteId: UInt) -> Int,
    ): Int {
        val due = suspendTransaction(database) {
            AssetScheduledTable.selectAll().where { AssetScheduledTable.publishAt lessEq now }
                .map { it[AssetScheduledTable.assetId].value }.toList()
        }
        var promoted = 0
        for (assetId in due) {
            val ok = runCatching { promoteOneReplacement(assetId, now, allowedMimeTypes, maxVersionsFor) }.getOrDefault(false)
            if (ok) promoted++
        }
        return promoted
    }

    private suspend fun promoteOneReplacement(
        assetId: UInt,
        now: Long,
        allowedMimeTypes: Set<String>,
        maxVersionsFor: suspend (siteId: UInt) -> Int,
    ): Boolean {
        val pending = pendingFor(assetId) ?: return false
        if (pending.publishAt > now) return false
        val asset = findById(assetId) ?: return false
        val maxVersions = maxVersionsFor(asset.siteId)
        val pendingFile = pendingFileForId(assetId)
        // Re-validate at promotion (file could be corrupt, or the allowlist could have changed).
        val head = if (Files.exists(pendingFile)) Files.newInputStream(pendingFile).use { it.readNBytes(16) } else ByteArray(0)
        val mime = ImageType.detect(head)
        if (mime == null || mime !in allowedMimeTypes) {
            discardPending(assetId) // drop so it doesn't retry every tick
            return false
        }
        val temp = newTempFile()
        Files.copy(pendingFile, temp, StandardCopyOption.REPLACE_EXISTING)
        val result = replace(assetId, mime, pending.sizeBytes, pending.originalFilename, temp, pending.createdBy, maxVersions)
        discardPending(assetId)
        return result != null
    }

    /** The on-disk file for an asset id, sharded by id to keep directories small. */
    fun fileForId(id: UInt): Path = storageRoot.resolve((id % 256u).toString()).resolve(id.toString())

    /** The on-disk file for an asset's pending scheduled replacement (own subtree; one per asset). */
    fun pendingFileForId(assetId: UInt): Path =
        storageRoot.resolve("pending").resolve((assetId % 256u).toString()).resolve(assetId.toString())

    /** The on-disk file for an archived revision id (under the `rev/` tree, sharded). */
    fun revFileForId(revisionId: UInt): Path =
        storageRoot.resolve("rev").resolve((revisionId % 256u).toString()).resolve(revisionId.toString())

    /** Creates an empty temp file (same filesystem as storage, so the later move is atomic). */
    fun newTempFile(): Path = Files.createTempFile(storageRoot.resolve("tmp"), "upload", ".part")

    /**
     * The set of assets (by locale+path) referenced from [content] — markdown `![](…)`/`[](…)` and
     * raw HTML `src`/`href`. Code spans/blocks are masked out. Local URLs are normalized to (locale,
     * path) using the same locale parsing the page/asset router uses, so `/x.png` and `/<default>/x.png`
     * collapse while a non-default `/de/x.png` stays distinct.
     */
    fun referencedAssetPaths(content: String, defaultLocale: String): Set<AssetRef> {
        val masked = ContentMasking.maskedText(content)
        val refs = mutableSetOf<AssetRef>()
        for (m in MARKDOWN_URL.findAll(masked)) addLocalUrl(refs, m.groupValues[1], defaultLocale)
        for (m in HTML_URL.findAll(masked)) addLocalUrl(refs, m.groupValues[1], defaultLocale)
        return refs
    }

    private fun addLocalUrl(into: MutableSet<AssetRef>, rawUrl: String, defaultLocale: String) {
        resolveLocalAssetUrl(rawUrl, defaultLocale)?.let { into.add(it) }
    }

    /**
     * Maps a same-origin asset URL (`/x.png` or `/<locale>/x.png`) to its (locale, path) identity using
     * the same locale parsing as the router. Returns null for external/protocol-relative/anchor URLs.
     */
    fun resolveLocalAssetUrl(rawUrl: String, defaultLocale: String): AssetRef? {
        val url = rawUrl.substringBefore('?').substringBefore('#').trim()
        if (!url.startsWith("/") || url.startsWith("//") || url.contains(":")) return null
        val segments = url.removePrefix("/").split("/").filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val (locale, pathSegments) = if (isLocaleSegment(segments.first()) && segments.size > 1) {
            segments.first() to segments.drop(1)
        } else {
            defaultLocale to segments
        }
        val path = pathSegments.joinToString("/")
        return if (path.isEmpty()) null else AssetRef(locale, path)
    }

    companion object {
        private val MARKDOWN_URL = Regex("!?\\[[^\\]]*]\\(\\s*<?([^)\\s>]+)")
        private val HTML_URL = Regex("(?:src|href)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    }
}
