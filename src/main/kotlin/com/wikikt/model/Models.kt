package com.wikikt.model

import com.wikikt.db.ContentFormat
import com.wikikt.db.UserStatus
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val email: String? = null,
    val createdAt: String,
    val groupIds: List<String> = emptyList(),
)

@Serializable
data class CreateUserRequest(
    val username: String,
    val password: String,
    val email: String? = null,
    val groupIds: List<String> = emptyList(),
)

@Serializable
data class UpdateUserRequest(
    val username: String? = null,
    val password: String? = null,
    val email: String? = null,
    // Non-null replaces the display name (blank clears it); null leaves it unchanged. Set only by the
    // admin user editor — it deliberately bypasses the self-service uniqueness rule so an administrator
    // can assign a duplicate when needed.
    val displayName: String? = null,
    val groupIds: List<String>? = null,
)

@Serializable
data class GroupDto(
    val id: String,
    val name: String,
    val isSystem: Boolean,
    /** Global (admin) permission verbs held by this group. */
    val permissions: Set<String> = emptySet(),
)

@Serializable
data class CreateGroupRequest(
    val name: String,
    /** The group's global (admin) permission verbs — see AccessResolver.ADMIN_VERBS. */
    val permissions: Set<String> = emptySet(),
)

@Serializable
data class UpdateGroupRequest(
    val name: String? = null,
    /** Replacement set of global (admin) permission verbs; null leaves them unchanged. */
    val permissions: Set<String>? = null,
)

@Serializable
data class PageAclDto(
    val groupIds: List<String> = emptyList(),
    val userIds: List<String> = emptyList(),
)

/** Lightweight page identity for link-path autocomplete (no content). */
@Serializable
data class PagePathDto(
    val locale: String,
    val path: String,
    val title: String,
)

/** A single search hit: page identity plus a text snippet (no full content). */
@Serializable
data class SearchResultDto(
    val locale: String,
    val path: String,
    val title: String,
    val description: String? = null,
    val snippet: String,
    val tags: List<String> = emptyList(),
    val url: String,
    /** Where the page lives, for display: its parent's friendly name (or "Home" at the top level). */
    val parentLabel: String,
)

@Serializable
data class PageDto(
    val id: String,
    val locale: String,
    val path: String,
    val title: String,
    val description: String? = null,
    val metaRobots: String? = null,
    val content: String,
    val contentFormat: String,
    val published: Boolean = true,
    val publishAt: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val updatedBy: String? = null,
    val viewAcl: PageAclDto = PageAclDto(),
    val editAcl: PageAclDto = PageAclDto(),
)

@Serializable
data class CreatePageRequest(
    val locale: String,
    val path: String,
    val title: String,
    val description: String? = null,
    val metaRobots: String? = null,
    val content: String,
    val contentFormat: String = ContentFormat.MARKDOWN.name,
    val published: Boolean = true,
    val publishAt: Long? = null,
    val tags: List<String> = emptyList(),
    // Infobox instance data as a JSON object string (field-name → value); null = none. See InfoboxService.
    val infobox: String? = null,
    // Per-page custom code injected into the page <head> on view (see PagesTable.customCss/customJs).
    val customCss: String? = null,
    val customJs: String? = null,
    val viewAcl: PageAclDto = PageAclDto(),
    val editAcl: PageAclDto = PageAclDto(),
)

@Serializable
data class UpdatePageRequest(
    val title: String? = null,
    val description: String? = null,
    val metaRobots: String? = null,
    val content: String? = null,
    val contentFormat: String? = null,
    val published: Boolean? = null,
    val publishAt: Long? = null,
    val tags: List<String>? = null,
    // Null = leave the page's infobox unchanged; a value (possibly blank → cleared) replaces it.
    val infobox: String? = null,
    // Null = leave unchanged; a value (blank → cleared) replaces the stored per-page custom code.
    val customCss: String? = null,
    val customJs: String? = null,
    val viewAcl: PageAclDto? = null,
    val editAcl: PageAclDto? = null,
)

@Serializable
data class PageAliasDto(
    val id: String,
    val aliasPath: String,
    val locale: String?,
    val pageId: String,
)

