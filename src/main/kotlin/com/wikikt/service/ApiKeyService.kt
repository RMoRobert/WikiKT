package com.wikikt.service

import com.wikikt.auth.TokenHash
import com.wikikt.db.ApiKeysTable
import com.wikikt.model.nowMillis
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.security.SecureRandom
import java.util.Base64

/** Metadata for one API key. Never carries the secret — only the non-secret [prefix] for display. */
data class ApiKeyRecord(
    val id: UInt,
    val userId: UInt,
    val name: String,
    val partialKey: String,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val expiresAt: Long?,
    val revokedAt: Long?,
)

/** A freshly created key: the stored [record] plus the [plaintext] token, shown to the user once. */
data class CreatedApiKey(val record: ApiKeyRecord, val plaintext: String)

/**
 * Store for long-lived API bearer tokens. A key belongs to a user and, on a request, resolves to
 * that user id — so it inherits exactly that user's permissions (scope a key by owning it with a
 * purpose-built user). Tokens are stored as a SHA-256 hash: the plaintext exists only in the
 * response that creates the key. Revocation and expiry are enforced on lookup.
 */
class ApiKeyService(private val database: R2dbcDatabase) {
    private val random = SecureRandom()

    /**
     * Creates a key for [userId]. [ttlMillis] null means it never expires. Returns the plaintext
     * token (prefixed `wkt_`) which the caller must show once — it is not recoverable afterwards.
     */
    suspend fun create(userId: UInt, name: String, ttlMillis: Long?): CreatedApiKey {
        val plaintext = newToken()
        val now = nowMillis()
        val expiresAt = ttlMillis?.let { now + it }
        val partialKey = plaintext.take(PARTIAL_KEY_LENGTH)
        val id = suspendTransaction(database) {
            ApiKeysTable.insert {
                it[ApiKeysTable.userId] = userId
                it[ApiKeysTable.name] = name
                it[ApiKeysTable.tokenHash] = hash(plaintext)
                it[ApiKeysTable.partialKey] = partialKey
                it[ApiKeysTable.createdAt] = now
                it[ApiKeysTable.expiresAt] = expiresAt
            }[ApiKeysTable.id].value
        }
        return CreatedApiKey(
            ApiKeyRecord(id, userId, name, partialKey, now, null, expiresAt, null),
            plaintext,
        )
    }

    /**
     * Resolves a bearer token to its owning user id, or null if the token is unknown, revoked, or
     * expired. Best-effort touches last_used_at so the admin UI can show activity.
     */
    suspend fun resolveUserId(token: String): UInt? = suspendTransaction(database) {
        val tokenHash = hash(token)
        val row = ApiKeysTable.selectAll()
            .where { ApiKeysTable.tokenHash eq tokenHash }
            .singleOrNull() ?: return@suspendTransaction null
        if (row[ApiKeysTable.revokedAt] != null) return@suspendTransaction null
        val expiresAt = row[ApiKeysTable.expiresAt]
        if (expiresAt != null && expiresAt < nowMillis()) return@suspendTransaction null
        val id = row[ApiKeysTable.id].value
        ApiKeysTable.update({ ApiKeysTable.id eq id }) { it[lastUsedAt] = nowMillis() }
        row[ApiKeysTable.userId].value
    }

    /** All keys, newest first — for the admin console. */
    suspend fun list(): List<ApiKeyRecord> = suspendTransaction(database) {
        ApiKeysTable.selectAll()
            .orderBy(ApiKeysTable.createdAt, SortOrder.DESC)
            .map { it.toRecord() }
            .toList()
    }

    suspend fun findById(id: UInt): ApiKeyRecord? = suspendTransaction(database) {
        ApiKeysTable.selectAll()
            .where { ApiKeysTable.id eq id }
            .singleOrNull()
            ?.toRecord()
    }

    /** Marks a key revoked (idempotent). Returns false if no such key. A revoked key stays in the
     *  list as an audit trail; use [delete] to remove it entirely. */
    suspend fun revoke(id: UInt): Boolean = suspendTransaction(database) {
        val updated = ApiKeysTable.update({ (ApiKeysTable.id eq id) and ApiKeysTable.revokedAt.isNull() }) {
            it[revokedAt] = nowMillis()
        }
        updated > 0
    }

    suspend fun delete(id: UInt): Boolean = suspendTransaction(database) {
        ApiKeysTable.deleteWhere { ApiKeysTable.id eq id } > 0
    }

    private fun ResultRow.toRecord() = ApiKeyRecord(
        id = this[ApiKeysTable.id].value,
        userId = this[ApiKeysTable.userId].value,
        name = this[ApiKeysTable.name],
        partialKey = this[ApiKeysTable.partialKey],
        createdAt = this[ApiKeysTable.createdAt],
        lastUsedAt = this[ApiKeysTable.lastUsedAt],
        expiresAt = this[ApiKeysTable.expiresAt],
        revokedAt = this[ApiKeysTable.revokedAt],
    )

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(token: String): String = TokenHash.sha256Hex(token)

    companion object {
        /** Greppable, non-secret marker so a leaked key is recognizable in logs. */
        const val TOKEN_PREFIX = "wkt_"
        private const val PARTIAL_KEY_LENGTH = 12
    }
}
