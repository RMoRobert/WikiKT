package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.model.CreateUserRequest
import com.wikikt.service.MigrationService
import com.wikikt.service.PasswordResetService
import com.wikikt.service.UserService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PasswordResetServiceTest {
    private fun fixture() = runBlocking {
        // Unique per call: JUnit instantiates the test class per method, so a shared name would collide
        // with the previous test's "alice" in the JVM-persistent (DB_CLOSE_DELAY=-1) in-memory DB.
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-reset-svc-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val users = UserService(database)
        val userId = users.create(CreateUserRequest("alice", "hunter2", "alice@example.com")).id
        PasswordResetService(database) to userId
    }

    @Test
    fun `a fresh token resolves and consumes to its user`() = runBlocking {
        val (service, userId) = fixture()
        val token = service.createToken(userId)
        assertEquals(userId, service.resolve(token), "resolve returns the token's user")
        assertEquals(userId, service.consume(token), "consume returns the token's user")
    }

    @Test
    fun `an expired token is not resolvable or consumable`() = runBlocking {
        val (service, userId) = fixture()
        val token = service.createToken(userId, ttlMillis = 1_000)
        val afterExpiry = System.currentTimeMillis() + 60_000
        assertNull(service.resolve(token, now = afterExpiry), "expired token doesn't resolve")
        assertNull(service.consume(token, now = afterExpiry), "expired token can't be consumed")
    }

    @Test
    fun `a token is single-use`() = runBlocking {
        val (service, userId) = fixture()
        val token = service.createToken(userId)
        assertNotNull(service.consume(token), "first consume succeeds")
        assertNull(service.consume(token), "the same token can't be consumed twice")
        assertNull(service.resolve(token), "a used token no longer resolves")
    }

    @Test
    fun `an unknown token yields nothing`() = runBlocking {
        val (service, _) = fixture()
        assertNull(service.resolve("not-a-real-token"))
        assertNull(service.consume("not-a-real-token"))
    }

    @Test
    fun `deleteAllForUser voids outstanding tokens`() = runBlocking {
        val (service, userId) = fixture()
        val a = service.createToken(userId)
        val b = service.createToken(userId)
        service.deleteAllForUser(userId)
        assertNull(service.resolve(a))
        assertNull(service.resolve(b))
    }

    @Test
    fun `purgeExpired removes only elapsed tokens`() = runBlocking {
        val (service, userId) = fixture()
        val shortLived = service.createToken(userId, ttlMillis = 1_000)
        val longLived = service.createToken(userId, ttlMillis = 60L * 60 * 1000)
        service.purgeExpired(now = System.currentTimeMillis() + 30_000)
        assertNull(service.resolve(shortLived), "the elapsed token was purged")
        assertNotNull(service.resolve(longLived), "the still-valid token survives")
        Unit
    }
}
