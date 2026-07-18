package com.wikikt.service

import com.wikikt.model.GroupPageRuleRecord
import com.wikikt.model.GroupRecord
import com.wikikt.model.PageRecord
import com.wikikt.model.RuleEffect
import com.wikikt.model.RuleMatchType

/**
 * The permission layer, backed by [AccessResolver]. Content access is granted only
 * by page rules (default-deny, scoped by site/path/locale); admin capabilities are global verbs.
 *
 * The `canX` method surface is kept stable so existing routes compile unchanged; route-level
 * refinement to precise per-verb/per-resource checks is a later phase. `manage:system` is root.
 */
class PermissionService(
    private val userService: UserService,
    private val groupService: GroupService,
    private val pageService: PageService,
    private val groupPageRuleService: GroupPageRuleService,
) {
    suspend fun effectiveGroups(userId: UInt?): List<GroupRecord> {
        if (userId == null) {
            return listOfNotNull(groupService.findByName(GUEST_GROUP))
        }
        // Authenticated users always get at least the baseline "User" group, so someone created
        // without any explicit group assignment still resolves against the default rules.
        val groups = userService.groupsForUser(userId)
        val userGroup = groupService.findByName(USER_GROUP)
        return if (userGroup != null && groups.none { it.id == userGroup.id }) {
            groups + userGroup
        } else {
            groups
        }
    }

    /** Flattens the user's effective groups into a resolver principal (global verbs + all page rules). */
    private suspend fun principal(userId: UInt?): AccessResolver.Principal {
        val groups = effectiveGroups(userId)
        val globalPermissions = groups.flatMap { it.permissions }.toSet()
        val rules = groupPageRuleService.rulesForGroups(groups.map { it.id }.toSet()).map { it.toAccessRule() }
        return AccessResolver.Principal(globalPermissions, rules)
    }

    /** Generic check of a content [permission] against an explicit resource (site/path/locale/tags). */
    suspend fun check(
        userId: UInt?,
        permission: String,
        siteId: UInt,
        locale: String,
        path: String,
        tags: Set<String> = emptySet(),
    ): Boolean = AccessResolver.check(principal(userId), permission, AccessResolver.Resource(siteId, locale, path, tags))

    // --- Content access (rules-only, default-deny, scoped by site/path/locale) ---

    suspend fun canViewPage(userId: UInt?, page: PageRecord): Boolean {
        val p = principal(userId)
        // A draft (unpublished) page is visible only to those who can write it.
        val verb = if (page.published) AccessResolver.Perm.READ_PAGES else AccessResolver.Perm.WRITE_PAGES
        return AccessResolver.check(p, verb, page.resource())
    }

    suspend fun canEditPage(userId: UInt?, page: PageRecord): Boolean =
        AccessResolver.check(principal(userId), AccessResolver.Perm.WRITE_PAGES, page.resource())

    /**
     * Filters [pages] to those [userId] may view, applying the same published/draft rule as [canViewPage]
     * (a draft needs write access). The principal is resolved once and reused, so a whole-site page list
     * can be filtered without re-querying per page — used to build the navigation site tree so it never
     * leaks the titles or paths of pages behind a DENY (or unpublished drafts) to a visitor.
     */
    suspend fun readablePages(userId: UInt?, pages: List<PageRecord>): List<PageRecord> =
        filterViewable(userId, pages) { it }

    /**
     * Filters [items] to those [userId] may view, applying the same published/draft rule as
     * [canViewPage] via the [PageRecord] each item is [pageOf]. The principal is resolved ONCE and
     * reused across all items — so filtering a list (e.g. search hits) costs no per-item permission
     * re-query. Prefer this over `list.filter { canViewPage(userId, it) }`, which re-resolves the
     * principal (and re-queries the user's groups/rules) for every element.
     */
    suspend fun <T> filterViewable(userId: UInt?, items: List<T>, pageOf: (T) -> PageRecord): List<T> {
        val p = principal(userId)
        return items.filter {
            val page = pageOf(it)
            val verb = if (page.published) AccessResolver.Perm.READ_PAGES else AccessResolver.Perm.WRITE_PAGES
            AccessResolver.check(p, verb, page.resource())
        }
    }

    /**
     * Coarse "is this user an editor somewhere" gate for the New-page affordance and alias creation.
     * The concrete create/save path enforces write:pages against the actual target resource.
     */
    suspend fun canCreatePages(userId: UInt?): Boolean =
        userId != null && hasContentAllow(userId, AccessResolver.Perm.WRITE_PAGES)

    /**
     * Coarse "may create a page somewhere on [siteId]" — for the New-page / Duplicate affordances, so
     * they don't show while viewing a site the user has no write grant for. The concrete save still
     * enforces write:pages against the exact target path.
     */
    suspend fun canCreatePagesOnSite(userId: UInt?, siteId: UInt): Boolean {
        if (userId == null) return false
        val p = principal(userId)
        if (AccessResolver.Perm.MANAGE_SYSTEM in p.globalPermissions) return true
        return p.rules.any {
            it.mode == AccessResolver.Mode.ALLOW &&
                AccessResolver.Perm.WRITE_PAGES in it.roles &&
                (it.sites.isEmpty() || siteId in it.sites)
        }
    }

    /** Coarse gate for the admin content-management views (/a/pages, fragments, asset manager). */
    suspend fun canManagePages(userId: UInt?): Boolean =
        userId != null && (
            hasContentAllow(userId, AccessResolver.Perm.WRITE_PAGES) ||
                hasContentAllow(userId, AccessResolver.Perm.WRITE_ASSETS)
            )

    /** Coarse gate for viewing revision history (a read:history rule must grant it). */
    suspend fun canViewHistory(userId: UInt?): Boolean =
        hasContentAllow(userId, AccessResolver.Perm.READ_HISTORY)

    /** Whether the user may view the revision history of a specific [page] (read:history on it). */
    suspend fun canViewHistory(userId: UInt?, page: PageRecord): Boolean =
        AccessResolver.check(principal(userId), AccessResolver.Perm.READ_HISTORY, page.resource())

    /** Coarse "can upload assets somewhere" gate for the uploader; per-file write:assets is enforced per path. */
    suspend fun canUploadAssets(userId: UInt?): Boolean =
        userId != null && hasContentAllow(userId, AccessResolver.Perm.WRITE_ASSETS)

    /**
     * Filters [assets] to those the user may read (read:assets against each asset's own site/locale/path).
     * The caller's principal is built once, so this doesn't re-query per asset the way calling [check] in a
     * loop would. Used so an asset list (picker / manager) never leaks the paths of assets behind a DENY.
     */
    suspend fun readableAssets(
        userId: UInt?,
        siteId: UInt,
        assets: List<com.wikikt.model.AssetRecord>,
    ): List<com.wikikt.model.AssetRecord> {
        val p = principal(userId)
        return assets.filter {
            AccessResolver.check(p, AccessResolver.Perm.READ_ASSETS, AccessResolver.Resource(siteId, it.locale, it.path))
        }
    }

    // --- Admin verbs (global) ---

    /**
     * Whether [userId] holds root (`manage:system`). Root bypasses the delegated-admin guardrails
     * that stop a limited admin (e.g. `manage:groups`/`manage:users`) from escalating to root — so
     * callers pass this into user/group mutations to authorize touching a system group or its members.
     */
    suspend fun isRoot(userId: UInt?): Boolean =
        userId != null && AccessResolver.Perm.MANAGE_SYSTEM in principal(userId).globalPermissions

    /**
     * Whether [userId] is a member of a system (root-bearing) group — i.e. a root account that only
     * another root may administer. Used to stop a delegated `manage:users` admin from taking over the
     * built-in admin via a password reset, a group swap, or an API key minted in its name.
     */
    suspend fun isSystemUser(userId: UInt): Boolean {
        val systemGroups = groupService.systemGroupIds()
        return systemGroups.isNotEmpty() && userService.groupIdsForUser(userId).any { it in systemGroups }
    }

    suspend fun canManageUsers(userId: UInt?): Boolean =
        userId != null && AccessResolver.check(principal(userId), AccessResolver.Perm.MANAGE_USERS)

    suspend fun canManageGroups(userId: UInt?): Boolean =
        userId != null && AccessResolver.check(principal(userId), AccessResolver.Perm.MANAGE_GROUPS)

    suspend fun canManageNavigation(userId: UInt?): Boolean =
        userId != null && AccessResolver.check(principal(userId), AccessResolver.Perm.MANAGE_NAVIGATION)

    /** Governs appearance / CSP / site-wide custom HTML+CSS — and per-page custom code (unsanitized). */
    suspend fun canManageTheme(userId: UInt?): Boolean =
        userId != null && AccessResolver.check(principal(userId), AccessResolver.Perm.MANAGE_THEME)

    /**
     * Whether the user can reach ANY part of the admin area — so the header shows the admin cog only
     * when there is something behind it. True for root, any admin verb, or any content-write grant
     * (content editors can reach /a/pages and the asset manager). One principal build.
     */
    suspend fun canAccessAdmin(userId: UInt?): Boolean {
        if (userId == null) return false
        val p = principal(userId)
        if (AccessResolver.Perm.MANAGE_SYSTEM in p.globalPermissions) return true
        if (p.globalPermissions.any { it in AccessResolver.ADMIN_VERBS }) return true
        return p.rules.any {
            it.mode == AccessResolver.Mode.ALLOW &&
                (AccessResolver.Perm.WRITE_PAGES in it.roles || AccessResolver.Perm.WRITE_ASSETS in it.roles)
        }
    }

    suspend fun canCreateApiKeys(userId: UInt?): Boolean {
        if (userId == null) return false
        val p = principal(userId)
        // user-managers can already mint keys for anyone via the admin area, so they always qualify.
        return AccessResolver.check(p, AccessResolver.Perm.CREATE_APIKEYS) ||
            AccessResolver.check(p, AccessResolver.Perm.MANAGE_USERS)
    }

    // --- Helpers ---

    /** Whether any of the user's rules ALLOW [verb] anywhere (coarse; ignores path/deny specificity). */
    private suspend fun hasContentAllow(userId: UInt?, verb: String): Boolean {
        val p = principal(userId)
        if (AccessResolver.Perm.MANAGE_SYSTEM in p.globalPermissions) return true
        return p.rules.any { it.mode == AccessResolver.Mode.ALLOW && verb in it.roles }
    }

    private fun PageRecord.resource() = AccessResolver.Resource(siteId, locale, path, tags.toSet())

    private fun GroupPageRuleRecord.toAccessRule() = AccessResolver.AccessRule(
        mode = if (effect == RuleEffect.DENY) AccessResolver.Mode.DENY else AccessResolver.Mode.ALLOW,
        roles = roles,
        sites = sites,
        locales = locales,
        match = when (matchType) {
            RuleMatchType.PREFIX -> AccessResolver.Match.START
            RuleMatchType.EXACT -> AccessResolver.Match.EXACT
            RuleMatchType.SUFFIX -> AccessResolver.Match.END
            RuleMatchType.REGEX -> AccessResolver.Match.REGEX
            RuleMatchType.TAG -> AccessResolver.Match.TAG
        },
        path = pattern,
    )

    companion object {
        const val GUEST_GROUP = "Guest"
        const val USER_GROUP = "User"
        const val ADMIN_GROUP = "Admin"
    }
}
