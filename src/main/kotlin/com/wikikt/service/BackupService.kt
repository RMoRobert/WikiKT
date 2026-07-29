package com.wikikt.service

import com.wikikt.BuildInfo
import com.wikikt.db.ApiKeysTable
import com.wikikt.db.AppSettingsTable
import com.wikikt.db.AssetRevisionsTable
import com.wikikt.db.AssetScheduledTable
import com.wikikt.db.AssetsTable
import com.wikikt.db.ContentFormat
import com.wikikt.db.FragmentsTable
import com.wikikt.db.GroupPageRuleLocalesTable
import com.wikikt.db.GroupPageRuleRolesTable
import com.wikikt.db.GroupPageRuleSitesTable
import com.wikikt.db.GroupPageRulesTable
import com.wikikt.db.GroupPermissionsTable
import com.wikikt.db.GroupsTable
import com.wikikt.db.NavItemsTable
import com.wikikt.db.NavMenusTable
import com.wikikt.db.PageAliasesTable
import com.wikikt.db.PageEditAclTable
import com.wikikt.db.PageRevisionsTable
import com.wikikt.db.PageSearchIndexTable
import com.wikikt.db.PageStagedTable
import com.wikikt.db.PageTagsTable
import com.wikikt.db.PageViewAclTable
import com.wikikt.db.PagesTable
import com.wikikt.db.PageRenderCacheTable
import com.wikikt.db.SessionsTable
import com.wikikt.db.SitesTable
import com.wikikt.db.UserGroupsTable
import com.wikikt.db.UsersTable
import com.wikikt.model.nowMillis
import com.wikikt.routing.isLocaleSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.EntityIDColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.UIntegerColumnType
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteAll
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable
data class BackupManifest(
    val format: String, // always BackupService.MANIFEST_FORMAT; required so foreign zips can't pass
    val scope: String,
    val wikiktVersion: String,
    val schemaVersion: Int,
    val createdAt: Long,
)

@Serializable
data class FragmentBackup(val locale: String, val key: String, val title: String, val content: String)

@Serializable
data class NavItemBackup(
    val isHeader: Boolean,
    val label: String,
    val icon: String?,
    val target: String?,
    val isDivider: Boolean = false,
    val depth: Int = 0,
)

@Serializable
data class NavMenuBackup(val scope: String, val items: List<NavItemBackup>)

data class RestoreSummary(
    val pagesImported: Int,
    val assetsImported: Int,
    val fragmentsImported: Int,
    val menusImported: Int,
    val unchanged: Int,
    val skipped: Int,
) {
    fun message(): String {
        val skippedNote = if (skipped > 0) ", $skipped skipped" else ""
        return "Restored $pagesImported pages, $assetsImported assets, $fragmentsImported fragments, " +
            "$menusImported menus ($unchanged already up to date$skippedNote)."
    }
}

/**
 * Site backup and restore as a single ZIP with two scopes.
 *
 * **Content** holds pages in the same WikiJS-compatible tree the git sync exports
 * (`content/{locale}/{path}.md|.html`, front-matter per [PageFileFormat]), assets at
 * `assets/{locale}/{path}`, plus fragments and navigation as JSON — human-readable, credential-free,
 * and restorable onto any wiki. Content restore is upsert-only through [ContentImporter] (page
 * changes land in revision history) and never deletes anything.
 *
 * **Full** is a whole-**instance** backup: a portable JSON dump of every database table
 * (`db/{table}.json` — the sites list, users with password hashes, groups, ACLs, revisions, API-key
 * hashes, settings) plus every site's current, revision, and pending asset bytes (keyed by row id
 * under `files/`), so it captures all sites and restores identically onto H2 or Postgres. The
 * readable `content/` tree is the managing site's, as a human-readable preview; the authoritative
 * all-sites content lives in the DB dump. Full restore is destructive: every table and asset file is
 * replaced with the backup's state. Rows are re-inserted with fresh ids (foreign keys — including
 * each row's `site_id` — remapped via old→new id maps), which sidesteps database-specific
 * auto-increment sequence repair. Sessions are never backed up and are wiped on restore, so all users
 * must sign in again; the search index and render cache are rebuilt.
 */
