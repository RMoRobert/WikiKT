package com.wikikt.service

import com.wikikt.auth.PasswordHasher
import com.wikikt.config.WikiKtConfig
import com.wikikt.db.ContentFormat
import com.wikikt.db.GroupPageRuleRolesTable
import com.wikikt.db.GroupPageRulesTable
import com.wikikt.db.GroupPermissionsTable
import com.wikikt.db.GroupsTable
import com.wikikt.db.PagesTable
import com.wikikt.model.RuleEffect
import com.wikikt.model.RuleMatchType
import com.wikikt.db.UserGroupsTable
import com.wikikt.db.UsersTable
import com.wikikt.model.CreatePageRequest
import com.wikikt.routing.HOME_PAGE_PATH
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

class SeedService(
    private val database: R2dbcDatabase,
    private val config: WikiKtConfig,
    private val sites: SiteService,
    private val pageService: PageService,
) {
    suspend fun seedIfEmpty() {
        seedGroups()
        seedAdminUser()
        val siteId = seedDefaultSite()
        seedHomePage(siteId)
    }

    /**
     * Gives a freshly created site the same starter content a first-run install gets: a home page from
     * /seed/home.md — so a new hostname greets visitors with a real page instead of a "create this page"
     * 404. No navigation menu is seeded: the sidebar's Home shortcut (Administration > Navigation, on by
     * default) already provides that link, so a seeded "Home" item would only duplicate it. Idempotent
     * (it reuses the guarded seeder below), so it's a no-op on a site that already has a home page.
     */
    suspend fun seedNewSite(siteId: UInt) {
        seedHomePage(siteId)
    }

    /** The default catch-all site (created on first run); its id owns the seeded content. */
    private suspend fun seedDefaultSite(): UInt {
        sites.all().firstOrNull()?.let { return it.id }
        return sites.create(name = "Main site", hostname = null, isCatchAll = true).id
    }

    private suspend fun seedGroups() {
        suspendTransaction(database) {
            // Guests and authenticated Users can read all pages/assets everywhere by default (a broad
            // ALLOW rule — required because the model is default-deny). No write by default.
            val readEverywhere = setOf(AccessResolver.Perm.READ_PAGES, AccessResolver.Perm.READ_ASSETS)
            seedGroup(PermissionService.GUEST_GROUP, permissions = emptySet(), readAllRoles = readEverywhere)
            seedGroup(PermissionService.USER_GROUP, permissions = emptySet(), readAllRoles = readEverywhere)
            // Administrators are root: manage:system grants everything, so no page rules are needed.
            seedGroup(PermissionService.ADMIN_GROUP, permissions = setOf(AccessResolver.Perm.MANAGE_SYSTEM), readAllRoles = emptySet())
        }
    }

    /**
     * Seeds a system group with [permissions] global verbs and, when [readAllRoles] is non-empty, a
     * single ALLOW rule granting those content verbs on every site/locale (PREFIX ""). Idempotent.
     */
    private suspend fun seedGroup(name: String, permissions: Set<String>, readAllRoles: Set<String>) {
        val existing = GroupsTable.selectAll().where { GroupsTable.name eq name }.singleOrNull()
        if (existing != null) return
        val groupId = GroupsTable.insert {
            it[GroupsTable.name] = name
            it[GroupsTable.isSystem] = true
        }[GroupsTable.id].value
        for (verb in permissions) {
            GroupPermissionsTable.insert {
                it[GroupPermissionsTable.groupId] = groupId
                it[permission] = verb
            }
        }
        if (readAllRoles.isNotEmpty()) {
            val ruleId = GroupPageRulesTable.insert {
                it[GroupPageRulesTable.groupId] = groupId
                it[effect] = RuleEffect.ALLOW.name
                it[matchType] = RuleMatchType.PREFIX.name
                it[pattern] = ""
                it[position] = 0
            }[GroupPageRulesTable.id].value
            for (verb in readAllRoles) {
                GroupPageRuleRolesTable.insert {
                    it[GroupPageRuleRolesTable.ruleId] = ruleId
                    it[permission] = verb
                }
            }
        }
    }

    private suspend fun seedAdminUser() {
        suspendTransaction(database) {
            val adminUsername = UserService.normalizeUsername(config.defaultAdmin.username)
            val existing = UsersTable.selectAll()
                .where { UsersTable.username eq adminUsername }
                .singleOrNull()
            if (existing != null) return@suspendTransaction

            val adminGroup = GroupsTable.selectAll()
                .where { GroupsTable.name eq PermissionService.ADMIN_GROUP }
                .singleOrNull() ?: return@suspendTransaction

            val userId = UsersTable.insert {
                it[username] = adminUsername
                it[passwordHash] = PasswordHasher.hash(config.defaultAdmin.password)
                it[email] = null
                it[createdAt] = System.currentTimeMillis()
            }[UsersTable.id].value

            UserGroupsTable.insert {
                it[UserGroupsTable.userId] = userId
                it[UserGroupsTable.groupId] = adminGroup[GroupsTable.id].value
            }
        }
    }

    // The landing page at HOME_PAGE_PATH ("home"). `/` and `/{locale}` redirect here, so a fresh
    // install would otherwise greet visitors with a "create this page" 404. Seeded only when absent, so
    // an admin who edits or deletes it isn't overwritten on the next boot (a deleted home page reappears
    // on restart).
    private suspend fun seedHomePage(siteId: UInt) {
        val existing = suspendTransaction(database) {
            PagesTable.selectAll()
                .where { (PagesTable.siteId eq siteId) and (PagesTable.path eq HOME_PAGE_PATH) and (PagesTable.locale eq config.defaultLocale) }
                .singleOrNull()
        }
        if (existing != null) return

        val content = SeedService::class.java.getResource("/seed/home.md")?.readText()
            ?: "# Welcome\n\nThis is your wiki's home page."

        pageService.create(
            siteId,
            CreatePageRequest(
                locale = config.defaultLocale,
                path = HOME_PAGE_PATH,
                title = "Welcome",
                content = content,
                contentFormat = ContentFormat.MARKDOWN.name,
            ),
            updatedBy = null,
        )
    }
}
