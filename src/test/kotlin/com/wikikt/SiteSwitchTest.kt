package com.wikikt

import com.wikikt.auth.SiteHandoff
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Switching which site the admin console manages should move the whole console to that site — its URL,
 * its chrome, and the links that leave it — not just the data underneath. These cover the parts:
 *
 *  - the cross-host jump (`/a/sites/select` → the target host's `/a/handoff`) and the one-time ticket
 *    that re-establishes the login there, so switching doesn't mean logging in again;
 *  - the two guards on that jump: HTTPS, and evidence that host-based routing resolves for this browser
 *    (without which it would strand the admin on a host their machine can't reach);
 *  - what the console shows while it *is* managing another site: that site's branding, and links into
 *    that site rather than same-path links into whichever site the current host serves.
 */
class SiteSwitchTest {
    /**
     * [https] models the deployment shape the cross-host jump requires: a TLS-terminating proxy in
     * front, announcing the real scheme in X-Forwarded-Proto (which [proxiedFrom] then sends). Without
     * it the app sees plain HTTP and the switcher refuses to move the browser.
     */
    private fun ApplicationTestBuilder.h2(name: String, https: Boolean = false) {
        environment {
            config = MapApplicationConfig(
                *listOfNotNull(
                    "wikikt.defaultLocale" to "en",
                    "wikikt.defaultAdmin.username" to "admin",
                    "wikikt.defaultAdmin.password" to "test",
                    "wikikt.database.type" to "h2",
                    "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-$name-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                    "wikikt.database.h2.username" to "sa",
                    "wikikt.database.h2.password" to "",
                    ("wikikt.server.trustProxy" to "true").takeIf { https },
                ).toTypedArray(),
            )
        }
        application { configure() }
    }

    /** Headers standing in for a proxied request: the host it arrived on, and whether that hop was TLS. */
    private fun HttpRequestBuilder.proxiedFrom(host: String, https: Boolean = true) {
        header("Host", host)
        if (https) {
            header("X-Forwarded-Proto", "https")
            header("X-Forwarded-Port", "443")
        }
    }

    /** Ids of the configured sites, in list order (the seeded catch-all first, then any added). */
    private suspend fun HttpClient.siteIds(): List<String> =
        Regex("""/a/sites/(\d+)/edit""").findAll(get("/a/sites").bodyAsText())
            .map { it.groupValues[1] }.distinct().toList()

    private suspend fun HttpClient.saveSite(id: String?, name: String, hostname: String, catchAll: Boolean, csrf: String) =
        post(if (id == null) "/a/sites" else "/a/sites/$id") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("name", name)
                        append("hostname", hostname)
                        if (catchAll) append("isCatchAll", "on")
                    },
                ),
            )
        }

    private suspend fun HttpClient.selectSite(
        siteId: String,
        returnPath: String,
        host: String,
        csrf: String,
        https: Boolean = true,
    ) = post("/a/sites/select") {
        proxiedFrom(host, https)
        setBody(
            FormDataContent(
                Parameters.build {
                    append("_csrf", csrf)
                    append("siteId", siteId)
                    append("return", returnPath)
                },
            ),
        )
    }

    private suspend fun HttpClient.setSiteName(name: String, host: String, csrf: String) =
        post("/a/settings") {
            proxiedFrom(host, https = false)
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("siteName", name) }))
        }

    private fun titleOf(html: String): String =
        Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim().orEmpty()

    @Test
    fun `switching to a site with its own host jumps there and carries the login`() = testApplication {
        h2("site-switch-jump", https = true)
        SiteHandoff.reset()

        val admin = createClient { install(HttpCookies); followRedirects = false }
        val csrf = admin.loginAsAdmin()
        admin.saveSite(id = null, name = "Docs", hostname = "docs.test", catchAll = false, csrf = csrf)
        // A third site with no hostname: only ever managed via a stay-put switch (see the end of this test).
        admin.saveSite(id = null, name = "Internal", hostname = "", catchAll = false, csrf = csrf)
        val ids = admin.siteIds()
        val (mainId, docsId) = ids[0] to ids[1]
        val internalId = ids[2]
        // Give the catch-all a host of its own too, so a request can arrive by hostname match — the
        // evidence the switcher needs before it will move the browser anywhere.
        admin.saveSite(mainId, name = "Main", hostname = "main.test", catchAll = true, csrf = csrf)

        val select = admin.selectSite(docsId, returnPath = "/a/pages", host = "main.test", csrf = csrf)
        assertEquals(HttpStatusCode.Found, select.status)
        val location = select.headers["Location"].orEmpty()
        assertTrue(
            location.startsWith("https://docs.test/a/handoff?t="),
            "switching to a hostname'd site redirects to that host, over the scheme we arrived on: $location",
        )

        // A browser that has never authenticated on docs.test — the situation the ticket exists for.
        val ticket = location.substringAfter("/a/handoff?t=")
        val fresh = createClient { install(HttpCookies); followRedirects = false }
        assertEquals(
            HttpStatusCode.Forbidden,
            fresh.get("/a/pages") { proxiedFrom("docs.test") }.status,
            "this browser is not signed in on docs.test yet",
        )

        val handoff = fresh.get("/a/handoff?t=$ticket") { proxiedFrom("docs.test") }
        assertEquals(HttpStatusCode.Found, handoff.status)
        assertEquals("/a/pages", handoff.headers["Location"], "lands on the page the admin was on")
        val landed = fresh.get("/a/pages") { proxiedFrom("docs.test") }
        assertEquals(HttpStatusCode.OK, landed.status, "the ticket signed this browser in on the new host")
        assertTrue(
            landed.bodyAsText().contains("""admin-site-label">Docs<"""),
            "and the console there is managing the site it jumped to",
        )

        // Single use. The browser that just redeemed it is the one that can navigate Back onto this URL,
        // so it gets the console rather than a pointless login form — but the ticket itself is dead, as
        // a session-less browser presenting the very same one proves.
        val back = fresh.get("/a/handoff?t=$ticket") { proxiedFrom("docs.test") }
        assertEquals("/a", back.headers["Location"], "Back onto the spent handoff URL returns to the console")
        val stranger = createClient { install(HttpCookies); followRedirects = false }
        assertEquals(
            "/login",
            stranger.get("/a/handoff?t=$ticket") { proxiedFrom("docs.test") }.headers["Location"],
            "a spent ticket is not a way in",
        )

        val second = admin.selectSite(docsId, returnPath = "/a", host = "main.test", csrf = csrf)
        val stolen = second.headers["Location"].orEmpty().substringAfter("/a/handoff?t=")
        val elsewhere = createClient { install(HttpCookies); followRedirects = false }
        assertEquals(
            "/login",
            elsewhere.get("/a/handoff?t=$stolen") { proxiedFrom("main.test") }.headers["Location"],
            "a ticket is pinned to the site it was issued for",
        )

        // A stay-put selection made ON the target host must not hijack a later jump onto it. From
        // docs.test, pick the hostname-less Internal site — nowhere to move, so a cookie remembers it
        // (the same CSRF token works: the handoff carried the session, it didn't mint a second one) —
        // then redeem a fresh ticket for Docs. Arriving by handoff must mean managing Docs again.
        val stay = fresh.selectSite(internalId, returnPath = "/a/pages", host = "docs.test", csrf = csrf)
        assertEquals("/a/pages", stay.headers["Location"], "a hostname-less site is managed from here")
        assertTrue(
            fresh.get("/a/pages") { proxiedFrom("docs.test") }.bodyAsText().contains("""admin-site-label">Internal<"""),
            "the stay-put selection stuck",
        )
        val third = admin.selectSite(docsId, returnPath = "/a/pages", host = "main.test", csrf = csrf)
        val ticket3 = third.headers["Location"].orEmpty().substringAfter("/a/handoff?t=")
        fresh.get("/a/handoff?t=$ticket3") { proxiedFrom("docs.test") }
        assertTrue(
            fresh.get("/a/pages") { proxiedFrom("docs.test") }.bodyAsText().contains("""admin-site-label">Docs<"""),
            "jumping onto this host overrides a stale stay-put selection made here",
        )
    }

    @Test
    fun `site switch jump needs HTTPS`() = testApplication {
        h2("site-switch-https")
        SiteHandoff.reset()

        val admin = createClient { install(HttpCookies); followRedirects = false }
        val csrf = admin.loginAsAdmin()
        admin.saveSite(id = null, name = "Docs", hostname = "docs.test", catchAll = false, csrf = csrf)
        val ids = admin.siteIds()
        val (mainId, docsId) = ids[0] to ids[1]
        admin.saveSite(mainId, name = "Main", hostname = "main.test", catchAll = true, csrf = csrf)

        // Everything the jump wants is in place except TLS: the request matched a site by hostname and
        // the target has a host of its own. A cross-host redirect would hand a credential to another
        // host over a plaintext hop, so the switcher stays where it is.
        val select = admin.selectSite(docsId, returnPath = "/a/pages", host = "main.test", csrf = csrf, https = false)
        assertEquals(HttpStatusCode.Found, select.status)
        assertEquals("/a/pages", select.headers["Location"], "no cross-host jump over plain HTTP")

        // And nothing can be redeemed over plain HTTP either, however the ticket was come by.
        val fresh = createClient { install(HttpCookies); followRedirects = false }
        val overHttp = fresh.get("/a/handoff?t=whatever") { proxiedFrom("docs.test", https = false) }
        assertEquals("/login", overHttp.headers["Location"], "the handoff endpoint is HTTPS-only")
    }

    @Test
    fun `switching stays put when the request did not arrive by hostname`() = testApplication {
        h2("site-switch-guard", https = true)
        SiteHandoff.reset()

        val admin = createClient { install(HttpCookies); followRedirects = false }
        val csrf = admin.loginAsAdmin()
        admin.saveSite(id = null, name = "Docs", hostname = "docs.test", catchAll = false, csrf = csrf)
        val ids = admin.siteIds()
        val (mainId, docsId) = ids[0] to ids[1]
        admin.saveSite(mainId, name = "Main", hostname = "main.test", catchAll = true, csrf = csrf)

        // "localhost" matches no site's hostname, so this request fell through to the catch-all — which
        // proves nothing about whether docs.test resolves for this browser. Stay, don't jump.
        val select = admin.selectSite(docsId, returnPath = "/a/pages", host = "localhost", csrf = csrf)
        assertEquals(HttpStatusCode.Found, select.status)
        assertEquals("/a/pages", select.headers["Location"], "no cross-host jump from an unmatched host")

        // ...and the console is nonetheless managing Docs, the way it always has.
        admin.setSiteName("Docs Wiki", host = "localhost", csrf = csrf)
        val pages = admin.get("/a/pages") { proxiedFrom("localhost", https = false) }.bodyAsText()
        assertTrue(titleOf(pages).contains("Docs Wiki"), "console wears the managed site's branding: ${titleOf(pages)}")
    }

    @Test
    fun `while managing another site the console links into that site, not this host`() = testApplication {
        h2("site-switch-links")
        SiteHandoff.reset()

        val admin = createClient { install(HttpCookies); followRedirects = false }
        val csrf = admin.loginAsAdmin()
        admin.saveSite(id = null, name = "Docs", hostname = "docs.test", catchAll = false, csrf = csrf)
        val docsId = admin.siteIds()[1]

        // A page that exists on both sites at the same path, so a relative link would look like it
        // worked while opening the wrong one.
        admin.createSamplePage(csrf, path = "shared/notes", title = "Main Notes")
        admin.post("/u/v1/pages") {
            header("Host", "docs.test")
            header("X-CSRF-Token", csrf)
            contentType(ContentType.Application.Json)
            setBody("""{"locale":"en","path":"shared/notes","title":"Docs Notes","content":"On Docs.","published":true}""")
        }

        admin.selectSite(docsId, returnPath = "/a/pages", host = "localhost", csrf = csrf, https = false)
        val html = admin.get("/a/pages") { header("Host", "localhost") }.bodyAsText()
        assertTrue(
            html.contains("http://docs.test/en/shared/notes"),
            "View links point at the managed site's own host, not this one",
        )
        assertTrue(
            html.contains("http://docs.test/e/en/shared/notes"),
            "so do Edit links",
        )
    }

    @Test
    fun `a hostname must be a bare host`() = testApplication {
        h2("site-switch-hostname")

        val admin = createClient { install(HttpCookies) }
        val csrf = admin.loginAsAdmin()

        val bad = admin.saveSite(id = null, name = "Bad", hostname = "https://evil.test/x", catchAll = false, csrf = csrf)
        assertEquals(HttpStatusCode.OK, bad.status, "the form is re-rendered rather than saved")
        assertTrue(bad.bodyAsText().contains("a valid hostname"), "and says why")
        assertEquals(1, admin.siteIds().size, "no site was created")

        val good = admin.saveSite(id = null, name = "Docs", hostname = "docs.example.com", catchAll = false, csrf = csrf)
        assertEquals(HttpStatusCode.Found, good.status, "a bare host saves")
        assertNotNull(admin.siteIds().getOrNull(1))
    }
}
