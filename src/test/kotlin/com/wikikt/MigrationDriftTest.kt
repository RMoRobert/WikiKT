package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.db.FragmentsTable
import com.wikikt.db.GroupsTable
import com.wikikt.db.AssetRevisionsTable
import com.wikikt.db.AssetScheduledTable
import com.wikikt.db.AssetsTable
import com.wikikt.db.EmailVerificationTokensTable
import com.wikikt.db.GroupPageRulesTable
import com.wikikt.db.PageStagedTable
import com.wikikt.db.NavItemsTable
import com.wikikt.db.NavMenusTable
import com.wikikt.db.PageEditAclTable
import com.wikikt.db.PageRevisionsTable
import com.wikikt.db.PageTagsTable
import com.wikikt.db.PageViewAclTable
import com.wikikt.db.PagesTable
import com.wikikt.db.SchemaMigrationsTable
import com.wikikt.db.SessionsTable
import com.wikikt.db.UserGroupsTable
import com.wikikt.db.UsersTable
import com.wikikt.service.MigrationService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.migration.r2dbc.MigrationUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertTrue

class MigrationDriftTest {
    @Test
    fun `exposed tables match the migrated schema`() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-drift-test;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()

        val statements = suspendTransaction(database) {
            MigrationUtils.statementsRequiredForDatabaseMigration(
                UsersTable,
                GroupsTable,
                UserGroupsTable,
                PagesTable,
                PageViewAclTable,
                PageEditAclTable,
                PageRevisionsTable,
                PageTagsTable,
                SessionsTable,
                SchemaMigrationsTable,
                NavMenusTable,
                NavItemsTable,
                FragmentsTable,
                GroupPageRulesTable,
                AssetsTable,
                AssetRevisionsTable,
                PageStagedTable,
                AssetScheduledTable,
                EmailVerificationTokensTable,
                withLogs = false,
            )
        }

        // H2 auto-creates an index for every foreign key, which Exposed doesn't declare, so
        // MigrationUtils always reports them as "unmapped" and wants to drop them. They're not real
        // drift (Postgres doesn't auto-index FKs), so ignore those specific statements and assert on
        // genuine differences (missing/changed tables, columns, or declared indexes).
        val realDrift = statements.filterNot { it.trim().uppercase().startsWith("DROP INDEX IF EXISTS FK_") }

        assertTrue(
            realDrift.isEmpty(),
            "Schema drift detected — a table definition changed without a matching migration. " +
                "Add a migration applying:\n${realDrift.joinToString("\n")}",
        )
    }
}
