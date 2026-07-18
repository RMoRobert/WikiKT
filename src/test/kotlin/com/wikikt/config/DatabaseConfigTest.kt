package com.wikikt.config

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatabaseConfigTest {
    @Test
    fun `defaults to h2 when type is not set`() {
        val config = MapApplicationConfig(
            "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///test",
            "wikikt.database.h2.username" to "sa",
            "wikikt.database.h2.password" to "",
        )

        val database = config.loadDatabaseConfig()

        assertEquals(DatabaseType.H2, database.type)
        assertEquals("r2dbc:h2:mem:///test", database.connection.r2dbcUrl)
    }

    @Test
    fun `loads postgres settings when type is postgres`() {
        val config = MapApplicationConfig(
            "wikikt.database.type" to "postgres",
            "wikikt.database.postgres.r2dbcUrl" to "r2dbc:postgresql://localhost:5432/wikikt",
            "wikikt.database.postgres.username" to "wikikt",
            "wikikt.database.postgres.password" to "secret",
        )

        val database = config.loadDatabaseConfig()

        assertEquals(DatabaseType.POSTGRES, database.type)
        assertEquals("wikikt", database.connection.username)
        assertEquals("secret", database.connection.password)
    }

    @Test
    fun `rejects unknown database type`() {
        val config = MapApplicationConfig(
            "wikikt.database.type" to "mysql",
            "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///test",
            "wikikt.database.h2.username" to "sa",
        )

        assertFailsWith<IllegalArgumentException> {
            config.loadDatabaseConfig()
        }
    }
}
