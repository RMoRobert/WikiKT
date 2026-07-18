package com.wikikt.service

import com.wikikt.db.GroupPageRuleLocalesTable
import com.wikikt.db.GroupPageRuleRolesTable
import com.wikikt.db.GroupPageRuleSitesTable
import com.wikikt.db.GroupPageRulesTable
import com.wikikt.db.GroupPermissionsTable
import com.wikikt.db.GroupsTable
import com.wikikt.db.PageEditAclTable
import com.wikikt.db.PageViewAclTable
import com.wikikt.db.UserGroupsTable
import com.wikikt.model.CreateGroupRequest
import com.wikikt.model.GroupRecord
import com.wikikt.model.UpdateGroupRequest
import com.wikikt.model.toGroupRecord
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

class GroupService(private val database: R2dbcDatabase) {
    suspend fun list(): List<GroupRecord> = suspendTransaction(database) {
        val rows = GroupsTable.selectAll().toList()
        val perms = permissionsFor(rows.map { it[GroupsTable.id].value }.toSet())
        rows.map { it.toGroupRecord(perms[it[GroupsTable.id].value].orEmpty()) }
    }

    suspend fun findById(id: UInt): GroupRecord? = suspendTransaction(database) {
        GroupsTable.selectAll().where { GroupsTable.id eq id }.toList()
            .singleOrNull()?.let { it.toGroupRecord(permissionsFor(setOf(id))[id].orEmpty()) }
    }

    suspend fun findByName(name: String): GroupRecord? = suspendTransaction(database) {
        val row = GroupsTable.selectAll().where { GroupsTable.name eq name }.toList().singleOrNull()
            ?: return@suspendTransaction null
        val id = row[GroupsTable.id].value
        row.toGroupRecord(permissionsFor(setOf(id))[id].orEmpty())
    }

    suspend fun create(request: CreateGroupRequest): GroupRecord = suspendTransaction(database) {
        val id = GroupsTable.insert {
            it[name] = request.name
            it[isSystem] = false
        }[GroupsTable.id].value
        setPermissions(id, request.permissions)
        GroupsTable.selectAll().where { GroupsTable.id eq id }.toList()
            .single().toGroupRecord(request.permissions.filter { it in AccessResolver.ASSIGNABLE_ADMIN_VERBS }.toSet())
    }

    suspend fun update(id: UInt, request: UpdateGroupRequest): GroupRecord? = suspendTransaction(database) {
        val existing = GroupsTable.selectAll().where { GroupsTable.id eq id }.toList().singleOrNull()
            ?: return@suspendTransaction null

        if (existing[GroupsTable.isSystem] && request.name != null && request.name != existing[GroupsTable.name]) {
            throw IllegalArgumentException("System group names cannot be changed")
        }

        request.name?.let { value -> GroupsTable.update({ GroupsTable.id eq id }) { it[name] = value } }
        request.permissions?.let { setPermissions(id, it) }

        GroupsTable.selectAll().where { GroupsTable.id eq id }.toList()
            .singleOrNull()?.toGroupRecord(permissionsFor(setOf(id))[id].orEmpty())
    }

    suspend fun delete(id: UInt): Boolean = suspendTransaction(database) {
        val group = GroupsTable.selectAll().where { GroupsTable.id eq id }.toList().singleOrNull()
            ?: return@suspendTransaction false

        if (group[GroupsTable.isSystem]) {
            throw IllegalArgumentException("System groups cannot be deleted")
        }
        // Remove everything that references the group: memberships, global permissions, page rules
        // (and their role/site/locale scopes), and any per-group ACL entries.
        UserGroupsTable.deleteWhere { UserGroupsTable.groupId eq id }
        GroupPermissionsTable.deleteWhere { GroupPermissionsTable.groupId eq id }
        val ruleIds = GroupPageRulesTable.selectAll().where { GroupPageRulesTable.groupId eq id }
            .map { it[GroupPageRulesTable.id].value }.toList()
        if (ruleIds.isNotEmpty()) {
            GroupPageRuleRolesTable.deleteWhere { ruleId inList ruleIds }
            GroupPageRuleSitesTable.deleteWhere { ruleId inList ruleIds }
            GroupPageRuleLocalesTable.deleteWhere { ruleId inList ruleIds }
        }
        GroupPageRulesTable.deleteWhere { GroupPageRulesTable.groupId eq id }
        PageViewAclTable.deleteWhere { PageViewAclTable.groupId eq id }
        PageEditAclTable.deleteWhere { PageEditAclTable.groupId eq id }
        GroupsTable.deleteWhere { GroupsTable.id eq id } > 0
    }

