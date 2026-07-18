package com.wikikt.service

import com.wikikt.db.ContentFormat
import com.wikikt.model.CreatePageRequest
import com.wikikt.model.UpdatePageRequest
import com.wikikt.model.normalizeTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Upserts externally-sourced content (git sync pulls, backup restores) into the wiki through the
 * normal services, so imported page changes land in revision history and asset replacements are
 * archived. Imports are unattributed (`updatedBy = null`) — the WikiJS-style file format carries
 * no author. Unchanged content is detected and left untouched to avoid revision spam.
 */
class ContentImporter(
    private val pages: PageService,
    private val assets: AssetService,
    private val maxAssetVersionsFor: suspend (siteId: UInt) -> Int,
    private val allowedAssetMimeTypes: Set<String>,
) {
    enum class Outcome { APPLIED, UNCHANGED, SKIPPED }

    /** Creates or updates the page at ([locale], [path]) on [siteId] from a page file's raw text. */
    suspend fun upsertPage(siteId: UInt, locale: String, path: String, format: ContentFormat, raw: String): Outcome {
        val parsed = PageFileFormat.parsePageFile(raw, html = format == ContentFormat.HTML)
        val existing = pages.findByLocaleAndPath(siteId, locale, path)
        val tags = parsed.tags?.let { normalizeTags(it) }
        // WikiKT front-matter extension: honor a per-page robots override, but only a known directive
        // (a hand-edited git file could carry anything) — anything else falls back to the site default.
        val metaRobots = parsed.metaRobots?.takeIf { it in SettingsService.META_ROBOTS_OPTIONS }
        if (existing == null) {
            pages.create(
                siteId,
                CreatePageRequest(
                    locale = locale,
                    path = path,
                    title = parsed.title ?: path.substringAfterLast('/'),
                    description = parsed.description?.ifBlank { null },
                    content = parsed.content,
                    contentFormat = format.name,
                    published = parsed.published ?: true,
                    tags = tags ?: emptyList(),
                    infobox = parsed.infobox,
                    metaRobots = metaRobots,
                ),
                updatedBy = null,
            )
            return Outcome.APPLIED
        }
        val unchanged = existing.content == parsed.content &&
            existing.contentFormat == format &&
            (parsed.title == null || existing.title == parsed.title) &&
            (existing.description ?: "") == parsed.description.orEmpty() &&
            (parsed.published == null || existing.published == parsed.published) &&
            (parsed.infobox == null || (existing.infobox ?: "") == parsed.infobox) &&
            (metaRobots == null || existing.metaRobots == metaRobots) &&
            (tags == null || existing.tags.sorted() == tags.sorted())
        if (unchanged) return Outcome.UNCHANGED
        pages.update(
            existing.id,
            UpdatePageRequest(
                title = parsed.title,
                description = parsed.description,
                content = parsed.content,
                contentFormat = format.name,
                published = parsed.published,
                tags = tags,
                infobox = parsed.infobox,
                metaRobots = metaRobots,
            ),
            updatedBy = null,
        )
        return Outcome.APPLIED
    }

    /**
     * Creates or replaces the asset at ([locale], [path]) from [file]. Only supported image types
     * import (anything else is skipped); identical bytes are left untouched.
     */
    suspend fun upsertAsset(siteId: UInt, locale: String, path: String, file: Path): Outcome {
        if (!withContext(Dispatchers.IO) { Files.exists(file) }) return Outcome.SKIPPED
        val head = withContext(Dispatchers.IO) { Files.newInputStream(file).use { it.readNBytes(16) } }
        val mime = ImageType.detect(head)
        if (mime == null || mime !in allowedAssetMimeTypes) return Outcome.SKIPPED
        val existing = assets.findByLocaleAndPath(siteId, locale, path)
        val size = withContext(Dispatchers.IO) { Files.size(file) }
        val filename = path.substringAfterLast('/')
        if (existing == null) {
            val temp = assets.newTempFile()
            withContext(Dispatchers.IO) { Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING) }
            assets.create(siteId, locale, path, filename, mime, size, temp, uploadedBy = null)
            return Outcome.APPLIED
        }
        val identical = withContext(Dispatchers.IO) {
            val current = assets.fileForId(existing.id)
            Files.exists(current) && Files.mismatch(current, file) == -1L
        }
        if (identical) return Outcome.UNCHANGED
        val temp = assets.newTempFile()
        withContext(Dispatchers.IO) { Files.copy(file, temp, StandardCopyOption.REPLACE_EXISTING) }
        assets.replace(existing.id, mime, size, filename, temp, replacedBy = null, maxVersions = maxAssetVersionsFor(siteId))
        return Outcome.APPLIED
    }
}
