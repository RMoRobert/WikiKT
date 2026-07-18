package com.wikikt.service

import com.wikikt.auth.TokenHash
import com.wikikt.db.SessionsTable
import com.wikikt.model.nowMillis
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.security.SecureRandom
import java.util.Base64

/**
 * Server-side session store. Sessions live in the database so they can be revoked: deleting a row
 * (on logout, or when the user is deleted) immediately invalidates that session on the next request.
 *
 * The stored `token` column holds the SHA-256 of the session id, not the id itself — like API keys,
 * so a leaked database or backup doesn't contain live credentials. The plaintext id exists only in
 * the user's (encrypted) cookie and is hashed on every lookup.
 */
class SessionService(private val database: R2dbcDatabase) {
    private val random = SecureRandom()

    /** Creates a session for [userId] living [ttlMillis] from now; returns the opaque session id. */
    suspend fun create(userId: UInt, ttlMillis: Long): String {
        val token = newToken()
        val now = nowMillis()
        suspendTransaction(database) {
            SessionsTable.insert {
                it[SessionsTable.token] = TokenHash.sha256Hex(token)
                it[SessionsTable.userId] = userId
                it[SessionsTable.createdAt] = now
                it[SessionsTable.expiresAt] = now + ttlMillis
            }
        }
        return token
    }

    /** Resolves a session id to its user, or null if unknown or expired (expired rows are purged). */
    suspend fun resolveUserId(token: String): UInt? = suspendTransaction(database) {
        val hashed = TokenHash.sha256Hex(token)
        val row = SessionsTable.selectAll()
            .where { SessionsTable.token eq hashed }
            .singleOrNull() ?: return@suspendTransaction null
        if (row[SessionsTable.expiresAt] < nowMillis()) {
            SessionsTable.deleteWhere { SessionsTable.token eq hashed }
            return@suspendTransaction null
        }
        row[SessionsTable.userId].value
    }

    suspend fun delete(token: String) {
        suspendTransaction(database) {
            SessionsTable.deleteWhere { SessionsTable.token eq TokenHash.sha256Hex(token) }
        }
    }

    /**
     * Deletes every session that expired before [now]. Expired rows are otherwise only removed
     * lazily when their exact token is presented again, so without this sweep the table grows
     * unboundedly. Called periodically from the background scheduler.
     */
    suspend fun purgeExpired(now: Long = nowMillis()) {
        suspendTransaction(database) {
            SessionsTable.deleteWhere { SessionsTable.expiresAt less now }
        }
    }

    suspend fun deleteAllForUser(userId: UInt) {
        suspendTransaction(database) {
            SessionsTable.deleteWhere { SessionsTable.userId eq userId }
        }
    }

    /**
     * The most recent login (session creation) per user, newest first, up to [limit] users — a
     * lightweight "recently active users" feed. Scans the latest sessions and de-duplicates by user;
     * sessions are the only login signal we record (there's no users.lastLogin column).
     */
    suspend fun recentLoginsByUser(limit: Int): List<Pair<UInt, Long>> = suspendTransaction(database) {
        val rows = SessionsTable.selectAll()
            .orderBy(SessionsTable.createdAt, SortOrder.DESC)
            .limit(500)
            .map { it[SessionsTable.userId].value to it[SessionsTable.createdAt] }
            .toList()
        val mostRecent = LinkedHashMap<UInt, Long>()
        for ((userId, createdAt) in rows) if (userId !in mostRecent) mostRecent[userId] = createdAt
        mostRecent.entries.take(limit).map { it.key to it.value }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