@Serializable
data class CreatePageAliasRequest(
    val aliasPath: String,
    val locale: String? = null,
    val pageId: String,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

data class UserRecord(
    val id: UInt,
    val username: String,
    val passwordHash: String,
    val email: String?,
    val createdAt: Long,
    val timezone: String? = null,
    val dateFormatShort: String? = null,
    val dateFormatLong: String? = null,
    val timeFormat: String? = null,
    val displayName: String? = null,
    val jobTitle: String? = null,
    val location: String? = null,
    val theme: String? = null,
    val status: UserStatus = UserStatus.ACTIVE,
)

data class GroupRecord(
    val id: UInt,
    val name: String,
    val isSystem: Boolean,
    /** Global (admin) permission verbs held by this group — see AccessResolver.ADMIN_VERBS. */
    val permissions: Set<String>,
)

/** A site hosted by this instance; content is partitioned by [id]. */
data class SiteRecord(
    val id: UInt,
    val name: String,
    val hostname: String?,
    val isCatchAll: Boolean,
    val position: Int,
    val createdAt: Long,
)

data class PageRecord(
    val id: UInt,
    val siteId: UInt,
    val locale: String,
    val path: String,
    val title: String,
    val description: String?,
    val metaRobots: String? = null,
    val content: String,
    val contentFormat: ContentFormat,
    val published: Boolean,
    val publishAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: UInt?,
    val tags: List<String> = emptyList(),
    val infobox: String? = null,
    // Per-page custom code injected into the page <head> on view (see PagesTable.customCss/customJs).
    val customCss: String? = null,
    val customJs: String? = null,
)

data class PageAcl(
    val groupIds: Set<UInt> = emptySet(),
    val userIds: Set<UInt> = emptySet(),
) {
    val isEmpty: Boolean get() = groupIds.isEmpty() && userIds.isEmpty()
}

data class PageRevisionRecord(
    val id: UInt,
    val pageId: UInt,
    val title: String,
    val description: String?,
    val content: String,
    val contentFormat: ContentFormat,
    val revisionNumber: Int,
    val createdAt: Long,
    val createdBy: UInt?,
    val infobox: String? = null,
)

/** A single staged (future) content version of a live page. */
data class PageStagedRecord(
    val id: UInt,
    val pageId: UInt,
    val title: String,
    val description: String?,
    val content: String,
    val contentFormat: ContentFormat,
    val publishAt: Long?,
    val updatedAt: Long,
    val updatedBy: UInt?,
    val infobox: String? = null,
)

// --- Infobox templates ---------------------------------------------------------------------------

/**
 * One field in an infobox template. [type] is string | select | boolean | array; [options] lists the
 * allowed values for a select/array. String/select/array-item values may contain inline Markdown
 * (bold/italic/links); block markup is stripped on render.
 */
@Serializable
data class InfoboxFieldDef(
    val name: String,
    val label: String,
    val type: String = "string",
    val required: Boolean = false,
    val help: String? = null,
    val options: List<String> = emptyList(),
)

/** An admin-defined infobox template (its fields decoded from the stored JSON). */
data class InfoboxTemplate(
    val id: UInt,
    val siteId: UInt,
    val slug: String,
    val name: String,
    val description: String?,
    val fields: List<InfoboxFieldDef>,
)

data class NavMenuRecord(
    val id: UInt,
    val siteId: UInt,
    val scope: String,
    val position: Int,
)

data class NavItemRecord(
    val id: UInt,
    val menuId: UInt,
    val position: Int,
    val isHeader: Boolean,
    val isDivider: Boolean,
    val depth: Int,
    val label: String,
    val icon: String?,
    val target: String?,
)

data class AssetRecord(
    val id: UInt,
    val siteId: UInt,
    val locale: String,
    val path: String,
    val originalFilename: String,
    val mime: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val uploadedBy: UInt?,
    val altText: String? = null,
    val description: String? = null,
)

/** A normalized reference to an asset by its identity (locale + path), used by the "used by" scan. */
data class AssetRef(val locale: String, val path: String)

/** Lightweight asset identity for the asset-browser picker (no usage scan). */
@Serializable
data class AssetPickerDto(
    val id: String,
    val locale: String,
    val path: String,
    val url: String,
    val mime: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val hasAlt: Boolean = false,
)

/** Result of an AJAX asset upload (from the picker): how many landed, which paths already existed. */
@Serializable
data class UploadResultDto(
    val uploaded: Int,
    val conflicts: List<String>,
    val errors: List<String>,
)

/** Asset row for the /f manager's embedded folder browser — adds the usage count. */
@Serializable
data class AssetAdminDto(
    val id: String,
    val locale: String,
    val path: String,
    val url: String,
    val mime: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val usedBy: Int,
    val description: String = "",
)

/** A pending scheduled replacement of an asset's file. */
data class AssetScheduledRecord(
    val id: UInt,
    val assetId: UInt,
    val mime: String,
    val sizeBytes: Long,
    val originalFilename: String,
    val publishAt: Long,
    val createdAt: Long,
    val createdBy: UInt?,
)

/** A prior version of an asset's file, kept when the asset is replaced. */
data class AssetRevisionRecord(
    val id: UInt,
    val assetId: UInt,
    val versionNumber: Int,
    val originalFilename: String,
    val mime: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val createdBy: UInt?,
)

data class FragmentRecord(
    val id: UInt,
    val siteId: UInt,
    val locale: String,
    val key: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: UInt?,
)

/** Whether a matching page rule grants (ALLOW) or removes (DENY) the rule's roles. */
enum class RuleEffect { ALLOW, DENY }

/**
 * How a page rule's [GroupPageRuleRecord.pattern] is matched against a page/asset path. Bridged
 * to [com.wikikt.service.AccessResolver.Match] in the permission layer.
 */
enum class RuleMatchType {
    /** The path begins with the pattern (empty pattern = the whole site): "START". */
    PREFIX,

    /** The path equals the pattern exactly: "EXACT". */
    EXACT,

    /** The path ends with the pattern: "END". */
    SUFFIX,

    /** The path matches the pattern as a regular expression: "REGEX". */
    REGEX,

    /** The page carries the pattern as a tag: "TAG". */
    TAG,
}

/**
 * A page rule owned by a group. [roles] are the content verbs it grants/denies (see
 * AccessResolver.CONTENT_VERBS). [sites] empty = all sites; [locales] empty = all locales. [position]
 * is display order only — resolution is by specificity, not order.
 */
data class GroupPageRuleRecord(
    val id: UInt,
    val groupId: UInt,
    val effect: RuleEffect,
    val matchType: RuleMatchType,
    val pattern: String,
    val roles: Set<String>,
    val sites: Set<UInt>,
    val locales: Set<String>,
    val position: Int,
)

/** A parsed nav item (from the text editor format), before it has a database id. */
data class NavItemInput(
    val isHeader: Boolean,
    val isDivider: Boolean = false,
    val depth: Int = 0,
    val label: String,
    val icon: String?,
    val target: String?,
)
