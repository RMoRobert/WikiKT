package com.wikikt

import com.wikikt.auth.MfaSecretCipher
import com.wikikt.auth.Totp
import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.service.MfaService
import com.wikikt.service.MigrationService
import com.wikikt.service.TotpEnrollment
import com.wikikt.service.UserService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MfaServiceTest {
    private class Fx(val mfa: MfaService, val userId: UInt)

    private fun fixture() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-mfa-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val userId = UserService(database).register("alice", "alice@example.com", "password1", defaultGroupId = null).id
        // A fixed 32-byte test key — the real one comes from WIKIKT_MFA_KEY.
        Fx(MfaService(database, MfaSecretCipher(ByteArray(32) { it.toByte() })), userId)
    }

    private fun codeAt(enrollment: TotpEnrollment, timeMillis: Long): String =
        Totp.codeAt(Totp.base32Decode(enrollment.secretBase32), timeMillis / 1000)

    @Test
    fun `enrollment is pending until a valid code confirms it`() = runBlocking {
        val fx = fixture()
        val t = 1_700_000_000_000L
        assertFalse(fx.mfa.hasMfa(fx.userId))

        val enr = fx.mfa.beginTotpEnrollment(fx.userId, "WikiKT", "alice")
        assertTrue(enr.provisioningUri.startsWith("otpauth://totp/WikiKT:alice"))
        assertFalse(fx.mfa.hasMfa(fx.userId), "a pending enrollment is not yet active")

        assertNull(fx.mfa.confirmTotpEnrollment(fx.userId, "000000", now = t), "a wrong code doesn't confirm")
        assertFalse(fx.mfa.hasMfa(fx.userId))

        val codes = fx.mfa.confirmTotpEnrollment(fx.userId, codeAt(enr, t), now = t)
        assertNotNull(codes)
        assertEquals(10, codes.size, "confirmation returns ten recovery codes")
        assertTrue(fx.mfa.hasMfa(fx.userId))
        assertEquals(1, fx.mfa.listFactors(fx.userId).size)
    }

    @Test
    fun `verifyCode accepts a fresh code but blocks replay and the confirming code`() = runBlocking {
        val fx = fixture()
        val t0 = 1_700_000_000_000L
        val enr = fx.mfa.beginTotpEnrollment(fx.userId, "WikiKT", "alice")
        fx.mfa.confirmTotpEnrollment(fx.userId, codeAt(enr, t0), now = t0)

        // The code used to confirm was spent — it can't double as a login code in the same window.
        assertFalse(fx.mfa.verifyCode(fx.userId, codeAt(enr, t0), now = t0), "the confirming code can't log in")

        // A code from a later time step logs in once, then can't be replayed.
        val t1 = t0 + 60_000
        val later = codeAt(enr, t1)
        assertTrue(fx.mfa.verifyCode(fx.userId, later, now = t1), "a fresh code verifies")
        assertFalse(fx.mfa.verifyCode(fx.userId, later, now = t1), "the same code can't be replayed")
        assertFalse(fx.mfa.verifyCode(fx.userId, "000000", now = t1), "a wrong code fails")
    }

    @Test
    fun `recovery codes are single-use and format-insensitive`() = runBlocking {
        val fx = fixture()
        val t = 1_700_000_000_000L
        val enr = fx.mfa.beginTotpEnrollment(fx.userId, "WikiKT", "alice")
        val codes = fx.mfa.confirmTotpEnrollment(fx.userId, codeAt(enr, t), now = t)!!
        assertEquals(10, fx.mfa.remainingRecoveryCodes(fx.userId))

        val first = codes.first()
        assertTrue(fx.mfa.verifyRecoveryCode(fx.userId, first), "a valid recovery code works once")
        assertFalse(fx.mfa.verifyRecoveryCode(fx.userId, first), "and can't be reused")
        assertEquals(9, fx.mfa.remainingRecoveryCodes(fx.userId))
        assertFalse(fx.mfa.verifyRecoveryCode(fx.userId, "NOPE-NOPE"), "an unknown code fails")

        // Hyphens and case are normalized away before matching.
        assertTrue(
            fx.mfa.verifyRecoveryCode(fx.userId, codes[1].lowercase().replace("-", "")),
            "the normalized form still matches",
        )
    }

    @Test
    fun `disable removes all factors and recovery codes`() = runBlocking {
        val fx = fixture()
        val t = 1_700_000_000_000L
        val enr = fx.mfa.beginTotpEnrollment(fx.userId, "WikiKT", "alice")
        fx.mfa.confirmTotpEnrollment(fx.userId, codeAt(enr, t), now = t)
        assertTrue(fx.mfa.hasMfa(fx.userId))

        fx.mfa.disableMfa(fx.userId)
        assertFalse(fx.mfa.hasMfa(fx.userId))
        assertEquals(0, fx.mfa.remainingRecoveryCodes(fx.userId))
        assertTrue(fx.mfa.listFactors(fx.userId).isEmpty())
    }

    @Test
    fun `regenerating recovery codes invalidates the old set`() = runBlocking {
        val fx = fixture()
        val t = 1_700_000_000_000L
        val enr = fx.mfa.beginTotpEnrollment(fx.userId, "WikiKT", "alice")
        val old = fx.mfa.confirmTotpEnrollment(fx.userId, codeAt(enr, t), now = t)!!

        val fresh = fx.mfa.regenerateRecoveryCodes(fx.userId)
        assertNotNull(fresh)
        assertEquals(10, fresh.size)
        assertFalse(fx.mfa.verifyRecoveryCode(fx.userId, old.first()), "an old code no longer works")
        assertTrue(fx.mfa.verifyRecoveryCode(fx.userId, fresh.first()), "a fresh code works")
    }
}
