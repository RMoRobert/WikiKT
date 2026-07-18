package com.wikikt.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory throttle for the MFA second-factor step, keyed by user id. A 6-digit code is only a million
 * possibilities, so without a cap an attacker who already has the password could brute-force it within a
 * code's validity window; this bounds the attempts. Keyed by user (not host) because the pending login is
 * already tied to one account. Mirrors [LoginThrottle]/[RegisterThrottle]: in-memory, not shared across
 * nodes, a first line of defense for a single instance.
 */
object MfaThrottle {
    private const val MAX_FAILURES = 5
    private const val WINDOW_MS = 5 * 60_000L // 5 minutes
    private const val PRUNE_SIZE = 10_000

    private data class Attempts(val count: Int, val firstAt: Long)

    private val failures = ConcurrentHashMap<UInt, Attempts>()

    fun isLockedOut(userId: UInt): Boolean {
        val entry = failures[userId] ?: return false
        if (System.currentTimeMillis() - entry.firstAt > WINDOW_MS) {
            failures.remove(userId)
            return false
        }
        return entry.count >= MAX_FAILURES
    }

    fun recordFailure(userId: UInt) {
        if (failures.size > PRUNE_SIZE) pruneExpired()
        val now = System.currentTimeMillis()
        failures.compute(userId) { _, existing ->
            if (existing == null || now - existing.firstAt > WINDOW_MS) Attempts(1, now)
            else existing.copy(count = existing.count + 1)
        }
    }

    fun recordSuccess(userId: UInt) {
        failures.remove(userId)
    }

    /** Clears all recorded failures (test isolation). */
    fun reset() = failures.clear()

    private fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        failures.entries.removeIf { it.value.firstAt < cutoff }
    }
}
