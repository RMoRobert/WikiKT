package com.wikikt.config

enum class DatabaseType {
    H2,
    POSTGRES,
    ;

    companion object {
        fun fromConfig(value: String): DatabaseType = when (value.lowercase()) {
            "h2" -> H2
            "postgres", "postgresql", "pg" -> POSTGRES
            else -> throw IllegalArgumentException(
                "Unknown database type '$value'. Supported values: h2, postgres",
            )
        }
    }
}

data class DatabaseConnectionConfig(
    val r2dbcUrl: String,
    val username: String,
    val password: String,
)

/**
 * Connection-pool sizing (r2dbc-pool). Without a pool, Exposed opens and discards a physical
 * connection per transaction — one page view costs ~13 connections, and concurrent load scales
 * connections 1:1 with in-flight transactions, so a burst can exhaust the server's max_connections.
 *
 * Defaults are deliberately conservative: [maxSize] bounds how many connections this app will ever
 * hold (keep it well under the database server's max_connections — Postgres defaults to 100), and
 * [maxAcquireTimeSeconds] makes a saturated pool fail fast instead of hanging forever.
 */
data class DatabasePoolConfig(
    val maxSize: Int = 10,
    val initialSize: Int = 2,
    val maxIdleTimeSeconds: Long = 1800,
    val maxLifeTimeSeconds: Long = 3600,
    val maxAcquireTimeSeconds: Long = 10,
)

data class DatabaseConfig(
    val type: DatabaseType,
    val connection: DatabaseConnectionConfig,
    val pool: DatabasePoolConfig = DatabasePoolConfig(),
)
