package com.wikikt.service

import com.wikikt.auth.TokenHash
import com.wikikt.db.PasswordResetTokensTable
import com.wikikt.model.nowMillis
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.security.SecureRandom
import java.util.Base64

/**
 * Issues and redeems password-reset tokens for the self-service "forgot password" flow. Tokens are
 * single-use and short-lived: the plaintext is minted here, mailed to the user inside a reset link, and
 * never persisted — only its SHA-256 hash is stored (like [SessionService]/[ApiKeyService]), so a leaked
 * database or backup can't reset anyone's password.
 *
 * A token is valid only while it is unexpired AND unused; [consume] flips it to used atomically so the
 * same link can't be replayed. Expired rows are swept periodically ([purgeExpired]).
 */
class PasswordResetService(private val database: R2dbcDatabase) {
    private val random = SecureRandom()

    /** Creates a reset token for [userId] valid [ttlMillis] from now; returns the opaque plaintext token. */
    suspend fun createToken(userId: UInt, ttlMillis: Long = DEFAULT_TTL_MILLIS): String {
        val token = newToken()
        val now = nowMillis()
        suspendTransaction(database) {
            PasswordResetTokensTable.insert {
                it[PasswordResetTokensTable.tokenHash] = TokenHash.sha256Hex(token)
                it[PasswordResetTokensTable.userId] = userId
                it[PasswordResetTokensTable.createdAt] = now
                it[PasswordResetTokensTable.expiresAt] = now + ttlMillis
            }
        }
        return token
    }

    /**
     * Resolves a token to its user WITHOUT spending it — for rendering the reset form on GET. Returns
     * null if the token is unknown, expired, or already used.
     */
    suspend fun resolve(token: String, now: Long = nowMillis()): UInt? = suspendTransaction(database) {
        val row = PasswordResetTokensTable.selectAll()
            .where { PasswordResetTokensTable.tokenHash eq TokenHash.sha256Hex(token) }
            .singleOrNull() ?: return@suspendTransaction null
        if (row[PasswordResetTokensTable.expiresAt] < now || row[PasswordResetTokensTable.usedAt] != null) {
            return@suspendTransaction null
        }
        row[PasswordResetTokensTable.userId].value
    }

    /**
     * Validates the token and marks it used in one step, returning its user id, or null if it is
     * unknown, expired, or already used. The `usedAt IS NULL` guard on the UPDATE makes single-use
     * race-safe: two concurrent redemptions can't both flip the same row.
     */
    suspend fun consume(token: String, now: Long = nowMillis()): UInt? = suspendTransaction(database) {
        val hashed = TokenHash.sha256Hex(token)
        val row = PasswordResetTokensTable.selectAll()
            .where { PasswordResetTokensTable.tokenHash eq hashed }
            .singleOrNull() ?: return@suspendTransaction null
        if (row[PasswordResetTokensTable.expiresAt] < now) return@suspendTransaction null
        val updated = PasswordResetTokensTable.update(
            { (PasswordResetTokensTable.tokenHash eq hashed) and PasswordResetTokensTable.usedAt.isNull() },
        ) { it[PasswordResetTokensTable.usedAt] = now }
        if (updated == 0) return@suspendTransaction null // already used (lost the race)
        row[PasswordResetTokensTable.userId].value
    }

    /** Drops every reset token for [userId] — used after a successful reset to void any other outstanding
     *  links, and when the user is deleted. */
    suspend fun deleteAllForUser(userId: UInt) {
        suspendTransaction(database) {
            PasswordResetTokensTable.deleteWhere { PasswordResetTokensTable.userId eq userId }
        }
    }

    /** Deletes every token that expired before [now], so spent/stale rows don't accumulate. */
    suspend fun purgeExpired(now: Long = nowMillis()) {
        suspendTransaction(database) {
            PasswordResetTokensTable.deleteWhere { PasswordResetTokensTable.expiresAt less now }
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 60L * 60 * 1000 // 1 hour
    }
}
