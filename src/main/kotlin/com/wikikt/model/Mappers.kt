package com.wikikt.model

import com.wikikt.db.ContentFormat
import com.wikikt.db.GroupsTable
import com.wikikt.db.FragmentsTable
import com.wikikt.db.NavItemsTable
import com.wikikt.db.NavMenusTable
import com.wikikt.db.PageEditAclTable
import com.wikikt.db.PageRevisionsTable
import com.wikikt.db.PageViewAclTable
import com.wikikt.db.PagesTable
import com.wikikt.db.SitesTable
import com.wikikt.db.UsersTable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

fun Long.toIsoString(): String =
    ISO_FORMATTER.format(Instant.ofEpochMilli(this).atOffset(ZoneOffset.UTC))

fun UserRecord.toDto(groupIds: List<UInt> = emptyList()): UserDto = UserDto(
    id = id.toString(),
    username = username,
    email = email,
    createdAt = createdAt.toIsoString(),
    groupIds = groupIds.map { it.toString() },
)

fun GroupRecord.toDto(): GroupDto = GroupDto(
    id = id.toString(),
    name = name,
    isSystem = isSystem,
    permissions = permissions,
)

fun PageRecord.toDto(viewAcl: PageAcl = PageAcl(), editAcl: PageAcl = PageAcl()): PageDto = PageDto(
    id = id.toString(),
    locale = locale,
    path = path,
    title = title,
    description = description,
    metaRobots = metaRobots,
    content = content,
    contentFormat = contentFormat.name,
    published = published,
    publishAt = publishAt?.toIsoString(),
    tags = tags,
    createdAt = createdAt.toIsoString(),
    updatedAt = updatedAt.toIsoString(),
    updatedBy = updatedBy?.toString(),
    viewAcl = PageAclDto(
        groupIds = viewAcl.groupIds.map { it.toString() },
        userIds = viewAcl.userIds.map { it.toString() },
    ),
    editAcl = PageAclDto(
        groupIds = editAcl.groupIds.map { it.toString() },
        userIds = editAcl.userIds.map { it.toString() },
    ),
)

fun PageAclDto.toModel(): PageAcl = PageAcl(
    groupIds = groupIds.map { it.toUInt() }.toSet(),
    userIds = userIds.map { it.toUInt() }.toSet(),
)

fun org.jetbrains.exposed.v1.core.ResultRow.toUserRecord(): UserRecord = UserRecord(
    id = this[UsersTable.id].value,
    username = this[UsersTable.username],
    passwordHash = this[UsersTable.passwordHash],
    email = this[UsersTable.email],
    createdAt = this[UsersTable.createdAt],
    timezone = this[UsersTable.timezone],
    dateFormatShort = this[UsersTable.dateFormatShort],
    dateFormatLong = this[UsersTable.dateFormatLong],
    timeFormat = this[UsersTable.timeFormat],
    displayName = this[UsersTable.displayName],
    jobTitle = this[UsersTable.jobTitle],
    location = this[UsersTable.location],
    theme = this[UsersTable.theme],
    status = runCatching { com.wikikt.db.UserStatus.valueOf(this[UsersTable.status]) }
        .getOrDefault(com.wikikt.db.UserStatus.ACTIVE),
)

fun org.jetbrains.exposed.v1.core.ResultRow.toSiteRecord(): SiteRecord = SiteRecord(
    id = this[SitesTable.id].value,
    name = this[SitesTable.name],
    hostname = this[SitesTable.hostname],
    isCatchAll = this[SitesTable.isCatchAll],
    position = this[SitesTable.position],
    createdAt = this[SitesTable.createdAt],
)

// Global permission verbs live in a separate table, so the caller loads them and passes them in.
fun org.jetbrains.exposed.v1.core.ResultRow.toGroupRecord(permissions: Set<String> = emptySet()): GroupRecord = GroupRecord(
    id = this[GroupsTable.id].value,
    name = this[GroupsTable.name],
    isSystem = this[GroupsTable.isSystem],
    permissions = permissions,
)

// Tags live in a separate table; PageService fills them in after building the record (default empty).
fun org.jetbrains.exposed.v1.core.ResultRow.toPageRecord(): PageRecord = PageRecord(
    id = this[PagesTable.id].value,
    siteId = this[PagesTable.siteId].value,
    locale = this[PagesTable.locale],
    path = this[PagesTable.path],
    title = this[PagesTable.title],
    description = this[PagesTable.description],
    metaRobots = this[PagesTable.metaRobots],
    content = this[PagesTable.content],
    contentFormat = ContentFormat.valueOf(this[PagesTable.contentFormat]),
    published = this[PagesTable.published],
    publishAt = this[PagesTable.publishAt],
    createdAt = this[PagesTable.createdAt],
    updatedAt = this[PagesTable.updatedAt],
    updatedBy = this[PagesTable.updatedBy]?.value,
    infobox = this[PagesTable.infobox],
    customCss = this[PagesTable.customCss],
    customJs = this[PagesTable.customJs],
)

