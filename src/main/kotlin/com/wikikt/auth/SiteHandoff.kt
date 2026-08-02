package com.wikikt.auth

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * One-time tickets that carry an admin's login to another hostname when the site switcher jumps to the
 * site's own host (`POST /a/sites/select` issues one, `GET /a/handoff` redeems it).
 *
 * A session *cookie* is scoped to the host that set it, but a WikiKT *session* is not: sessions are
 * instance-global rows and the cookie is sealed with instance-wide keys, so the same cookie value is
 * valid on every host this instance serves. A ticket is therefore only a short-lived pointer to the
 * cookie the browser already holds, handed to the other host so it can set it there too — no second
 * login, no second session row, and a logout on either host still ends the one session.
 *
 * Held in memory rather than the database: tickets live for seconds, are consumed once, and are
 * worthless after a restart (the admin just switches again). Single-instance only, like the throttles.
 *
 * Ticket safety: 256 random bits, [TTL_MS] to be redeemed, dropped on first presentation, and pinned to
 * the site that issued it, so replaying one at another host fails. The ticket rides in a redirect URL and
 * can therefore reach a request log — the short TTL and single use mean a logged ticket is already dead —
 * and the instance-wide `Referrer-Policy: strict-origin-when-cross-origin` keeps it out of cross-origin
 * referers.
 *
 * A ticket IS a credential for the session it names, so redeeming one signs that browser in as that user:
 * an admin who fed their own ticket to someone else within the minute would be signing them in as
 * themselves (the login-CSRF shape). Accepted deliberately — minting one takes manage:groups, which is
 * already the keys to the instance, and the victim lands on a console plainly logged in as someone else.
 */
object SiteHandoff {
    /**
     * How long a ticket stays redeemable. The browser redeems it on the next hop of a redirect it is
     * already following, so this only has to cover one round trip; the rest is slack for a slow link.
     * Kept short deliberately — the ticket's exposure is the gap between issue and redemption.
     */
    private const val TTL_MS = 15_000L
    private const val PRUNE_SIZE = 1_000

    /** What the far host needs to finish the switch: the cookie to set, whose host it is for, where to land. */
    data class Ticket(
        val session: UserSession,
        val siteId: UInt,
        val returnPath: String,
        val expiresAt: Long,
    )

    private val random = SecureRandom()
    private val tickets = ConcurrentHashMap<String, Ticket>()

    /** Issues a ticket that re-establishes [session] on [siteId]'s host and lands on [returnPath]. */
    fun issue(session: UserSession, siteId: UInt, returnPath: String): String {
        if (tickets.size > PRUNE_SIZE) pruneExpired()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tickets[id] = Ticket(session, siteId, returnPath, System.currentTimeMillis() + TTL_MS)
        return id
    }

    /**
     * Redeems [id] for the site serving the redeeming request, or returns null if there is no such live
     * ticket or it was issued for a different site. Removes the ticket either way, so one is redeemable
     * exactly once whether or not it reached the host it was meant for.
     */
    fun consume(id: String?, siteId: UInt): Ticket? {
        if (id.isNullOrEmpty()) return null
        val ticket = tickets.remove(id) ?: return null
        if (ticket.expiresAt < System.currentTimeMillis()) return null
        if (ticket.siteId != siteId) return null
        return ticket
    }

    /** Drops every outstanding ticket (test isolation). */
    fun reset() {
        tickets.clear()
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        tickets.entries.removeIf { it.value.expiresAt < now }
    }
}
