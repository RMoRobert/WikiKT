package com.wikikt.service

import com.wikikt.auth.TokenHash
import com.wikikt.db.EmailVerificationTokensTable
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
 * Issues and redeems email-confirmation tokens for the self-service registration flow. Modelled on
 * [PasswordResetService]: tokens are single-use and short-lived, the plaintext is minted here and mailed
 * to the registrant inside a confirmation link, and only its SHA-256 hash is stored — so a leaked
 * database or backup can't confirm anyone's account.
 *
 * A token is valid only while it is unexpired AND unused; [consume] flips it to used atomically so the
 * same link can't be replayed. Expired rows are swept periodically ([purgeExpired]) along with the
 * unconfirmed accounts they belong to (see UserService.purgeUnverified).
 */
class EmailVerificationService(private val database: R2dbcDatabase) {
    private val random = SecureRandom()

    /** Creates a confirmation token for [userId] valid [ttlMillis] from now; returns the opaque plaintext. */
    suspend fun createToken(userId: UInt, ttlMillis: Long = DEFAULT_TTL_MILLIS): String {
        val token = newToken()
        val now = nowMillis()
        suspendTransaction(database) {
            EmailVerificationTokensTable.insert {
                it[EmailVerificationTokensTable.tokenHash] = TokenHash.sha256Hex(token)
                it[EmailVerificationTokensTable.userId] = userId
                it[EmailVerificationTokensTable.createdAt] = now
                it[EmailVerificationTokensTable.expiresAt] = now + ttlMillis
            }
        }
        return token
    }

    /**
     * Resolves a token to its user WITHOUT spending it — for rendering the confirm page on GET, so a mail
     * security scanner or link prefetcher that merely fetches the confirmation link can't burn the
     * single-use token (or silently activate the account) before the human clicks. Returns null if the
     * token is unknown, expired, or already used. Mirrors [PasswordResetService.resolve].
     */
    suspend fun resolve(token: String, now: Long = nowMillis()): UInt? = suspendTransaction(database) {
        val row = EmailVerificationTokensTable.selectAll()
            .where { EmailVerificationTokensTable.tokenHash eq TokenHash.sha256Hex(token) }
            .singleOrNull() ?: return@suspendTransaction null
        if (row[EmailVerificationTokensTable.expiresAt] < now || row[EmailVerificationTokensTable.usedAt] != null) {
            return@suspendTransaction null
        }
        row[EmailVerificationTokensTable.userId].value
    }

    /**
     * Validates the token and marks it used in one step, returning its user id, or null if it is
     * unknown, expired, or already used. The `usedAt IS NULL` guard on the UPDATE makes single-use
     * race-safe: two concurrent redemptions can't both flip the same row.
     */
    suspend fun consume(token: String, now: Long = nowMillis()): UInt? = suspendTransaction(database) {
        val hashed = TokenHash.sha256Hex(token)
        val row = EmailVerificationTokensTable.selectAll()
            .where { EmailVerificationTokensTable.tokenHash eq hashed }
            .singleOrNull() ?: return@suspendTransaction null
        if (row[EmailVerificationTokensTable.expiresAt] < now) return@suspendTransaction null
        val updated = EmailVerificationTokensTable.update(
            { (EmailVerificationTokensTable.tokenHash eq hashed) and EmailVerificationTokensTable.usedAt.isNull() },
        ) { it[EmailVerificationTokensTable.usedAt] = now }
        if (updated == 0) return@suspendTransaction null // already used (lost the race)
        row[EmailVerificationTokensTable.userId].value
    }

    /** Drops every confirmation token for [userId] — used after a successful confirm and on user delete. */
    suspend fun deleteAllForUser(userId: UInt) {
        suspendTransaction(database) {
            EmailVerificationTokensTable.deleteWhere { EmailVerificationTokensTable.userId eq userId }
        }
    }

    /** Deletes every token that expired before [now], so spent/stale rows don't accumulate. */
    suspend fun purgeExpired(now: Long = nowMillis()) {
        suspendTransaction(database) {
            EmailVerificationTokensTable.deleteWhere { EmailVerificationTokensTable.expiresAt less now }
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        // Longer than a password reset (1h): people often confirm from a different device hours later.
        const val DEFAULT_TTL_MILLIS = 24L * 60 * 60 * 1000 // 24 hours
    }
}