/**
 * Maps a row from `PageService.search`'s column projection (`SEARCH_PAGE_COLUMNS`) to a PageRecord.
 * The heavy text fields — content, infobox, customCss, customJs — are NOT selected there (search
 * never renders them), so they are left empty/null here. Do not read those fields off a record that
 * came from search; every other field is populated normally.
 */
fun org.jetbrains.exposed.v1.core.ResultRow.toSearchPageRecord(): PageRecord = PageRecord(
    id = this[PagesTable.id].value,
    siteId = this[PagesTable.siteId].value,
    locale = this[PagesTable.locale],
    path = this[PagesTable.path],
    title = this[PagesTable.title],
    description = this[PagesTable.description],
    metaRobots = this[PagesTable.metaRobots],
    content = "",
    contentFormat = ContentFormat.valueOf(this[PagesTable.contentFormat]),
    published = this[PagesTable.published],
    publishAt = this[PagesTable.publishAt],
    createdAt = this[PagesTable.createdAt],
    updatedAt = this[PagesTable.updatedAt],
    updatedBy = this[PagesTable.updatedBy]?.value,
)

fun org.jetbrains.exposed.v1.core.ResultRow.toPageRevisionRecord(): PageRevisionRecord = PageRevisionRecord(
    id = this[PageRevisionsTable.id].value,
    pageId = this[PageRevisionsTable.pageId].value,
    title = this[PageRevisionsTable.title],
    description = this[PageRevisionsTable.description],
    content = this[PageRevisionsTable.content],
    contentFormat = ContentFormat.valueOf(this[PageRevisionsTable.contentFormat]),
    revisionNumber = this[PageRevisionsTable.revisionNumber],
    createdAt = this[PageRevisionsTable.createdAt],
    createdBy = this[PageRevisionsTable.createdBy]?.value,
    infobox = this[PageRevisionsTable.infobox],
)

fun org.jetbrains.exposed.v1.core.ResultRow.toPageStagedRecord(): PageStagedRecord = PageStagedRecord(
    id = this[com.wikikt.db.PageStagedTable.id].value,
    pageId = this[com.wikikt.db.PageStagedTable.pageId].value,
    title = this[com.wikikt.db.PageStagedTable.title],
    description = this[com.wikikt.db.PageStagedTable.description],
    content = this[com.wikikt.db.PageStagedTable.content],
    contentFormat = ContentFormat.valueOf(this[com.wikikt.db.PageStagedTable.contentFormat]),
    publishAt = this[com.wikikt.db.PageStagedTable.publishAt],
    updatedAt = this[com.wikikt.db.PageStagedTable.updatedAt],
    updatedBy = this[com.wikikt.db.PageStagedTable.updatedBy]?.value,
    infobox = this[com.wikikt.db.PageStagedTable.infobox],
)

fun org.jetbrains.exposed.v1.core.ResultRow.toNavMenuRecord(): NavMenuRecord = NavMenuRecord(
    id = this[NavMenusTable.id].value,
    siteId = this[NavMenusTable.siteId].value,
    scope = this[NavMenusTable.scope],
    position = this[NavMenusTable.position],
)

fun org.jetbrains.exposed.v1.core.ResultRow.toNavItemRecord(): NavItemRecord = NavItemRecord(
    id = this[NavItemsTable.id].value,
    menuId = this[NavItemsTable.menuId].value,
    position = this[NavItemsTable.position],
    isHeader = this[NavItemsTable.isHeader],
    isDivider = this[NavItemsTable.isDivider],
    depth = this[NavItemsTable.depth],
    label = this[NavItemsTable.label],
    icon = this[NavItemsTable.icon],
    target = this[NavItemsTable.target],
)

