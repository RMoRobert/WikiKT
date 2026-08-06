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
import com.wikikt.markdown.MarkdownRefScanner
import com.wikikt.routing.isLocaleSegment
import com.wikikt.routing.resolveRelativeWikiUrl
import io.ktor.http.decodeURLPart
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

    /**
     * Moves the asset's virtual identity to ([newLocale], [newPath]). The bytes never move — storage
     * is keyed by id — and revisions/scheduled replacements key by id too, so this is metadata-only:
     * the asset's URL changes and content references do NOT follow (the caller warns about them; the
     * broken-references report lists each one afterwards). Returns false when another asset already
     * sits at the target for the same site; the (siteId, locale, path) unique index backstops races.
     */
    suspend fun rename(id: UInt, newLocale: String, newPath: String): Boolean = suspendTransaction(database) {
        val asset = AssetsTable.selectAll().where { AssetsTable.id eq id }
            .map { it.toAssetRecord() }.singleOrNull() ?: return@suspendTransaction false
        if (asset.locale == newLocale && asset.path == newPath) return@suspendTransaction true
        val taken = AssetsTable.selectAll()
            .where { (AssetsTable.siteId eq asset.siteId) and (AssetsTable.locale eq newLocale) and (AssetsTable.path eq newPath) }
            .map { it[AssetsTable.id].value }.singleOrNull() != null
        if (taken) return@suspendTransaction false
        AssetsTable.update({ AssetsTable.id eq id }) {
            it[AssetsTable.locale] = newLocale
            it[AssetsTable.path] = newPath
        }
        true
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
     * How a same-origin URL was written in content. An [EMBED] can only ever be satisfied by an asset —
     * nothing else renders inside an `<img>`. A [LINK] may legitimately point at a wiki page instead, so
     * the broken-reference scan can't demand an asset for one.
     */
    enum class RefKind { EMBED, LINK }

    /**
     * One same-origin URL found in content: the asset identity it resolves to, how it was written, and
     * whether the URL named its locale explicitly. The last matters because a locale-relative URL binds
     * late — see `renderAssetRefs` in WikiRouting — so it can resolve to a different locale per source.
     */
    data class ContentRef(val ref: AssetRef, val kind: RefKind, val explicitLocale: Boolean)

    /**
     * The set of assets (by locale+path) referenced from [content] — markdown images/links (including
     * reference-style and nested forms) and raw HTML `src`/`href`, via [MarkdownRefScanner]. Local URLs
     * are normalized to (locale, path) using the same locale parsing the page/asset router uses, so
     * `/x.png` and `/<default>/x.png` collapse while a non-default `/de/x.png` stays distinct.
     */
    fun referencedAssetPaths(
        content: String,
        defaultLocale: String,
        basePath: String = "",
        baseLocale: String = "",
    ): Set<AssetRef> =
        referencedLocalUrls(content, defaultLocale, basePath, baseLocale).mapTo(mutableSetOf()) { it.ref }

    /**
     * The richer form of [referencedAssetPaths]: the same scan, but keeping embed-vs-link and whether
     * the locale was explicit. The broken-reference report needs both — an `<img>` with no asset behind
     * it is broken, while a link with no asset may simply be pointing at a page.
     *
     * When [basePath] is given, directory-relative URLs (`image.png`, `../shared/logo.png`) are
     * resolved against it exactly as [com.wikikt.routing.resolveRelativeWikiUrl] does at render time —
     * the containing page treated as a directory, landing in [baseLocale]. Pass it only for content
     * whose render actually applies that pass (page bodies), since a relative URL elsewhere has no
     * single target. Left blank, relative URLs are skipped entirely.
     *
     * [html] marks HTML-format content: it is scanned for src/href attributes directly instead of being
     * parsed as Markdown (whose block rules — indented lines become code — would hide references).
     */
    fun referencedLocalUrls(
        content: String,
        defaultLocale: String,
        basePath: String = "",
        baseLocale: String = "",
        html: Boolean = false,
    ): Set<ContentRef> {
        val refs = mutableSetOf<ContentRef>()
        for (scanned in MarkdownRefScanner.scan(content, html)) {
            // Relative first: resolveRelativeWikiUrl returns null for anything already absolute, so the
            // absolute URL falls through to parseLocalUrl unchanged.
            val url = basePath.takeIf { it.isNotBlank() }
                ?.let { resolveRelativeWikiUrl(scanned.url, baseLocale.ifBlank { defaultLocale }, it) }
                ?: scanned.url
            val (ref, explicit) = parseLocalUrl(url, defaultLocale) ?: continue
            refs.add(ContentRef(ref, if (scanned.embed) RefKind.EMBED else RefKind.LINK, explicit))
        }
        return refs
    }

    /**
     * Directory-relative URLs in [content] that must name an asset (an embed, or a link to a path with
     * a file extension — a page path can't contain a period). These are exactly what
     * [referencedLocalUrls] drops when given no base, so the broken-reference report can list them for
     * content whose render has no single page to resolve against: infobox values, fragments, the footer
     * override, nav targets. The fix in every case is an absolute `/folder/file.png`, which binds to the
     * page's locale and falls back to the default at serve time.
     */
    fun unresolvableRelativeRefs(content: String, html: Boolean = false): Set<String> {
        val out = mutableSetOf<String>()
        for (scanned in MarkdownRefScanner.scan(content, html)) {
            unresolvableRelativeRef(scanned.url, if (scanned.embed) RefKind.EMBED else RefKind.LINK)?.let { out.add(it) }
        }
        return out
    }

    /**
     * [rawUrl]'s path if it is directory-relative AND must name an asset ([kind] embed, or a dotted
     * final segment), else null. Public for the one caller with a bare URL rather than content to scan:
     * the broken-reference report's check of navigation targets.
     */
    fun unresolvableRelativeRef(rawUrl: String, kind: RefKind): String? {
        // A non-null result means it really is directory-relative: absolute, anchor, protocol-relative
        // and scheme'd URLs all return null. The locale/path arguments are placeholders, unused here.
        if (resolveRelativeWikiUrl(rawUrl, "x", "x") == null) return null
        val path = rawUrl.substringBefore('?').substringBefore('#').trim()
        return if (kind == RefKind.EMBED || path.substringAfterLast('/').contains('.')) path else null
    }

    /**
     * A human-readable message naming every directory-relative asset reference in [content] and the
     * absolute form to replace it with, or null when there are none. [subject] names what is being
     * checked ("Infobox values", "Fragment content") and starts the sentence.
     *
     * This is a **UI-layer** guard: it is called from the page editor and the fragment form only. The
     * service layer stays permissive on purpose, so a WikiJS import, a backup restore, or the JSON API
     * can't be failed over a link style — see the note on /f/broken.
     */
    fun relativeRefError(content: String, subject: String): String? {
        val refs = unresolvableRelativeRefs(content)
        if (refs.isEmpty()) return null
        // A "../" reference has no single absolute equivalent, so it's named without a suggestion.
        val fixes = refs.sorted().joinToString(", ") { url ->
            if (url.startsWith("..")) "'$url'" else "'$url' → '/${url.removePrefix("./")}'"
        }
        return "$subject must use absolute file paths that start with '/': $fixes. " +
            "An absolute path binds to the page's locale and falls back to the default locale, so it " +
            "stays correct wherever it is shown."
    }

    /**
     * Maps a same-origin asset URL (`/x.png` or `/<locale>/x.png`) to its (locale, path) identity using
     * the same locale parsing as the router. Returns null for external/protocol-relative/anchor URLs.
     */
    fun resolveLocalAssetUrl(rawUrl: String, defaultLocale: String): AssetRef? =
        parseLocalUrl(rawUrl, defaultLocale)?.first

    /** [resolveLocalAssetUrl], also reporting whether the URL carried its own locale segment. */
    private fun parseLocalUrl(rawUrl: String, defaultLocale: String): Pair<AssetRef, Boolean>? {
        val url = rawUrl.substringBefore('?').substringBefore('#').trim()
        if (!url.startsWith("/") || url.startsWith("//") || url.contains(":")) return null
        // Percent-decode each segment the way the router does (it splits the raw path on '/', then
        // Ktor decodes each captured segment) — so `/caf%C3%A9.png`, which serves fine, matches the
        // stored `café.png` here too instead of scanning as a different (broken/unused) path. A
        // segment that fails to decode is kept raw: a literal `%` can't appear in an asset path, so
        // it matches nothing — same as at serve time.
        val segments = url.removePrefix("/").split("/").filter { it.isNotEmpty() }
            .map { seg -> runCatching { seg.decodeURLPart() }.getOrDefault(seg) }
        if (segments.isEmpty()) return null
        val explicitLocale = isLocaleSegment(segments.first()) && segments.size > 1
        val locale = if (explicitLocale) segments.first() else defaultLocale
        val path = (if (explicitLocale) segments.drop(1) else segments).joinToString("/")
        return if (path.isEmpty()) null else AssetRef(locale, path) to explicitLocale
    }
}
