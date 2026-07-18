package com.wikikt

import com.wikikt.auth.LoginThrottle
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The login throttle keeps a tight per-host cap (the primary control against one source hammering) and a
 * deliberately loose per-username cap (a backstop against a distributed guess on one account). The loose
 * username cap is what keeps a lone attacker — or a few typos — from locking the real owner out. The
 * JVM-wide singleton is reset around each test.
 */
class LoginThrottleTest {
    @BeforeTest fun clear() = LoginThrottle.reset()
    @AfterTest fun cleanup() = LoginThrottle.reset()

    @Test
    fun `one host is locked after five failures`() {
        val host = "192.0.2.1"
        // Five failures from one host, spread across different usernames so only the HOST key accumulates.
        repeat(5) { i -> LoginThrottle.recordFailure(host, "victim-$i") }
        assertTrue(LoginThrottle.isLockedOut(host, "anyone"), "one host is capped at five failures")
        assertFalse(LoginThrottle.isLockedOut("192.0.2.2", "anyone"), "a different host has its own bucket")
    }

    @Test
    fun `a lone or lightly-distributed attacker no longer locks an account out`() {
        val victim = "admin"
        // Five failures for one username, each from a DIFFERENT host: no host hits its cap, and the loose
        // username cap is nowhere near tripped — so the real owner is NOT locked out (the old behavior would
        // have locked at five, enabling a targeted account-lockout DoS).
        repeat(5) { i -> LoginThrottle.recordFailure("10.0.0.$i", victim) }
        assertFalse(LoginThrottle.isLockedOut("10.0.0.250", victim), "five distributed failures don't lock the account")
    }

    @Test
    fun `the loose username backstop still trips under a sustained distributed guess`() {
        val victim = "admin"
        // Twenty distributed failures reach the (higher) username cap, so the backstop still engages.
        repeat(20) { i -> LoginThrottle.recordFailure("10.1.$i.1", victim) }
        assertTrue(LoginThrottle.isLockedOut("10.1.250.1", victim), "the username backstop engages at its cap")
    }

    @Test
    fun `a successful login clears the account and host counters`() {
        val victim = "admin"
        val host = "203.0.113.5"
        repeat(4) { LoginThrottle.recordFailure(host, victim) }
        LoginThrottle.recordSuccess(host, victim)
        // Both keys were cleared, so the next failure starts a fresh window.
        repeat(4) { LoginThrottle.recordFailure(host, victim) }
        assertFalse(LoginThrottle.isLockedOut(host, victim), "a success reset the counters")
    }
}
