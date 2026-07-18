package com.wikikt.db

import com.wikikt.config.DatabaseConfig
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

object DatabaseFactory {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    /**
     * Pools handed out by [connect], disposed together by [close] on shutdown.
     *
     * Exposed asks its ConnectionFactory for a connection per transaction. Given a plain (unpooled)
     * factory that means a brand-new physical connection every time — measured at ~4 connections for
     * one search request and ~13 for one page view, each one a fresh Postgres backend process — and
     * connections then scale 1:1 with in-flight transactions, so a traffic burst can exhaust the
     * server's max_connections. Handing Exposed a [ConnectionPool] instead makes those acquisitions
     * cheap reuses and caps the total at `pool.maxSize`.
     */
    private val pools = CopyOnWriteArrayList<ConnectionPool>()

    fun connect(config: DatabaseConfig): R2dbcDatabase {
        val connection = config.connection
        val pool = config.pool
        log.info(
            "Connecting to {} database at {} (pool: initial {}, max {})",
            config.type.name.lowercase(),
            sanitizeUrlForLog(connection.r2dbcUrl),
            pool.initialSize,
            pool.maxSize,
        )

        // Credentials go through ConnectionFactoryOptions rather than R2dbcDatabase.connect(user, password),
        // because the factory Exposed receives below is the pool, not the driver.
        val options = ConnectionFactoryOptions.parse(connection.r2dbcUrl).mutate()
            .option(ConnectionFactoryOptions.USER, connection.username)
            .option(ConnectionFactoryOptions.PASSWORD, connection.password)
            .build()

        val connectionPool = ConnectionPool(
            ConnectionPoolConfiguration.builder(ConnectionFactories.get(options))
                .initialSize(pool.initialSize.coerceAtMost(pool.maxSize))
                .maxSize(pool.maxSize)
                .maxIdleTime(Duration.ofSeconds(pool.maxIdleTimeSeconds))
                .maxLifeTime(Duration.ofSeconds(pool.maxLifeTimeSeconds))
                // Fail fast when the pool is saturated instead of hanging on the acquire forever.
                .maxAcquireTime(Duration.ofSeconds(pool.maxAcquireTimeSeconds))
                .build(),
        )
        pools += connectionPool

        // The pool carries no vendor of its own, so hand Exposed the real URL too — that's what it
        // resolves the dialect (H2 vs Postgres) from, which the migrations branch on.
        return R2dbcDatabase.connect(
            connectionFactory = connectionPool,
            databaseConfig = R2dbcDatabaseConfig { setUrl(connection.r2dbcUrl) },
        )
    }

    /** Disposes every pool created by [connect]. Called on application shutdown. */
    fun close() {
        pools.forEach { runCatching { it.dispose() } }
        pools.clear()
    }

    private fun sanitizeUrlForLog(url: String): String =
        url.substringBefore('?').substringBefore('@')
}
