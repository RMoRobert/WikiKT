package com.wikikt.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal in-memory brute-force throttle for login attempts. bcrypt slows guessing but does not
 * stop it; this caps repeated failures within a window, keyed BOTH by client host (one source
 * hammering any accounts) and by target username (a distributed attack on one account). Either
 * key reaching its cap locks that key out until the window passes.
 *
 * The two caps are deliberately different. The per-HOST cap is tight ([MAX_HOST_FAILURES]) — a single
 * source has no legitimate reason to fail many logins quickly, and this is the primary control. The
 * per-USERNAME cap is loose ([MAX_USER_FAILURES]) on purpose: it only backstops a DISTRIBUTED guess on
 * one account, and keeping it high means ordinary typos — or a lone attacker spamming a known username
 * from one IP — can't easily lock the real owner out (a targeted account-lockout DoS; the per-host cap
 * already handles that single source). A determined attacker who sustains [MAX_USER_FAILURES]/window
 * could still trip it; if that ever matters, gate the username lock on failures from multiple distinct
 * hosts so a single source can't reach it at all.
 *
 * Not durable across restarts and not shared across nodes — adequate as a first line of defense
 * for a single-instance deployment.
 */
object LoginThrottle {
    private const val MAX_HOST_FAILURES = 5 // one source hammering any account(s) — the primary control
    private const val MAX_USER_FAILURES = 20 // loose backstop against a distributed guess on one account
    private const val WINDOW_MS = 60_000L
    // Opportunistic-prune trigger so a flood of unique hosts/usernames can't grow the map forever.
    private const val PRUNE_SIZE = 10_000

    private data class Attempts(val count: Int, val firstFailureAt: Long)

    private val failures = ConcurrentHashMap<String, Attempts>()

    fun isLockedOut(host: String, username: String): Boolean =
        isKeyLockedOut(hostKey(host), MAX_HOST_FAILURES) || isKeyLockedOut(userKey(username), MAX_USER_FAILURES)

    fun recordFailure(host: String, username: String) {
        if (failures.size > PRUNE_SIZE) pruneExpired()
        bump(hostKey(host))
        bump(userKey(username))
    }

    fun recordSuccess(host: String, username: String) {
        failures.remove(hostKey(host))
        failures.remove(userKey(username))
    }

    /** Clears all recorded failures (test isolation). */
    fun reset() = failures.clear()

    private fun hostKey(host: String) = "h:$host"
    private fun userKey(username: String) = "u:${username.trim().lowercase()}"

    private fun isKeyLockedOut(key: String, maxFailures: Int): Boolean {
        val entry = failures[key] ?: return false
        if (System.currentTimeMillis() - entry.firstFailureAt > WINDOW_MS) {
            failures.remove(key)
            return false
        }
        return entry.count >= maxFailures
    }

    private fun bump(key: String) {
        val now = System.currentTimeMillis()
        failures.compute(key) { _, existing ->
            if (existing == null || now - existing.firstFailureAt > WINDOW_MS) {
                Attempts(1, now)
            } else {
                existing.copy(count = existing.count + 1)
            }
        }
    }

    private fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        failures.entries.removeIf { it.value.firstFailureAt < cutoff }
    }
}
