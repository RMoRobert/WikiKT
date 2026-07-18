package com.wikikt.service

/**
 * The WikiKT permission model: pure logic for access control.
 *
 * No database, no routing — so the exact semantics can be reviewed and unit tested before any
 * schema/route work lands (that's where a wrong decision would lock people out). Nothing wires
 * this into the live app yet; [PermissionService] is unchanged.
 *
 * Model structure:
 *
 *  - **Two verb families.** *Admin* verbs ([ADMIN_VERBS], e.g. `manage:users`, `manage:sites`) are
 *    **global**: a user holds them (or not) instance-wide. *Content* verbs ([CONTENT_VERBS], e.g.
 *    `read:pages`, `write:assets`) are **never global** — they are granted only by page rules.
 *  - **Content = rules-only, default-deny.** Access to a page/asset requires a matching ALLOW rule
 *    that isn't overridden by a winning DENY. No matching rule ⇒ denied. There is *no* separate
 *    "must also hold the verb globally" gate for content (that gate exists only for admin verbs).
 *    The "open by default" feel comes from a *seeded* broad ALLOW rule, not from absence of rules.
 *  - **Rules are scoped by site + path + locale.** `sites` empty = all sites; `locales` empty = all
 *    locales. Asset access is matched against the asset's own path, exactly like a page path.
 *  - **No per-page ACLs.** Content is governed entirely by rules.
 *  - **`manage:system`** is the root escape hatch — it grants everything.
 *
 * Conflict resolution among matching rules: most specific wins, DENY breaks the final tie. Ordered
 * by decreasing weight: path specificity (longer pinned prefix) → match-type precision → a
 * site-scoped rule beats an all-sites one → a locale-scoped rule beats an all-locales one → DENY
 * over ALLOW.
 */
object AccessResolver {
    object Perm {
        // Content verbs — grantable only via page rules, scoped by site/path/locale.
        const val READ_PAGES = "read:pages"
        const val WRITE_PAGES = "write:pages"
        const val MANAGE_PAGES = "manage:pages" // move/relocate an existing page
        const val DELETE_PAGES = "delete:pages"
        // NOTE: defined and offered in the group-rule editor, but NOT yet enforced anywhere — there is no
        // "view source" feature wired up. Until one exists, a DENY read:source has no effect (raw source is
        // governed by read:pages). When adding a View-Source view, gate it on this verb (see security notes).
        const val READ_SOURCE = "read:source"
        const val READ_HISTORY = "read:history"
        const val READ_ASSETS = "read:assets"
        const val WRITE_ASSETS = "write:assets"
        const val MANAGE_ASSETS = "manage:assets"

        // Admin verbs — global, held instance-wide (the gate).
        const val ACCESS_ADMIN = "access:admin"
        const val MANAGE_USERS = "manage:users"
        const val MANAGE_GROUPS = "manage:groups"
        const val MANAGE_NAVIGATION = "manage:navigation"
        const val MANAGE_SITES = "manage:sites"
        const val MANAGE_THEME = "manage:theme" // appearance / CSP / custom HTML+CSS
        const val CREATE_APIKEYS = "create:apikeys" // WikiKT-only: self-service API-key minting
        const val MANAGE_SYSTEM = "manage:system" // root: grants everything
    }

    val CONTENT_VERBS: Set<String> = setOf(
        Perm.READ_PAGES, Perm.WRITE_PAGES, Perm.MANAGE_PAGES, Perm.DELETE_PAGES,
        Perm.READ_SOURCE, Perm.READ_HISTORY,
        Perm.READ_ASSETS, Perm.WRITE_ASSETS, Perm.MANAGE_ASSETS,
    )

    val ADMIN_VERBS: Set<String> = setOf(
        Perm.ACCESS_ADMIN, Perm.MANAGE_USERS, Perm.MANAGE_GROUPS, Perm.MANAGE_NAVIGATION,
        Perm.MANAGE_SITES, Perm.MANAGE_THEME, Perm.CREATE_APIKEYS, Perm.MANAGE_SYSTEM,
    )