fun org.jetbrains.exposed.v1.core.ResultRow.toAssetRecord(): AssetRecord = AssetRecord(
    id = this[com.wikikt.db.AssetsTable.id].value,
    siteId = this[com.wikikt.db.AssetsTable.siteId].value,
    locale = this[com.wikikt.db.AssetsTable.locale],
    path = this[com.wikikt.db.AssetsTable.path],
    originalFilename = this[com.wikikt.db.AssetsTable.originalFilename],
    mime = this[com.wikikt.db.AssetsTable.mime],
    sizeBytes = this[com.wikikt.db.AssetsTable.sizeBytes],
    createdAt = this[com.wikikt.db.AssetsTable.createdAt],
    updatedAt = this[com.wikikt.db.AssetsTable.updatedAt],
    uploadedBy = this[com.wikikt.db.AssetsTable.uploadedBy]?.value,
    altText = this[com.wikikt.db.AssetsTable.altText],
    description = this[com.wikikt.db.AssetsTable.description],
)

fun org.jetbrains.exposed.v1.core.ResultRow.toAssetScheduledRecord(): AssetScheduledRecord = AssetScheduledRecord(
    id = this[com.wikikt.db.AssetScheduledTable.id].value,
    assetId = this[com.wikikt.db.AssetScheduledTable.assetId].value,
    mime = this[com.wikikt.db.AssetScheduledTable.mime],
    sizeBytes = this[com.wikikt.db.AssetScheduledTable.sizeBytes],
    originalFilename = this[com.wikikt.db.AssetScheduledTable.originalFilename],
    publishAt = this[com.wikikt.db.AssetScheduledTable.publishAt],
    createdAt = this[com.wikikt.db.AssetScheduledTable.createdAt],
    createdBy = this[com.wikikt.db.AssetScheduledTable.createdBy]?.value,
)

fun org.jetbrains.exposed.v1.core.ResultRow.toAssetRevisionRecord(): AssetRevisionRecord = AssetRevisionRecord(
    id = this[com.wikikt.db.AssetRevisionsTable.id].value,
    assetId = this[com.wikikt.db.AssetRevisionsTable.assetId].value,
    versionNumber = this[com.wikikt.db.AssetRevisionsTable.versionNumber],
    originalFilename = this[com.wikikt.db.AssetRevisionsTable.originalFilename],
    mime = this[com.wikikt.db.AssetRevisionsTable.mime],
    sizeBytes = this[com.wikikt.db.AssetRevisionsTable.sizeBytes],
    createdAt = this[com.wikikt.db.AssetRevisionsTable.createdAt],
    createdBy = this[com.wikikt.db.AssetRevisionsTable.createdBy]?.value,
)

fun org.jetbrains.exposed.v1.core.ResultRow.toFragmentRecord(): FragmentRecord = FragmentRecord(
    id = this[FragmentsTable.id].value,
    siteId = this[FragmentsTable.siteId].value,
    locale = this[FragmentsTable.locale],
    key = this[FragmentsTable.key],
    title = this[FragmentsTable.title],
    content = this[FragmentsTable.content],
    createdAt = this[FragmentsTable.createdAt],
    updatedAt = this[FragmentsTable.updatedAt],
    updatedBy = this[FragmentsTable.updatedBy]?.value,
)

fun normalizePagePath(path: String): String =
    path.trim('/').takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Page path cannot be empty")

/** Normalizes free-form tag input (comma/whitespace-separated) to a clean, lowercased, deduped list. */
fun normalizeTags(raw: Iterable<String>): List<String> =
    raw.map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .map { it.take(100) }
        .distinct()

/** Parses a comma-separated tag string from a form field into normalized tags. */
fun parseTags(raw: String?): List<String> =
    if (raw.isNullOrBlank()) emptyList() else normalizeTags(raw.split(','))

fun parseId(value: String): UInt = value.toUIntOrNull()
    ?: throw IllegalArgumentException("Invalid ID: $value")

/**
 * Multi-character path segments reserved for app routes, so pages/assets can't be created under them
 * and the wiki catch-all skips them. The *entire single-character namespace* is reserved separately
 * (see [isReservedFirstSegment]): every one-letter first segment is held for function routes whether or
 * not one is wired today. Wired ones mostly mirror WikiJS 2.x — a=admin, c=comments, e=editor, f=assets,
 * h=history, i=by-id, p=profile, t=tags, u=upload/API, w=personal-wiki — except `s`, which WikiJS uses
 * for page source but we use for **search** (WikiKT has no source view yet; `r` is held for one if it
 * ever lands). This set is only the specific longer names we also hold: routes we use today, plus
 * `_assets`, `favicon`(.ico), `graphql`, `healthz`, `register` held for possible future use.
 *
 * NOTE: `home` is deliberately NOT reserved, although it is special. It needs to be a real, editable page
 * that `/` and `/{locale}` redirect to. Its canonical URL is `/{locale}/home`.
 */
val RESERVED_PATH_SEGMENTS = setOf(
    "login", "logout", "static", "new", "preview",
    // Held for future use if needed; no route wired yet.
    "_assets", "favicon", "graphql", "healthz", "register",
)