    /** The ids of users who are members of [groupId] (inverse of UserService.groupIdsForUser). */
    suspend fun userIdsInGroup(groupId: UInt): Set<UInt> = suspendTransaction(database) {
        UserGroupsTable.selectAll()
            .where { UserGroupsTable.groupId eq groupId }
            .map { it[UserGroupsTable.userId].value }
            .toList()
            .toSet()
    }

    /**
     * Replaces the membership of [groupId] with exactly [userIds]. A system group carrying
     * `manage:system` (root) may be modified only by a root actor: otherwise a delegated
     * `manage:groups` admin could add themselves to the Admin group and escalate to root. Root
     * membership is seeded directly (see SeedService), never through this path.
     */
    suspend fun setGroupMembers(
        groupId: UInt,
        userIds: Collection<UInt>,
        actorIsRoot: Boolean = false,
    ) = suspendTransaction(database) {
        if (!actorIsRoot && groupId in systemGroupIdsInTransaction()) {
            throw IllegalArgumentException("Only a root administrator can change the membership of a system group")
        }
        UserGroupsTable.deleteWhere { UserGroupsTable.groupId eq groupId }
        for (uid in userIds.toSet()) {
            UserGroupsTable.insert {
                it[UserGroupsTable.userId] = uid
                it[UserGroupsTable.groupId] = groupId
            }
        }
    }

    /**
     * Group ids that carry root (`manage:system`). Membership of — and permission edits to — these
     * groups are restricted to root actors so a delegated admin can't escalate through them.
     */
    suspend fun systemGroupIds(): Set<UInt> = suspendTransaction(database) { systemGroupIdsInTransaction() }

    /** [systemGroupIds] without opening its own transaction — call only from inside one. */
    private suspend fun systemGroupIdsInTransaction(): Set<UInt> =
        GroupPermissionsTable.selectAll()
            .where { GroupPermissionsTable.permission eq AccessResolver.Perm.MANAGE_SYSTEM }
            .map { it[GroupPermissionsTable.groupId].value }
            .toList()
            .toSet()

    /** Loads the global permission verbs for each of [groupIds] in one query (only known admin verbs). */
    private suspend fun permissionsFor(groupIds: Set<UInt>): Map<UInt, Set<String>> {
        if (groupIds.isEmpty()) return emptyMap()
        return GroupPermissionsTable.selectAll().where { GroupPermissionsTable.groupId inList groupIds }
            .toList()
            .groupBy({ it[GroupPermissionsTable.groupId].value }, { it[GroupPermissionsTable.permission] })
            .mapValues { (_, v) -> v.filter { it in AccessResolver.ADMIN_VERBS }.toSet() }
    }

    /**
     * Replaces a group's global permissions with the valid admin verbs in [permissions]. `manage:system`
     * (root) is **never assignable this way** — it lives only on the seeded Admin group — so it is dropped
     * from caller input but preserved if the group already holds it (e.g. renaming/re-permissioning the
     * Admin group). This is the structural guard that stops a `manage:groups` admin from minting a new root
     * group via the group editor or `POST /u/v1/groups`.
     */
    private suspend fun setPermissions(groupId: UInt, permissions: Set<String>) {
        val keepRoot = AccessResolver.Perm.MANAGE_SYSTEM in GroupPermissionsTable.selectAll()
            .where { GroupPermissionsTable.groupId eq groupId }
            .map { it[GroupPermissionsTable.permission] }
            .toList()
        GroupPermissionsTable.deleteWhere { GroupPermissionsTable.groupId eq groupId }
        val verbs = permissions.filterTo(mutableSetOf()) { it in AccessResolver.ASSIGNABLE_ADMIN_VERBS }
        if (keepRoot) verbs += AccessResolver.Perm.MANAGE_SYSTEM
        for (verb in verbs) {
            GroupPermissionsTable.insert {
                it[GroupPermissionsTable.groupId] = groupId
                it[permission] = verb
            }
        }
    }
}
