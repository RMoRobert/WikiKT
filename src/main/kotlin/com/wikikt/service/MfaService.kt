package com.wikikt.service

import com.wikikt.auth.MfaSecretCipher
import com.wikikt.auth.TokenHash
import com.wikikt.auth.Totp
import com.wikikt.db.UserMfaFactorsTable
import com.wikikt.db.UserMfaRecoveryCodesTable
import com.wikikt.model.nowMillis
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.security.SecureRandom

/** What a caller shows the user when starting TOTP enrollment. */
data class TotpEnrollment(val secretBase32: String, val provisioningUri: String)

/** Non-sensitive summary of an enrolled factor (never carries the secret). */
data class MfaFactorInfo(val id: UInt, val type: String, val label: String?, val createdAt: Long)

/**
 * Owns multi-factor authentication: enrollment, verification, and one-time recovery codes. TOTP is the
 * first (and, for now, only) factor type; the storage and the login-side entry points are type-agnostic
 * ([UserMfaFactorsTable.type] is a discriminator), so a WebAuthn/passkey method can be added later behind
 * the same service without reshaping the schema or the login flow.
 *
 * TOTP secrets are encrypted at rest via [MfaSecretCipher] (dedicated key); recovery codes are stored
 * hashed like the other token tables. A factor is only "active" once [confirmed][UserMfaFactorsTable.confirmedAt]
 * — enrollment requires a live code first — and replay of a code within its validity window is blocked by
 * recording the last accepted TOTP time step.
 */
