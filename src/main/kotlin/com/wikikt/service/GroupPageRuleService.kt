package com.wikikt.service

import com.wikikt.db.GroupPageRuleLocalesTable
import com.wikikt.db.GroupPageRuleRolesTable
import com.wikikt.db.GroupPageRuleSitesTable
import com.wikikt.db.GroupPageRulesTable
import com.wikikt.model.GroupPageRuleRecord
import com.wikikt.model.RuleEffect
import com.wikikt.model.RuleMatchType
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** CRUD for per-group page rules and the lookup the permission layer uses to resolve content access. */
class GroupPageRuleService(private val database: R2dbcDatabase) {
    suspend fun rulesForGroup(groupId: UInt): List<GroupPageRuleRecord> = suspendTransaction(database) {
        val rows = GroupPageRulesTable.selectAll().where { GroupPageRulesTable.groupId eq groupId }.toList()
        hydrate(rows).sortedBy { it.position }
    }

    /** All rules owned by any of [groupIds] — the candidate set for a content-access decision. */
    suspend fun rulesForGroups(groupIds: Set<UInt>): List<GroupPageRuleRecord> {
        if (groupIds.isEmpty()) return emptyList()
        return suspendTransaction(database) {
            hydrate(GroupPageRulesTable.selectAll().where { GroupPageRulesTable.groupId inList groupIds }.toList())
        }
    }

    suspend fun findById(id: UInt): GroupPageRuleRecord? = suspendTransaction(database) {
        hydrate(GroupPageRulesTable.selectAll().where { GroupPageRulesTable.id eq id }.toList()).firstOrNull()
    }

    suspend fun create(
        groupId: UInt,
        effect: RuleEffect,
        matchType: RuleMatchType,
        pattern: String,
        roles: Set<String>,
        sites: Set<UInt>,
        locales: Set<String>,
    ): GroupPageRuleRecord = suspendTransaction(database) {
        val nextPosition = GroupPageRulesTable.selectAll()
            .where { GroupPageRulesTable.groupId eq groupId }.toList().size
        val id = GroupPageRulesTable.insert {
            it[GroupPageRulesTable.groupId] = groupId
            it[GroupPageRulesTable.effect] = effect.name
            it[GroupPageRulesTable.matchType] = matchType.name
            it[GroupPageRulesTable.pattern] = pattern
            it[position] = nextPosition
        }[GroupPageRulesTable.id].value
        // Only real content verbs are stored as roles; unknown strings are dropped.
        for (verb in roles.filter { it in AccessResolver.CONTENT_VERBS }.toSet()) {
            GroupPageRuleRolesTable.insert { it[ruleId] = id; it[permission] = verb }
        }
        for (siteId in sites) {
            GroupPageRuleSitesTable.insert { it[ruleId] = id; it[GroupPageRuleSitesTable.siteId] = siteId }
        }
        for (loc in locales) {
            GroupPageRuleLocalesTable.insert { it[ruleId] = id; it[locale] = loc }
        }
        GroupPageRuleRecord(id, groupId, effect, matchType, pattern, roles, sites, locales, nextPosition)
    }

    suspend fun delete(id: UInt): Boolean = suspendTransaction(database) {
        GroupPageRuleRolesTable.deleteWhere { ruleId eq id }
        GroupPageRuleSitesTable.deleteWhere { ruleId eq id }
        GroupPageRuleLocalesTable.deleteWhere { ruleId eq id }
        GroupPageRulesTable.deleteWhere { GroupPageRulesTable.id eq id } > 0
    }

    /** Loads role/site/locale scopes for [rows] in three batch queries and builds full records. */
    private suspend fun hydrate(rows: List<ResultRow>): List<GroupPageRuleRecord> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[GroupPageRulesTable.id].value }.toSet()
        val roles = GroupPageRuleRolesTable.selectAll().where { GroupPageRuleRolesTable.ruleId inList ids }
            .toList().groupBy({ it[GroupPageRuleRolesTable.ruleId].value }, { it[GroupPageRuleRolesTable.permission] })
        val sites = GroupPageRuleSitesTable.selectAll().where { GroupPageRuleSitesTable.ruleId inList ids }
            .toList().groupBy({ it[GroupPageRuleSitesTable.ruleId].value }, { it[GroupPageRuleSitesTable.siteId].value })
        val locales = GroupPageRuleLocalesTable.selectAll().where { GroupPageRuleLocalesTable.ruleId inList ids }
            .toList().groupBy({ it[GroupPageRuleLocalesTable.ruleId].value }, { it[GroupPageRuleLocalesTable.locale] })
        return rows.map { row ->
            val id = row[GroupPageRulesTable.id].value
            GroupPageRuleRecord(
                id = id,
                groupId = row[GroupPageRulesTable.groupId].value,
                effect = RuleEffect.valueOf(row[GroupPageRulesTable.effect]),
                matchType = RuleMatchType.valueOf(row[GroupPageRulesTable.matchType]),
                pattern = row[GroupPageRulesTable.pattern],
                roles = roles[id].orEmpty().toSet(),
                sites = sites[id].orEmpty().toSet(),
                locales = locales[id].orEmpty().toSet(),
                position = row[GroupPageRulesTable.position],
            )
        }
    }
}