/**
 * Whether [segment] is reserved as the *first* segment of a page/asset path because it collides with the
 * app's route namespace: every single-character segment (the one-letter space is held wholesale for
 * function routes like `/a`, `/e`, `/s`) plus the specific multi-character [RESERVED_PATH_SEGMENTS].
 * Case-insensitive. This is the single source of truth shared by path validation, the wiki catch-all,
 * and internal-link resolution.
 */
fun isReservedFirstSegment(segment: String): Boolean =
    segment.length == 1 || segment.lowercase() in RESERVED_PATH_SEGMENTS

private val LOCALE_LIKE = Regex("^[a-z]{2}(-[a-z]{2})?$", RegexOption.IGNORE_CASE)

/**
 * Canonicalizes a locale code to `ll` or `ll-RR` (language lower-case, region upper-case), matching
 * the form the URL router recognizes (see WikiPath.isLocaleSegment). Returns null if [input] isn't a
 * valid 2-letter language with an optional 2-letter region.
 */
fun normalizeLocale(input: String): String? {
    val t = input.trim()
    if (!LOCALE_LIKE.matches(t)) return null
    val parts = t.split("-")
    return if (parts.size == 2) "${parts[0].lowercase()}-${parts[1].uppercase()}" else parts[0].lowercase()
}

// Spaces and URL-unsafe punctuation/symbols are disallowed in a path segment (use dashes instead).
private val UNSAFE_SEGMENT = Regex("""[\s\\?#\[\]{}<>"'`|^~:;,!@$%&*()=+]""")

/**
 * Validates a page or asset path against the naming rules (called on create). The first
 * segment may not be reserved, a single/two-letter name, or a locale code (it disambiguates routing
 * and locale); no segment may contain spaces or unsafe characters; pages disallow periods entirely,
 * while assets ([allowExtension]) permit one trailing extension on the final segment.
 * Throws [IllegalArgumentException] with a user-facing message on the first violation.
 */
fun validateWikiPath(path: String, allowExtension: Boolean) {
    val segments = path.split('/').filter { it.isNotEmpty() }
    require(segments.isNotEmpty()) { "Path cannot be empty." }
    segments.forEachIndexed { index, seg ->
        require(!UNSAFE_SEGMENT.containsMatchIn(seg)) {
            "'$seg' contains spaces or unsafe characters — use dashes and letters/numbers only."
        }
        if (allowExtension && index == segments.lastIndex) {
            require(seg.count { it == '.' } <= 1 && !seg.startsWith(".") && !seg.endsWith(".")) {
                "'$seg' is not a valid file name."
            }
        } else {
            require(!seg.contains('.')) { "'$seg' may not contain a period." }
        }
    }
    val first = segments.first()
    require(!isReservedFirstSegment(first)) { "Path may not start with the reserved name '$first'." }
    require(first.length >= 3) { "The first path segment must be at least 3 characters (1–2 letter names are reserved)." }
    require(!LOCALE_LIKE.matches(first)) { "The first path segment may not look like a locale code (e.g. 'en' or 'fr-ca')." }
}

/**
 * Strict normalizer for an asset's virtual path (which becomes part of a served URL). Unlike
 * [normalizePagePath] this rejects path-traversal and control characters. Does NOT check reserved
 * first segments — that's the routing layer's job (it owns the reserved set).
 */
fun normalizeAssetPath(raw: String): String {
    val segments = raw.split('/').map { it.trim() }.filter { it.isNotEmpty() }
    require(segments.isNotEmpty()) { "Asset path cannot be empty" }
    for (s in segments) {
        require(s != "." && s != "..") { "Asset path may not contain '.' or '..' segments" }
        require(!s.contains('\\')) { "Asset path may not contain backslashes" }
        require(s.none { it.isISOControl() }) { "Asset path may not contain control characters" }
    }
    return segments.joinToString("/")
}

/** Turns an uploaded filename into a safe, lowercased path segment, preserving a sane extension. */
fun slugFilename(name: String): String {
    val bare = name.substringAfterLast('/').substringAfterLast('\\')
    val dot = bare.lastIndexOf('.')
    val base = (if (dot > 0) bare.substring(0, dot) else bare)
        .lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').ifEmpty { "file" }
    val ext = (if (dot > 0) bare.substring(dot + 1) else "")
        .lowercase().replace(Regex("[^a-z0-9]+"), "")
    return if (ext.isNotEmpty()) "$base.$ext" else base
}

fun nowMillis(): Long = System.currentTimeMillis()