class MfaService(
    private val database: R2dbcDatabase,
    private val cipher: MfaSecretCipher,
) {
    private val random = SecureRandom()

    /** Whether [userId] has at least one confirmed (active) MFA factor — i.e. login must ask for a code. */
    suspend fun hasMfa(userId: UInt): Boolean = suspendTransaction(database) { hasConfirmedFactor(userId) }

    /** Active factors for [userId], for display in the account UI (no secrets). */
    suspend fun listFactors(userId: UInt): List<MfaFactorInfo> = suspendTransaction(database) {
        UserMfaFactorsTable.selectAll()
            .where { (UserMfaFactorsTable.userId eq userId) and UserMfaFactorsTable.confirmedAt.isNotNull() }
            .map {
                MfaFactorInfo(
                    id = it[UserMfaFactorsTable.id].value,
                    type = it[UserMfaFactorsTable.type],
                    label = it[UserMfaFactorsTable.label],
                    createdAt = it[UserMfaFactorsTable.createdAt],
                )
            }
            .toList()
    }

    /**
     * Starts TOTP enrollment: mints a fresh secret, stores it (encrypted) as an UNCONFIRMED factor, and
     * returns the secret (Base32) + provisioning URI for the QR. Any prior unconfirmed TOTP factor (a
     * restarted enrollment) is discarded first. The factor doesn't count until [confirmTotpEnrollment].
     */
    suspend fun beginTotpEnrollment(userId: UInt, issuer: String, account: String): TotpEnrollment =
        suspendTransaction(database) {
            UserMfaFactorsTable.deleteWhere {
                (UserMfaFactorsTable.userId eq userId) and
                    (UserMfaFactorsTable.type eq TOTP) and
                    UserMfaFactorsTable.confirmedAt.isNull()
            }
            val secret = Totp.generateSecret()
            UserMfaFactorsTable.insert {
                it[UserMfaFactorsTable.userId] = userId
                it[type] = TOTP
                it[UserMfaFactorsTable.secret] = cipher.encrypt(secret)
                it[createdAt] = nowMillis()
            }
            TotpEnrollment(Totp.base32Encode(secret), Totp.provisioningUri(secret, issuer, account))
        }

    /**
     * The in-progress (unconfirmed) TOTP enrollment for [userId] as display data, or null if there isn't
     * one. Lets the setup page re-render the same secret/QR (e.g. after a mistyped confirmation code)
     * without minting a new secret the user would have to re-scan.
     */
    suspend fun pendingTotpEnrollment(userId: UInt, issuer: String, account: String): TotpEnrollment? =
        suspendTransaction(database) {
            val row = UserMfaFactorsTable.selectAll()
                .where {
                    (UserMfaFactorsTable.userId eq userId) and
                        (UserMfaFactorsTable.type eq TOTP) and
                        UserMfaFactorsTable.confirmedAt.isNull()
                }
                .toList()
                .maxByOrNull { it[UserMfaFactorsTable.createdAt] }
                ?: return@suspendTransaction null
            val secret = cipher.decrypt(row[UserMfaFactorsTable.secret])
            TotpEnrollment(Totp.base32Encode(secret), Totp.provisioningUri(secret, issuer, account))
        }

    /**
     * Confirms the pending TOTP enrollment by verifying a live [code]. On success the factor becomes active
     * and a fresh set of recovery codes is generated and RETURNED (shown once). Returns null if there's no
     * pending enrollment or the code is wrong.
     */
    suspend fun confirmTotpEnrollment(userId: UInt, code: String, now: Long = nowMillis()): List<String>? =
        suspendTransaction(database) {
            val row = UserMfaFactorsTable.selectAll()
                .where {
                    (UserMfaFactorsTable.userId eq userId) and
                        (UserMfaFactorsTable.type eq TOTP) and
                        UserMfaFactorsTable.confirmedAt.isNull()
                }
                .toList()
                .maxByOrNull { it[UserMfaFactorsTable.createdAt] }
                ?: return@suspendTransaction null
            val secret = cipher.decrypt(row[UserMfaFactorsTable.secret])
            val step = Totp.matchingStep(secret, code, now / 1000) ?: return@suspendTransaction null
            // Record the confirming code's step so it can't also be replayed as a login code.
            UserMfaFactorsTable.update({ UserMfaFactorsTable.id eq row[UserMfaFactorsTable.id].value }) {
                it[confirmedAt] = now
                it[lastUsedAt] = now
                it[lastUsedStep] = step
            }
            regenerateRecoveryCodesInTx(userId)
        }

    /**
     * Verifies a TOTP [code] for login. Tries each confirmed factor; on a match, records the time step and
     * rejects any code whose step is not newer than the last used (replay guard). Returns true on success.
     */
    suspend fun verifyCode(userId: UInt, code: String, now: Long = nowMillis()): Boolean = suspendTransaction(database) {
        val factors = UserMfaFactorsTable.selectAll()
            .where {
                (UserMfaFactorsTable.userId eq userId) and
                    (UserMfaFactorsTable.type eq TOTP) and
                    UserMfaFactorsTable.confirmedAt.isNotNull()
            }
            .toList()
        for (row in factors) {
            val secret = cipher.decrypt(row[UserMfaFactorsTable.secret])
            val step = Totp.matchingStep(secret, code, now / 1000) ?: continue
            val last = row[UserMfaFactorsTable.lastUsedStep]
            if (last != null && step <= last) return@suspendTransaction false // replay of the same/older code
            UserMfaFactorsTable.update({ UserMfaFactorsTable.id eq row[UserMfaFactorsTable.id].value }) {
                it[lastUsedAt] = now
                it[lastUsedStep] = step
            }
            return@suspendTransaction true
        }
        false
    }

    /** Verifies and SPENDS a one-time recovery code (single-use, race-safe via the `usedAt IS NULL` guard). */
    suspend fun verifyRecoveryCode(userId: UInt, code: String): Boolean = suspendTransaction(database) {
        val hash = TokenHash.sha256Hex(normalizeRecoveryCode(code))
        UserMfaRecoveryCodesTable.update({
            (UserMfaRecoveryCodesTable.userId eq userId) and
                (UserMfaRecoveryCodesTable.codeHash eq hash) and
                UserMfaRecoveryCodesTable.usedAt.isNull()
        }) { it[usedAt] = nowMillis() } > 0
    }

    /** Removes every MFA factor and recovery code for [userId] (disables MFA entirely). */
    suspend fun disableMfa(userId: UInt): Unit = suspendTransaction(database) {
        UserMfaFactorsTable.deleteWhere { UserMfaFactorsTable.userId eq userId }
        UserMfaRecoveryCodesTable.deleteWhere { UserMfaRecoveryCodesTable.userId eq userId }
    }

    /** Replaces the user's recovery codes with a fresh set, returned once. Null if MFA isn't active. */
    suspend fun regenerateRecoveryCodes(userId: UInt): List<String>? = suspendTransaction(database) {
        if (!hasConfirmedFactor(userId)) return@suspendTransaction null
        regenerateRecoveryCodesInTx(userId)
    }

    /** How many unused recovery codes remain (for the account UI to warn when running low). */
    suspend fun remainingRecoveryCodes(userId: UInt): Int = suspendTransaction(database) {
        UserMfaRecoveryCodesTable.selectAll()
            .where { (UserMfaRecoveryCodesTable.userId eq userId) and UserMfaRecoveryCodesTable.usedAt.isNull() }
            .toList()
            .size
    }

    // --- in-transaction helpers (call only from within a suspendTransaction block) ---

    private suspend fun hasConfirmedFactor(userId: UInt): Boolean =
        UserMfaFactorsTable.selectAll()
            .where { (UserMfaFactorsTable.userId eq userId) and UserMfaFactorsTable.confirmedAt.isNotNull() }
            .map { it[UserMfaFactorsTable.id].value }
            .toList()
            .isNotEmpty()

    private suspend fun regenerateRecoveryCodesInTx(userId: UInt): List<String> {
        UserMfaRecoveryCodesTable.deleteWhere { UserMfaRecoveryCodesTable.userId eq userId }
        val now = nowMillis()
        val codes = (1..RECOVERY_CODE_COUNT).map { newRecoveryCode() }
        for (code in codes) {
            UserMfaRecoveryCodesTable.insert {
                it[UserMfaRecoveryCodesTable.userId] = userId
                it[codeHash] = TokenHash.sha256Hex(normalizeRecoveryCode(code))
                it[createdAt] = now
            }
        }
        return codes
    }

    /** A human-friendly one-time code: ~50 bits of entropy, Base32, hyphen-grouped ("ABCDE-FGHIJ"). */
    private fun newRecoveryCode(): String {
        val bytes = ByteArray(RECOVERY_CODE_BYTES).also { random.nextBytes(it) }
        return Totp.base32Encode(bytes).chunked(5).joinToString("-")
    }

    private fun normalizeRecoveryCode(code: String): String =
        code.trim().uppercase().replace("-", "").replace(" ", "")

    private companion object {
        const val TOTP = "totp"
        const val RECOVERY_CODE_COUNT = 10
        const val RECOVERY_CODE_BYTES = 6 // 48 bits → 10 Base32 chars
    }
}
