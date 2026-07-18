package com.wikikt.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory rate limiter for the public password-reset REQUEST endpoint, keyed by client host. Unlike
 * [LoginThrottle] there is no per-account key: the endpoint never reveals whether an email exists, so
 * there's no username to key on and nothing to gain from locking a specific account — the goal is only
 * to stop one source from flooding the mail queue (and victims' inboxes) with reset requests.
 *
 * Not durable across restarts and not shared across nodes — a first line of defense for a single-instance
 * deployment, matching [LoginThrottle].
 */
object ResetRequestThrottle {
    private const val MAX_REQUESTS = 5
    private const val WINDOW_MS = 15 * 60_000L // 15 minutes
    private const val PRUNE_SIZE = 10_000

    private data class Attempts(val count: Int, val firstAt: Long)

    private val requests = ConcurrentHashMap<String, Attempts>()

    fun isLockedOut(host: String): Boolean {
        val entry = requests[host] ?: return false
        if (System.currentTimeMillis() - entry.firstAt > WINDOW_MS) {
            requests.remove(host)
            return false
        }
        return entry.count >= MAX_REQUESTS
    }

    fun record(host: String) {
        if (requests.size > PRUNE_SIZE) pruneExpired()
        val now = System.currentTimeMillis()
        requests.compute(host) { _, existing ->
            if (existing == null || now - existing.firstAt > WINDOW_MS) Attempts(1, now)
            else existing.copy(count = existing.count + 1)
        }
    }

    /** Clears all recorded requests (test isolation). */
    fun reset() = requests.clear()

    private fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        requests.entries.removeIf { it.value.firstAt < cutoff }
    }
}
