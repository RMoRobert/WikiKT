package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.model.CreateGroupRequest
import com.wikikt.model.CreatePageRequest
import com.wikikt.model.CreateUserRequest
import com.wikikt.model.RuleEffect
import com.wikikt.model.RuleMatchType
import com.wikikt.service.AccessResolver
import com.wikikt.service.GroupPageRuleService
import com.wikikt.service.GroupService
import com.wikikt.service.MigrationService
import com.wikikt.service.PageService
import com.wikikt.service.PermissionService
import com.wikikt.service.SiteService
import com.wikikt.service.UserService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration test of the permission model end-to-end (DB → PermissionService →
 * AccessResolver): default-deny, broad ALLOW, more-specific DENY/ALLOW, site scoping, locale scoping.
 * Pure rule resolution is unit-tested in [AccessResolverTest]; this proves the wiring.
 */
class GroupPageRuleTest {
    private val READ = AccessResolver.Perm.READ_PAGES

    @Test
    fun `content access resolves through group rules with site and locale scoping`() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-grouprule-test;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val sites = SiteService(database)
        val siteA = sites.create("Site A", null, isCatchAll = true).id
        val siteB = sites.create("Site B", "b.example.com", isCatchAll = false).id

        val users = UserService(database)
        val groups = GroupService(database)
        val pages = PageService(database)
        val rules = GroupPageRuleService(database)
        val perms = PermissionService(users, groups, pages, rules)

        // Baseline groups that effectiveGroups() layers on (Guest for anonymous, User for authed).
        val guest = groups.create(CreateGroupRequest(name = PermissionService.GUEST_GROUP))
        val user = groups.create(CreateGroupRequest(name = PermissionService.USER_GROUP))
        val restricted = groups.create(CreateGroupRequest(name = "Restricted"))

        // Guests may read Site A (only) — no rule for Site B, so default-deny hides it.
        rules.create(guest.id, RuleEffect.ALLOW, RuleMatchType.PREFIX, "", setOf(READ), setOf(siteA), emptySet())
        rules.create(guest.id, RuleEffect.DENY, RuleMatchType.PREFIX, "beta/", setOf(READ), emptySet(), emptySet())
        // Authenticated users may read everything, on every site.
        rules.create(user.id, RuleEffect.ALLOW, RuleMatchType.PREFIX, "", setOf(READ), emptySet(), emptySet())
        // The Restricted group denies secret/* but a more-specific ALLOW rescues secret/public,
        // and an fr-only DENY hides docs/* in French.
        rules.create(restricted.id, RuleEffect.DENY, RuleMatchType.PREFIX, "secret/", setOf(READ), emptySet(), emptySet())
        rules.create(restricted.id, RuleEffect.ALLOW, RuleMatchType.PREFIX, "secret/public", setOf(READ), emptySet(), emptySet())
        rules.create(restricted.id, RuleEffect.DENY, RuleMatchType.PREFIX, "docs/", setOf(READ), emptySet(), setOf("fr"))

        val restrictedUser = users.create(CreateUserRequest("rest", "pw", null, listOf(restricted.id.toString())))
        val plainUser = users.create(CreateUserRequest("plain", "pw", null, emptyList()))

        val docsA = pages.create(siteA, CreatePageRequest(locale = "en", path = "docs/intro", title = "I", content = "x"), null)
        val betaA = pages.create(siteA, CreatePageRequest(locale = "en", path = "beta/x", title = "B", content = "x"), null)
        val secretA = pages.create(siteA, CreatePageRequest(locale = "en", path = "secret/x", title = "S", content = "x"), null)
        val secretPub = pages.create(siteA, CreatePageRequest(locale = "en", path = "secret/public", title = "P", content = "x"), null)
        val docsFr = pages.create(siteA, CreatePageRequest(locale = "fr", path = "docs/intro", title = "F", content = "x"), null)
        val docsB = pages.create(siteB, CreatePageRequest(locale = "en", path = "docs/b", title = "B", content = "x"), null)

        // Guest (anonymous): reads Site A except beta/*, and cannot reach Site B at all (default-deny).
        assertTrue(perms.canViewPage(null, docsA), "guest reads Site A")
        assertFalse(perms.canViewPage(null, betaA), "guest denied beta/* by a more-specific DENY")
        assertFalse(perms.canViewPage(null, docsB), "guest has no Site B rule → default-deny")

