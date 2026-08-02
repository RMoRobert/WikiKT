package com.wikikt.service

import com.wikikt.db.ApiKeysTable
import com.wikikt.db.AppSettingsTable
import com.wikikt.db.FragmentsTable
import com.wikikt.db.GroupsTable
import com.wikikt.db.AssetRevisionsTable
import com.wikikt.db.AssetScheduledTable
import com.wikikt.db.AssetsTable
import com.wikikt.db.GroupPageRuleLocalesTable
import com.wikikt.db.GroupPageRuleRolesTable
import com.wikikt.db.GroupPageRuleSitesTable
import com.wikikt.db.EmailQueueTable
import com.wikikt.db.EmailTemplatesTable
import com.wikikt.db.EmailVerificationTokensTable
import com.wikikt.db.GroupPageRulesTable
import com.wikikt.db.GroupPermissionsTable
import com.wikikt.db.InfoboxPathRulesTable
import com.wikikt.db.InfoboxTemplatesTable
import com.wikikt.db.PageStagedTable
import com.wikikt.db.NavItemsTable
import com.wikikt.db.NavMenusTable
import com.wikikt.db.PageEditAclTable
import com.wikikt.db.PageRenderCacheTable
import com.wikikt.db.PageRevisionsTable
import com.wikikt.db.PageSearchIndexTable
import com.wikikt.db.PageTagsTable
import com.wikikt.db.PageViewAclTable
import com.wikikt.db.PagesTable
import com.wikikt.db.PasswordResetTokensTable
import com.wikikt.db.SchemaMigrationsTable
import com.wikikt.db.SessionsTable
import com.wikikt.db.SitesTable
import com.wikikt.db.UserGroupsTable
import com.wikikt.db.UserMfaFactorsTable
import com.wikikt.db.UserMfaRecoveryCodesTable
import com.wikikt.db.UsersTable
import com.wikikt.model.nowMillis
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * One ordered, idempotent schema change. [apply] runs inside a transaction; prefer Exposed
 * ([SchemaUtils.create] etc.) so it stays portable across H2 and Postgres, dropping to
 * `exec(...)` only for changes Exposed can't express. Use `./gradlew schemaDiff`-style output
 * (MigrationUtils, see MigrationDriftTest) to draft the body when you change a table.
 */
class Migration(
    val version: Int,
    val name: String,
    val apply: suspend R2dbcTransaction.() -> Unit,
)

/**
 * The applied-in-order database migration.
 * Migration 1 (V1) is the baseline: a single create-if-not-exists of every
 * table from its current Exposed definition.
 *
 * See docs/migrations.md for adding additional migrations after V1; give each change its own migration with
 * the next version number.
 */
val MIGRATIONS: List<Migration> = listOf(
    // Migration 1 - a single create-if-not-exists of every table in its initial form.
    // SitesTable is created first because content tables reference it; each parent precedes
    // its children.
    // NOTE: One piece of initial schema is not an Exposed table definition: the Postgres-only pg_trgm
    // search index. This piece is done separately below.
    Migration(1, "baseline") {
        SchemaUtils.create(
            SitesTable,
            UsersTable,
            GroupsTable,
            GroupPermissionsTable,
            UserGroupsTable,
            ApiKeysTable,
            PagesTable,
            PageViewAclTable,
            PageEditAclTable,
            PageRevisionsTable,
            PageTagsTable,
            SessionsTable,
            NavMenusTable,
            NavItemsTable,
            FragmentsTable,
            GroupPageRulesTable,
            GroupPageRuleRolesTable,
            GroupPageRuleSitesTable,
            GroupPageRuleLocalesTable,
            AssetsTable,
            AssetRevisionsTable,
            PageStagedTable,
            AssetScheduledTable,
            AppSettingsTable,
            PageSearchIndexTable,
            PageRenderCacheTable,
            InfoboxTemplatesTable,
            InfoboxPathRulesTable,
            EmailTemplatesTable,
            EmailQueueTable,
            PasswordResetTokensTable,
            EmailVerificationTokensTable,
            UserMfaFactorsTable,
            UserMfaRecoveryCodesTable,
        )
        // Postgres substring-search acceleration for the live-search dropdown: `LOWER(text) LIKE '%q%'`
        // on page_search_index runs on every keystroke, and the leading wildcard defeats a B-tree
        // index. A pg_trgm GIN index on `lower(text)` keeps it index-backed instead of a full scan.
        // Postgres-only (pg_trgm is a Postgres extension; the DDL isn't valid H2, which keeps the
        // portable single-column scan) and not expressible as an Exposed index, so it's raw DDL here
        // rather than part of the SchemaUtils.create above. With H2, the guard is false, so the created
        // schema still matches the Exposed table definitions (MigrationDriftTest).
        if (currentDialect is PostgreSQLDialect) {
            exec("CREATE EXTENSION IF NOT EXISTS pg_trgm")
            exec(
                "CREATE INDEX IF NOT EXISTS page_search_index_text_trgm " +
                    "ON page_search_index USING gin (lower(\"text\") gin_trgm_ops)",
            )
        }
    },
)

/** R2DBC-native migration runner: applies any [migrations] not yet recorded, in version order. */
class MigrationService(
    private val database: R2dbcDatabase,
    private val migrations: List<Migration> = MIGRATIONS,
) {
    suspend fun migrate() {
        suspendTransaction(database) { SchemaUtils.create(SchemaMigrationsTable) }

        val applied = suspendTransaction(database) {
            SchemaMigrationsTable.selectAll().map { it[SchemaMigrationsTable.version] }.toList().toSet()
        }

        for (migration in migrations.sortedBy { it.version }) {
            if (migration.version in applied) continue
            suspendTransaction(database) {
                migration.apply(this)
                SchemaMigrationsTable.insert {
                    it[version] = migration.version
                    it[name] = migration.name
                    it[appliedAt] = nowMillis()
                }
            }
        }
    }
}
