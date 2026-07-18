package com.wikikt.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory rate limiter for the public self-registration endpoint. Blunts bulk or bot sign-ups; the
 * email-confirmation requirement and the optional domain allowlist are the stronger controls. Two keys:
 *
 *  - a PER-HOST bucket (like [ResetRequestThrottle]) that caps one source, and
 *  - a SITE-WIDE bucket that caps registration-triggered emails across ALL sources. Each self-registration
 *    makes the server send mail (a confirmation to the entered address, or an "email already in use" notice
 *    to the existing owner), so registration is an outbound-email amplifier. The per-host cap alone doesn't
 *    stop a distributed/botnet attacker who rotates source IPs — each fresh IP gets a fresh per-host bucket
 *    — so the global cap bounds how much mail the whole endpoint can emit per window, protecting the
 *    deployment's sender reputation. The trade-off: a burst past the global cap briefly pauses registration
 *    for everyone (self-healing when the window elapses) — reputation over open availability.
 *
 * Not durable across restarts and not shared across nodes — a first line of defense for a single-instance
 * deployment, matching [LoginThrottle]/[ResetRequestThrottle].
 */
object RegisterThrottle {
    private const val MAX_REQUESTS = 5 // per host, per window
    private const val GLOBAL_MAX_REQUESTS = 30 // site-wide, per window (caps the email-amplification ceiling)
    private const val WINDOW_MS = 15 * 60_000L // 15 minutes
    private const val PRUNE_SIZE = 10_000

    private data class Attempts(val count: Int, val firstAt: Long)

    private val requests = ConcurrentHashMap<String, Attempts>()

    private val globalLock = Any()
    private var globalCount = 0
    private var globalWindowStart = 0L

    fun isLockedOut(host: String): Boolean {
        val entry = requests[host] ?: return false
        if (System.currentTimeMillis() - entry.firstAt > WINDOW_MS) {
            requests.remove(host)
            return false
        }
        return entry.count >= MAX_REQUESTS
    }

    /** Whether the site-wide registration-email budget for the current window is exhausted. */
    fun isGloballyLockedOut(): Boolean = synchronized(globalLock) {
        if (System.currentTimeMillis() - globalWindowStart > WINDOW_MS) false
        else globalCount >= GLOBAL_MAX_REQUESTS
    }

    fun record(host: String) {
        if (requests.size > PRUNE_SIZE) pruneExpired()
        val now = System.currentTimeMillis()
        requests.compute(host) { _, existing ->
            if (existing == null || now - existing.firstAt > WINDOW_MS) Attempts(1, now)
            else existing.copy(count = existing.count + 1)
        }
        synchronized(globalLock) {
            if (now - globalWindowStart > WINDOW_MS) {
                globalWindowStart = now
                globalCount = 1
            } else {
                globalCount++
            }
        }
    }

    /** Clears all recorded requests (test isolation). */
    fun reset() {
        requests.clear()
        synchronized(globalLock) {
            globalCount = 0
            globalWindowStart = 0L
        }
    }

    private fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        requests.entries.removeIf { it.value.firstAt < cutoff }
    }
}