    /** Admin verbs an operator can assign in the group editor (excludes the root [Perm.MANAGE_SYSTEM]). */
    val ASSIGNABLE_ADMIN_VERBS: List<String> = listOf(
        Perm.ACCESS_ADMIN, Perm.MANAGE_USERS, Perm.MANAGE_GROUPS, Perm.MANAGE_NAVIGATION,
        Perm.MANAGE_SITES, Perm.MANAGE_THEME, Perm.CREATE_APIKEYS,
    )

    enum class Mode { ALLOW, DENY }

    /** How a rule's [AccessRule.path] is matched. Ordered least→most precise for tie-breaking. */
    enum class Match(val precision: Int) {
        START(0), // page/asset path begins with pattern (empty pattern = whole site)
        END(1), // path ends with pattern
        REGEX(2), // pattern is a regex tested against the path
        TAG(3), // pattern is a tag the page carries
        EXACT(4), // path equals pattern
    }

    /**
     * One rule owned (effectively) by one of the user's groups. [sites] empty = all sites; [locales]
     * empty = all locales; [roles] are the content verbs this rule grants/denies.
     */
    data class AccessRule(
        val mode: Mode,
        val roles: Set<String>,
        val sites: Set<UInt>,
        val locales: Set<String>,
        val match: Match,
        val path: String,
    )

    /** The requesting user, flattened across their groups. */
    data class Principal(
        val globalPermissions: Set<String>, // admin verbs the user holds instance-wide
        val rules: List<AccessRule>, // union of the user's groups' page rules
    )

    /** The page/asset being accessed. [tags] only needed for TAG-match rules. */
    data class Resource(
        val siteId: UInt,
        val locale: String,
        val path: String,
        val tags: Set<String> = emptySet(),
    )

    /**
     * Whether [principal] may perform [permission]. For an admin verb, [resource] is ignored. For a
     * content verb, pass the target [resource] (a null resource for a content verb is always denied —
     * content access is inherently resource-scoped).
     */
    fun check(principal: Principal, permission: String, resource: Resource? = null): Boolean {
        // Root bypasses everything.
        if (Perm.MANAGE_SYSTEM in principal.globalPermissions) return true

        // Admin verbs: a straight global check.
        if (permission in ADMIN_VERBS) return permission in principal.globalPermissions

        // Content verbs: rules-only, default-deny, and always resource-scoped.
        if (resource == null) return false
        val winner = winningRule(principal.rules, permission, resource) ?: return false
        return winner.mode == Mode.ALLOW
    }

    /** The most-specific rule that applies to [permission] on [resource], or null if none match. */
    private fun winningRule(rules: List<AccessRule>, permission: String, resource: Resource): AccessRule? =
        rules.asSequence()
            .filter { permission in it.roles }
            .filter { it.sites.isEmpty() || resource.siteId in it.sites }
            .filter { it.locales.isEmpty() || resource.locale in it.locales }
            .filter { it.matchesPath(resource) }
            .maxWithOrNull(
                compareBy(
                    { it.pathSpecificity() }, // longer pinned prefix = more specific
                    { it.match.precision }, // EXACT beats REGEX beats START, etc.
                    { if (it.sites.isEmpty()) 0 else 1 }, // a site-scoped rule beats an all-sites one
                    { if (it.locales.isEmpty()) 0 else 1 }, // a locale-scoped rule beats an all-locales one
                    { if (it.mode == Mode.DENY) 1 else 0 }, // DENY wins an otherwise exact tie
                ),
            )

    private fun AccessRule.matchesPath(resource: Resource): Boolean = when (match) {
        Match.START -> ("/" + resource.path).startsWith("/" + path)
        Match.END -> resource.path.endsWith(path)
        Match.EXACT -> ("/" + resource.path) == ("/" + path)
        Match.TAG -> path in resource.tags
        Match.REGEX -> try {
            SafeRegex.matches(path, resource.path)
        } catch (_: SafeRegex.BudgetExceeded) {
            // Fail closed: a runaway regex counts as a match only for DENY (still blocks), never
            // grants for ALLOW.
            mode == Mode.DENY
        }
    }

    private fun AccessRule.pathSpecificity(): Int = when (match) {
        Match.START -> path.length
        Match.EXACT -> path.length + 1 // an exact path is at least as pinned as the same-length prefix
        Match.END, Match.TAG -> path.length
        Match.REGEX -> SafeRegex.anchoredPrefixLength(path)
    }
}
