package com.wikikt

import com.wikikt.auth.RegisterThrottle
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The registration throttle has two buckets: a per-host cap and a site-wide cap that bounds the outbound
 * confirmation/notification mail the endpoint can emit per window (so a distributed attacker rotating IPs
 * can't use registration to spray third parties). The JVM-wide singleton is reset around each test.
 */
class RegisterThrottleTest {
    @BeforeTest fun clear() = RegisterThrottle.reset()
    @AfterTest fun cleanup() = RegisterThrottle.reset()

    @Test
    fun `the per-host cap locks one source after five attempts`() {
        val host = "203.0.113.7"
        repeat(5) {
            assertFalse(RegisterThrottle.isLockedOut(host), "still open before the cap")
            RegisterThrottle.record(host)
        }
        assertTrue(RegisterThrottle.isLockedOut(host), "a sixth attempt from the same host is locked out")
        assertFalse(RegisterThrottle.isLockedOut("198.51.100.9"), "a different host has its own bucket")
    }

    @Test
    fun `the site-wide cap bounds amplification across many source hosts`() {
        // A distributed attacker uses a fresh IP per attempt, so no per-host bucket ever trips...
        repeat(30) { i ->
            val host = "10.0.0.$i"
            assertFalse(RegisterThrottle.isLockedOut(host), "each fresh host is under its own per-host cap")
            assertFalse(RegisterThrottle.isGloballyLockedOut(), "still under the site-wide cap at attempt $i")
            RegisterThrottle.record(host)
        }
        // ...but the site-wide bucket has now reached its cap, so the endpoint stops emitting mail. The
        // register handler ORs this with the per-host gate, so a brand-new host is refused too.
        assertTrue(RegisterThrottle.isGloballyLockedOut(), "the site-wide cap halts further registrations")
    }
}