class BackupService(
    private val database: R2dbcDatabase,
    private val sites: SiteService,
    private val pages: PageService,
    private val assets: AssetService,
    private val fragments: FragmentService,
    private val nav: NavService,
    private val importer: ContentImporter,
    private val settings: SettingsService,
    private val searchIndex: SearchIndexService,
    private val assetStorageDir: Path,
) {
    // encodeDefaults so `format` is always written; without it the manifest would rely on the
    // decoder's default and any stray zip with a manifest.json would masquerade as a backup.
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    /** Streams a content-scope backup ZIP of [siteId] to [out]. */
    suspend fun writeContentBackup(siteId: UInt, out: OutputStream) = writeBackup(siteId, out, SCOPE_CONTENT, null)

    /**
     * Streams a full-scope backup ZIP to [out]: the complete whole-instance DB dump plus every site's
     * asset bytes. [siteId] only supplies the readable `content/` preview (that site's pages); the DB
     * dump and id-keyed asset files — which drive a full restore — span every site regardless.
     *
     * When [secretsPassword] is blank/null the backup is written in the clear but with plaintext
     * credentials ([SettingsService.SENSITIVE_SETTING_KEYS] — the SMTP password and git token) stripped
     * out of the dump entirely; the admin re-enters them after a restore. When it's set, the whole backup
     * is stream-encrypted under that password ([BackupCrypto]) — opaque and unrestorable without it — and
     * the credentials are included, since the container itself now protects them.
     */
    suspend fun writeFullBackup(siteId: UInt, out: OutputStream, secretsPassword: String? = null) =
        writeBackup(siteId, out, SCOPE_FULL, secretsPassword?.ifBlank { null })

    private suspend fun writeBackup(siteId: UInt, out: OutputStream, scope: String, secretsPassword: String?) {
        val pageList = pages.list(siteId)
        val assetList = assets.list(siteId)
        val fragmentList = fragments.list(siteId).map { FragmentBackup(it.locale, it.key, it.title, it.content) }
        val menuList = nav.listMenus(siteId).map { menu ->
            NavMenuBackup(
                scope = menu.scope,
                items = nav.items(menu.id).map { NavItemBackup(it.isHeader, it.label, it.icon, it.target, it.isDivider, it.depth) },
            )
        }
        val manifest = BackupManifest(
            format = MANIFEST_FORMAT,
            scope = scope,
            wikiktVersion = BuildInfo.version,
            schemaVersion = MIGRATIONS.maxOf { it.version },
            createdAt = nowMillis(),
        )
        val dump = if (scope == SCOPE_FULL) dumpAllTables() else null
        // A backup password encrypts the entire container; without one the credentials are redacted instead.
        val encrypt = secretsPassword != null
        withContext(Dispatchers.IO) {
            val sink = if (encrypt) BackupCrypto.EncryptingOutputStream(out, secretsPassword!!) else out
            ZipOutputStream(sink, StandardCharsets.UTF_8).use { zip ->
                zip.putText(MANIFEST_ENTRY, json.encodeToString(manifest))
                zip.putText(FRAGMENTS_ENTRY, json.encodeToString(fragmentList))
                zip.putText(NAVIGATION_ENTRY, json.encodeToString(menuList))
                for (page in pageList) {
                    zip.putText("$CONTENT_PREFIX${PageFileFormat.pageFilePath(page)}", PageFileFormat.pageFileBody(page))
                }
                if (dump == null) {
                    // Content scope: this site's asset bytes in the readable, path-addressed tree.
                    for (asset in assetList) {
                        val source = assets.fileForId(asset.id)
                        if (!Files.exists(source)) continue // metadata row without bytes; skip rather than fail
                        zip.putNextEntry(ZipEntry("$ASSETS_PREFIX${asset.locale}/${asset.path}"))
                        Files.copy(source, zip)
                        zip.closeEntry()
                    }
                } else {
                    // Unencrypted backup → strip plaintext credentials from the dump; encrypted → keep them
                    // (the container protects them).
                    for ((name, rows) in dump) {
                        val toWrite = if (name == AppSettingsTable.tableName && !encrypt) redactSecrets(rows) else rows
                        zip.putText("$DB_PREFIX$name.json", json.encodeToString(toWrite))
                    }
                    // Every site's current asset bytes, keyed by row id (a flat locale/path namespace
                    // would collide across sites); restore installs them under the remapped id.
                    for (assetId in dump.rowIds(AssetsTable.tableName, "id")) {
                        val f = assets.fileForId(assetId.toUInt())
                        if (Files.exists(f)) {
                            zip.putNextEntry(ZipEntry("$CURRENT_FILES_PREFIX$assetId"))
                            Files.copy(f, zip)
                            zip.closeEntry()
                        }
                    }
                    // Archived asset-revision bytes and pending scheduled replacements, keyed by row id.
                    for (revId in dump.rowIds(AssetRevisionsTable.tableName, "id")) {
                        val f = assets.revFileForId(revId.toUInt())
                        if (Files.exists(f)) {
                            zip.putNextEntry(ZipEntry("$REV_FILES_PREFIX$revId"))
                            Files.copy(f, zip)
                            zip.closeEntry()
                        }
                    }
                    for (assetId in dump.rowIds(AssetScheduledTable.tableName, "asset_id")) {
                        val f = assets.pendingFileForId(assetId.toUInt())
                        if (Files.exists(f)) {
                            zip.putNextEntry(ZipEntry("$PENDING_FILES_PREFIX$assetId"))
                            Files.copy(f, zip)
                            zip.closeEntry()
                        }
                    }
                }
            }
        }
    }

    private fun Map<String, JsonArray>.rowIds(tableName: String, field: String): List<Long> =
        this[tableName]?.mapNotNull { (it.jsonObject[field] as? JsonPrimitive)?.long } ?: emptyList()

    /** Drops sensitive-credential rows ([SettingsService.SENSITIVE_SETTING_KEYS]) from an `app_settings`
     *  dump, so an unencrypted backup never carries the SMTP password or git token. */
    private fun redactSecrets(appSettings: JsonArray): JsonArray = JsonArray(
        appSettings.filter { (it.jsonObject["key"] as? JsonPrimitive)?.content !in SettingsService.SENSITIVE_SETTING_KEYS },
    )

    // --- Restore (dispatch) ---

    /**
     * Restores a backup archive by its manifest scope. Content restores are additive and safe;
     * full restores replace the entire site and require [allowFull] (the explicit confirmation
     * checkbox in the admin form).
     */
    suspend fun restore(siteId: UInt, zipPath: Path, allowFull: Boolean, secretsPassword: String? = null): String {
        // An encrypted backup is opaque: it must be decrypted with its password before anything can be read
        // or restored. A wrong/missing password fails here, so nothing is touched.
        val encrypted = withContext(Dispatchers.IO) { BackupCrypto.isEncryptedBackup(zipPath) }
        var decrypted: Path? = null
        try {
            val effectiveZip = if (!encrypted) {
                zipPath
            } else {
                val password = secretsPassword?.ifBlank { null }
                    ?: throw IllegalArgumentException("This backup is encrypted. Enter the backup password used when it was created.")
                val temp = withContext(Dispatchers.IO) { Files.createTempFile("wikikt-decrypted", ".zip") }
                decrypted = temp
                val ok = withContext(Dispatchers.IO) { BackupCrypto.decryptToFile(zipPath, password, temp) }
                require(ok) { "Could not decrypt this backup — the backup password is incorrect, or the file is corrupt. Nothing was restored." }
                temp
            }

            val manifest = withContext(Dispatchers.IO) {
                ZipFile(effectiveZip.toFile()).use { readManifest(it, DecompressionBudget(MAX_RESTORE_DECOMPRESSED_BYTES)) }
            }
            return when (manifest.scope) {
                SCOPE_CONTENT -> restoreContent(siteId, effectiveZip).message()
                SCOPE_FULL -> {
                    require(allowFull) {
                        "This is a FULL backup. Restoring it replaces all site data (accounts, settings, " +
                            "history). Tick the confirmation box to proceed."
                    }
                    restoreFull(effectiveZip)
                }
                else -> throw IllegalArgumentException("Unknown backup scope '${manifest.scope}'.")
            }
        } finally {
            decrypted?.let { withContext(Dispatchers.IO) { Files.deleteIfExists(it) } }
        }
    }

    // --- Content restore (additive) ---

    private data class PageEntry(val locale: String, val path: String, val format: ContentFormat, val raw: String)
    private data class AssetEntry(val locale: String, val path: String, val tempFile: Path)

    /** Everything read out of a content backup archive, before any of it is applied. */
    private data class ArchiveContents(
        val pages: List<PageEntry>,
        val assetFiles: List<AssetEntry>,
        val fragmentsJson: String?,
        val navigationJson: String?,
        val skipped: Int,
    )

    /**
     * Restores a content backup from [zipPath] (a temp file of the uploaded archive). Validates the
     * manifest, then upserts pages, assets, fragments, and navigation menus. Never deletes.
     */
    suspend fun restoreContent(siteId: UInt, zipPath: Path): RestoreSummary {
        val contents = withContext(Dispatchers.IO) { readArchive(zipPath) }
        try {
            var pagesImported = 0
            var assetsImported = 0
            var unchanged = 0
            var skipped = contents.skipped

            for ((locale, path, format, raw) in contents.pages) {
                when (importer.upsertPage(siteId, locale, path, format, raw)) {
                    ContentImporter.Outcome.APPLIED -> pagesImported++
                    ContentImporter.Outcome.UNCHANGED -> unchanged++
                    ContentImporter.Outcome.SKIPPED -> skipped++
                }
            }
            for ((locale, path, file) in contents.assetFiles) {
                when (importer.upsertAsset(siteId, locale, path, file)) {
                    ContentImporter.Outcome.APPLIED -> assetsImported++
                    ContentImporter.Outcome.UNCHANGED -> unchanged++
                    ContentImporter.Outcome.SKIPPED -> skipped++
                }
            }

            var fragmentsImported = 0
            contents.fragmentsJson?.let { text ->
                val existing = fragments.list(siteId)
                for (f in json.decodeFromString<List<FragmentBackup>>(text)) {
                    val current = existing.find { it.locale == f.locale && it.key == f.key }
                    when {
                        current == null -> {
                            fragments.create(siteId, f.locale, f.key, f.title, f.content, updatedBy = null)
                            fragmentsImported++
                        }
                        current.title != f.title || current.content != f.content -> {
                            fragments.update(current.id, f.locale, f.key, f.title, f.content, updatedBy = null)
                            fragmentsImported++
                        }
                        else -> unchanged++
                    }
                }
            }

            var menusImported = 0
            contents.navigationJson?.let { text ->
                val existing = nav.listMenus(siteId)
                for (menu in json.decodeFromString<List<NavMenuBackup>>(text)) {
                    val items = menu.items.map {
                        com.wikikt.model.NavItemInput(
                            isHeader = it.isHeader, isDivider = it.isDivider, depth = it.depth,
                            label = it.label, icon = it.icon, target = it.target,
                        )
                    }
                    val current = existing.find { it.scope == menu.scope }
                    if (current == null) {
                        nav.createMenu(siteId, menu.scope, items)
                        menusImported++
                    } else {
                        val currentItems = nav.items(current.id).map { NavItemBackup(it.isHeader, it.label, it.icon, it.target, it.isDivider, it.depth) }
                        if (currentItems == menu.items) {
                            unchanged++
                        } else {
                            nav.updateMenu(current.id, menu.scope, items)
                            menusImported++
                        }
                    }
                }
            }

            return RestoreSummary(pagesImported, assetsImported, fragmentsImported, menusImported, unchanged, skipped)
        } finally {
            withContext(Dispatchers.IO) { contents.assetFiles.forEach { Files.deleteIfExists(it.tempFile) } }
        }
    }

    /** Reads and validates a content archive (blocking IO): page texts, assets to temp files, JSON blobs. */
    private fun readArchive(zipPath: Path): ArchiveContents = ZipFile(zipPath.toFile()).use { zip ->
        val budget = DecompressionBudget(MAX_RESTORE_DECOMPRESSED_BYTES)
        val manifest = readManifest(zip, budget)
        require(manifest.scope == SCOPE_CONTENT) {
            "This is a '${manifest.scope}' backup; only content backups can be restored here."
        }

        val entries = zip.entries().toList().filter { !it.isDirectory }
        require(entries.size <= MAX_RESTORE_ENTRIES) { "This backup archive has too many entries to restore." }
        val pageEntries = mutableListOf<PageEntry>()
        val assetFiles = mutableListOf<AssetEntry>()
        var skipped = 0
        for (entry in entries) {
            val name = entry.name
            when {
                name == MANIFEST_ENTRY || name == FRAGMENTS_ENTRY || name == NAVIGATION_ENTRY -> {}
                name.startsWith(CONTENT_PREFIX) -> {
                    val target = pageTarget(name.removePrefix(CONTENT_PREFIX))
                    if (target == null) {
                        skipped++
                    } else {
                        val (locale, path, format) = target
                        pageEntries.add(PageEntry(locale, path, format, zip.readText(entry, budget)))
                    }
                }
                name.startsWith(ASSETS_PREFIX) -> {
                    val rel = name.removePrefix(ASSETS_PREFIX)
                    val locale = rel.substringBefore('/')
                    val path = rel.substringAfter('/', "")
                    if (path.isEmpty() || !isLocaleSegment(locale)) {
                        skipped++
                    } else {
                        // Bytes go through a temp file; assets are stored by DB id, so a hostile entry
                        // name can never become a filesystem path.
                        val temp = Files.createTempFile("wikikt-restore", ".part")
                        BudgetedInputStream(zip.getInputStream(entry), budget).use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
                        assetFiles.add(AssetEntry(locale, path, temp))
                    }
                }
                else -> skipped++
            }
        }
        ArchiveContents(
            pages = pageEntries,
            assetFiles = assetFiles,
            fragmentsJson = zip.getEntry(FRAGMENTS_ENTRY)?.let { zip.readText(it, budget) },
            navigationJson = zip.getEntry(NAVIGATION_ENTRY)?.let { zip.readText(it, budget) },
            skipped = skipped,
        )
    }

    // --- Full restore (destructive replace) ---

    /** Everything read out of a full backup archive, before any of it is applied. */
    private data class FullArchive(
        val manifest: BackupManifest,
        val tables: Map<String, JsonArray>,
        val currentFiles: Map<Long, Path>, // old asset id -> extracted current bytes
        val revFiles: Map<Long, Path>, // old asset-revision id -> extracted bytes
        val pendingFiles: Map<Long, Path>, // old asset id -> extracted pending bytes
    )

    private suspend fun restoreFull(zipPath: Path): String {
        val archive = withContext(Dispatchers.IO) { readFullArchive(zipPath) }
        try {
            val currentSchema = MIGRATIONS.maxOf { it.version }
            // Older backups restore fine: migrations already brought the live schema forward before
            // this runs, and insertRows leaves absent columns to their defaults — which works because
            // migrations are required to be additive within a major version (see docs/migrations.md).
            // A NEWER backup genuinely cannot restore: its rows may carry columns this build has
            // never heard of, so data would be silently dropped.
            require(archive.manifest.schemaVersion <= currentSchema) {
                "This backup was taken at schema version ${archive.manifest.schemaVersion}, newer than " +
                    "this server's version $currentSchema. Upgrade WikiKT before restoring it."
            }

            // Replace every table in one transaction: wipe children-first, insert parents-first with
            // fresh ids (references remapped), so auto-increment sequences never need repair.
            val idMaps = mutableMapOf<Table, MutableMap<Long, UInt>>()
            var rows = 0
            suspendTransaction(database) {
                PageSearchIndexTable.deleteAll() // derived; rebuilt below
                PageRenderCacheTable.deleteAll() // derived; both FK pages, so clear before wiping pages
                SessionsTable.deleteAll() // never backed up: a restore signs everyone out
                for (table in BACKUP_TABLES.reversed()) table.deleteAll()
                for (table in BACKUP_TABLES) {
                    val dumped = archive.tables[table.tableName] ?: continue
                    rows += dumped.size
                    insertRows(table, dumped, idMaps)
                }
            }

            // Replace asset bytes: wipe the store (except tmp/), then install current, revision, and
            // pending files under their new ids.
            var files = 0
            withContext(Dispatchers.IO) {
                Files.list(assetStorageDir).use { entries ->
                    entries.filter { it.fileName.toString() != "tmp" }.forEach { entry ->
                        Files.walk(entry).use { walk ->
                            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                        }
                    }
                }
            }
            for ((oldAssetId, temp) in archive.currentFiles) {
                val newId = idMaps[AssetsTable]?.get(oldAssetId) ?: continue
                installFile(temp, assets.fileForId(newId))
                files++
            }
            for ((oldRevId, temp) in archive.revFiles) {
                val newId = idMaps[AssetRevisionsTable]?.get(oldRevId) ?: continue
                installFile(temp, assets.revFileForId(newId))
                files++
            }
            for ((oldAssetId, temp) in archive.pendingFiles) {
                val newId = idMaps[AssetsTable]?.get(oldAssetId) ?: continue
                installFile(temp, assets.pendingFileForId(newId))
                files++
            }

            sites.invalidateCache() // the sites table was replaced; host resolution must reload
            settings.invalidateCache() // app_settings rows were replaced underneath the cache
            searchIndex.reindexMissing() // rebuild any missing index rows across all restored sites
            val versionGapNote = if (archive.manifest.schemaVersion < currentSchema) {
                " This backup was taken at schema version ${archive.manifest.schemaVersion}; this server is at " +
                    "$currentSchema, so restored data was brought forward (new columns took their defaults)."
            } else {
                ""
            }
            return "Full restore complete: $rows rows across ${archive.tables.size} tables and $files asset files. " +
                "All users (including you) must sign in again.$versionGapNote"
        } finally {
            withContext(Dispatchers.IO) {
                (archive.currentFiles.values + archive.revFiles.values + archive.pendingFiles.values)
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private suspend fun installFile(from: Path, to: Path) = withContext(Dispatchers.IO) {
        Files.createDirectories(to.parent)
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Reads a full archive (blocking IO): manifest, db table dumps, and all asset bytes to temp files. */
    private fun readFullArchive(zipPath: Path): FullArchive = ZipFile(zipPath.toFile()).use { zip ->
        val budget = DecompressionBudget(MAX_RESTORE_DECOMPRESSED_BYTES)
        val manifest = readManifest(zip, budget)
        require(manifest.scope == SCOPE_FULL) { "Expected a full backup but this is '${manifest.scope}'." }
        val tables = mutableMapOf<String, JsonArray>()
        val currentFiles = mutableMapOf<Long, Path>()
        val revFiles = mutableMapOf<Long, Path>()
        val pendingFiles = mutableMapOf<Long, Path>()
        val entries = zip.entries().toList().filter { !it.isDirectory }
        require(entries.size <= MAX_RESTORE_ENTRIES) { "This backup archive has too many entries to restore." }
        for (entry in entries) {
            val name = entry.name
            when {
                name.startsWith(DB_PREFIX) && name.endsWith(".json") -> {
                    val table = name.removePrefix(DB_PREFIX).removeSuffix(".json")
                    tables[table] = json.decodeFromString<JsonArray>(zip.readText(entry, budget))
                }
                // All under files/, keyed by row id so cross-site path collisions can't clobber; the
                // readable assets/ tree is ignored on full restore (informational only).
                name.startsWith(CURRENT_FILES_PREFIX) ->
                    name.removePrefix(CURRENT_FILES_PREFIX).toLongOrNull()?.let { currentFiles[it] = extract(zip, entry, budget) }
                name.startsWith(REV_FILES_PREFIX) ->
                    name.removePrefix(REV_FILES_PREFIX).toLongOrNull()?.let { revFiles[it] = extract(zip, entry, budget) }
                name.startsWith(PENDING_FILES_PREFIX) ->
                    name.removePrefix(PENDING_FILES_PREFIX).toLongOrNull()?.let { pendingFiles[it] = extract(zip, entry, budget) }
                else -> {} // content/ tree, readable assets/ tree, fragments/navigation json: informational
            }
        }
        FullArchive(manifest, tables, currentFiles, revFiles, pendingFiles)
    }

    private fun extract(zip: ZipFile, entry: ZipEntry, budget: DecompressionBudget): Path {
        val temp = Files.createTempFile("wikikt-restore", ".part")
        BudgetedInputStream(zip.getInputStream(entry), budget).use { Files.copy(it, temp, StandardCopyOption.REPLACE_EXISTING) }
        return temp
    }

    // --- Generic table dump/insert ---

    /** All backed-up tables, parents before children so restore inserts satisfy FKs in order. */
    private val BACKUP_TABLES: List<Table> = listOf(
        SitesTable, // parent of all site-scoped content; site_id FKs remap through its id map
        UsersTable, GroupsTable, GroupPermissionsTable, UserGroupsTable,
        GroupPageRulesTable, GroupPageRuleRolesTable, GroupPageRuleSitesTable, GroupPageRuleLocalesTable,
        PagesTable, PageTagsTable, PageViewAclTable, PageEditAclTable, PageAliasesTable,
        PageRevisionsTable, PageStagedTable,
        AssetsTable, AssetRevisionsTable, AssetScheduledTable,
        FragmentsTable, NavMenusTable, NavItemsTable, ApiKeysTable, AppSettingsTable,
    )

    private suspend fun dumpAllTables(): Map<String, JsonArray> = suspendTransaction(database) {
        val result = LinkedHashMap<String, JsonArray>()
        for (table in BACKUP_TABLES) {
            result[table.tableName] = JsonArray(table.selectAll().map { rowToJson(table, it) }.toList())
        }
        result
    }

    private fun rowToJson(table: Table, row: ResultRow): JsonObject = buildJsonObject {
        for (col in table.columns) {
            val raw = row[col]
            val value = if (raw is EntityID<*>) raw.value else raw
            val prim = when (value) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(value)
                is UInt -> JsonPrimitive(value.toLong())
                is Number -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
            put(col.name, prim)
        }
    }

    /** Inserts dumped rows with fresh ids, recording old→new in [idMaps] for FK remapping. */
    private suspend fun insertRows(table: Table, rows: JsonArray, idMaps: MutableMap<Table, MutableMap<Long, UInt>>) {
        val idTable = table as? UIntIdTable
        val map = idTable?.let { idMaps.getOrPut(table) { mutableMapOf() } }
        for (element in rows) {
            val obj = element.jsonObject
            val oldId = idTable?.let { (obj[it.id.name] as? JsonPrimitive)?.long }
            val inserted = table.insert { stmt ->
                for (col in table.columns) {
                    if (idTable != null && col == idTable.id) continue // fresh id from auto-increment
                    val el = obj[col.name] ?: continue // absent column: leave to default
                    @Suppress("UNCHECKED_CAST")
                    stmt[col as Column<Any?>] = decodeColumn(col, el, idMaps)
                }
            }
            if (map != null && oldId != null) map[oldId] = inserted[idTable.id].value
        }
    }

    /** Decodes a dumped JSON value back to the column's Kotlin type, remapping FK references. */
    private fun decodeColumn(col: Column<*>, element: JsonElement, idMaps: Map<Table, Map<Long, UInt>>): Any? {
        if (element is JsonNull) return null
        val prim = element.jsonPrimitive
        // Reference columns carry a parent-row id: translate through the parent's old→new map.
        col.referee?.let { referee ->
            val old = prim.long
            val newId = idMaps[referee.table]?.get(old) ?: old.toUInt()
            val parent = referee.table as? UIntIdTable ?: return newId
            return EntityID(newId, parent) // reference columns hold EntityID values, not raw ids
        }
        val base = (col.columnType as? EntityIDColumnType<*>)?.idColumn?.columnType ?: col.columnType
        return when (base) {
            is BooleanColumnType -> prim.content.toBoolean()
            is UIntegerColumnType -> prim.long.toUInt()
            is IntegerColumnType -> prim.long.toInt()
            is LongColumnType -> prim.long
            else -> prim.content
        }
    }

    // --- Shared helpers ---

    private fun readManifest(zip: ZipFile, budget: DecompressionBudget): BackupManifest {
        val manifestEntry = zip.getEntry(MANIFEST_ENTRY)
            ?: throw IllegalArgumentException("Not a WikiKT backup: $MANIFEST_ENTRY is missing.")
        val manifest = json.decodeFromString<BackupManifest>(zip.readText(manifestEntry, budget))
        require(manifest.format == MANIFEST_FORMAT) { "Not a WikiKT backup (format '${manifest.format}')." }
        return manifest
    }

    /** Maps a `content/`-relative name to (locale, path, format); null when it isn't a page file. */
    private fun pageTarget(rel: String): Triple<String, String, ContentFormat>? {
        val locale = rel.substringBefore('/')
        val rest = rel.substringAfter('/', "")
        if (rest.isEmpty() || !isLocaleSegment(locale)) return null
        return when {
            rest.endsWith(".md") -> Triple(locale, rest.removeSuffix(".md"), ContentFormat.MARKDOWN)
            rest.endsWith(".html") -> Triple(locale, rest.removeSuffix(".html"), ContentFormat.HTML)
            else -> null
        }
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun ZipFile.readText(entry: ZipEntry, budget: DecompressionBudget): String =
        BudgetedInputStream(getInputStream(entry), budget).use { it.readBytes().toString(StandardCharsets.UTF_8) }

    companion object {
        const val MANIFEST_FORMAT = "wikikt-backup"
        const val SCOPE_CONTENT = "content"
        const val SCOPE_FULL = "full"
        const val MANIFEST_ENTRY = "manifest.json"
        const val FRAGMENTS_ENTRY = "fragments.json"
        const val NAVIGATION_ENTRY = "navigation.json"
        const val CONTENT_PREFIX = "content/"
        const val ASSETS_PREFIX = "assets/"
        const val DB_PREFIX = "db/"
        const val CURRENT_FILES_PREFIX = "files/assets/"
        const val REV_FILES_PREFIX = "files/asset-revisions/"
        const val PENDING_FILES_PREFIX = "files/asset-pending/"

        /** Sanity cap on uploaded backup archives (admin-only endpoint, but bounded anyway). */
        const val MAX_RESTORE_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024

        /**
         * Cap on the *decompressed* size of a restore archive. The upload is already bounded
         * compressed ([MAX_RESTORE_UPLOAD_BYTES]); this bounds what it expands to, so a "zip bomb"
         * (a tiny archive that inflates to hundreds of GB) can't exhaust disk or heap. Set a few
         * times the upload cap so legitimate, well-compressing backups (mostly text/JSON) restore
         * fine while pathological ratios are refused.
         */
        const val MAX_RESTORE_DECOMPRESSED_BYTES = 8L * 1024 * 1024 * 1024

        /** Backstop on entry count, independent of size (defends the many-tiny-entries case). */
        const val MAX_RESTORE_ENTRIES = 1_000_000
    }
}

/**
 * Caps the total decompressed bytes read from one restore archive. Every byte pulled out of the zip
 * is charged here; once the budget is exceeded the read aborts, so a zip bomb can't be fully
 * inflated. Shared across all entries of a single archive read.
 */
internal class DecompressionBudget(private val maxBytes: Long) {
    private var used = 0L
    fun spend(n: Long) {
        used += n
        require(used <= maxBytes) {
            "This backup archive is too large to restore (it decompresses to more than " +
                "${maxBytes / (1024 * 1024)} MB). Restore aborted."
        }
    }
}

/** Wraps a zip-entry stream so every decompressed byte is charged to [budget] as it is read. */
internal class BudgetedInputStream(
    private val delegate: InputStream,
    private val budget: DecompressionBudget,
) : InputStream() {
    override fun read(): Int = delegate.read().also { if (it >= 0) budget.spend(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        delegate.read(b, off, len).also { if (it > 0) budget.spend(it.toLong()) }

    override fun close() = delegate.close()
}