        // Plain authed user gets the User baseline ALLOW → reads everything, both sites.
        assertTrue(perms.canViewPage(plainUser.id, docsA))
        assertTrue(perms.canViewPage(plainUser.id, docsB), "User baseline spans all sites")
        assertTrue(perms.canViewPage(plainUser.id, betaA), "beta DENY was Guest-only")

        // Restricted user: baseline ALLOW, but the group's secret/* DENY beats it, and the deeper
        // secret/public ALLOW wins back. The fr-only DENY hides the French page but not the English one.
        assertTrue(perms.canViewPage(restrictedUser.id, docsA), "baseline allow")
        assertFalse(perms.canViewPage(restrictedUser.id, secretA), "secret/* DENY beats the broad ALLOW")
        assertTrue(perms.canViewPage(restrictedUser.id, secretPub), "more-specific ALLOW rescues secret/public")
        assertTrue(perms.canViewPage(restrictedUser.id, docsA), "en page unaffected by an fr-only DENY")
        assertFalse(perms.canViewPage(restrictedUser.id, docsFr), "fr-scoped DENY hides the French docs page")
    }

    @Test
    fun `a site-scoped editor can write on its site but not cross-site`() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-editor-scope-test;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val sites = SiteService(database)
        val siteA = sites.create("Site A", null, isCatchAll = true).id
        val siteB = sites.create("Site B", "b.example.com", isCatchAll = false).id

        val users = UserService(database)
        val groups = GroupService(database)
        val pages = PageService(database)
        val rules = GroupPageRuleService(database)
        val perms = PermissionService(users, groups, pages, rules)

        groups.create(CreateGroupRequest(name = PermissionService.GUEST_GROUP))
        groups.create(CreateGroupRequest(name = PermissionService.USER_GROUP))
        val editors = groups.create(CreateGroupRequest(name = "Site A editors"))
        // write + delete + manage:assets, scoped to Site A only (empty locale = all locales).
        val write = setOf(
            AccessResolver.Perm.WRITE_PAGES, AccessResolver.Perm.DELETE_PAGES,
            AccessResolver.Perm.MANAGE_PAGES, AccessResolver.Perm.WRITE_ASSETS, AccessResolver.Perm.MANAGE_ASSETS,
        )
        rules.create(editors.id, RuleEffect.ALLOW, RuleMatchType.PREFIX, "", write, setOf(siteA), emptySet())

        val editor = users.create(CreateUserRequest("ed", "pw", null, listOf(editors.id.toString())))
        val pageA = pages.create(siteA, CreatePageRequest(locale = "en", path = "docs/x", title = "A", content = "x"), null)
        val pageB = pages.create(siteB, CreatePageRequest(locale = "en", path = "docs/x", title = "B", content = "x"), null)

        // Page editing is site-scoped: allowed on Site A, denied on Site B (even by the same id-path).
        assertTrue(perms.canEditPage(editor.id, pageA), "editor writes Site A")
        assertFalse(perms.canEditPage(editor.id, pageB), "editor cannot write Site B")
        // delete:pages and asset management are likewise scoped to Site A only.
        assertTrue(perms.check(editor.id, AccessResolver.Perm.DELETE_PAGES, siteA, "en", "docs/x"))
        assertFalse(perms.check(editor.id, AccessResolver.Perm.DELETE_PAGES, siteB, "en", "docs/x"))
        assertTrue(perms.check(editor.id, AccessResolver.Perm.MANAGE_ASSETS, siteA, "en", "img/logo.png"))
        assertFalse(perms.check(editor.id, AccessResolver.Perm.MANAGE_ASSETS, siteB, "en", "img/logo.png"), "cross-site asset tamper blocked")
        // The New-page / Duplicate affordance is site-aware: shown on Site A, hidden on Site B.
        assertTrue(perms.canCreatePagesOnSite(editor.id, siteA), "New-page affordance shows on Site A")
        assertFalse(perms.canCreatePagesOnSite(editor.id, siteB), "New-page affordance hidden on Site B")
    }
}
