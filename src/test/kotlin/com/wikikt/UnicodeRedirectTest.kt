package com.wikikt

import com.wikikt.routing.headerSafePath
import com.wikikt.routing.headerSafeUrl
import com.wikikt.routing.sanitizeSameSiteRedirect
import com.wikikt.routing.wikiEditUrl
import com.wikikt.routing.wikiViewUrl
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.contentType
import io.ktor.http.decodeURLPart
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Redirects to a page whose path holds non-ASCII characters must carry a pure-ASCII `Location`. Netty
 * writes header chars as single bytes, so a raw `/en/docs/日本` left the server as `/en/docs/??`.
 * testApplication doesn't go through Netty's encoder, so these tests assert the header value itself is
 * printable ASCII, and then follow it to prove it lands on the page.
 */
class UnicodeRedirectTest {
    // 日本 / 日本語 / 新しい, percent-encoded as UTF-8.
    private val nihon = "%E6%97%A5%E6%9C%AC"
    private val nihongo = "%E6%97%A5%E6%9C%AC%E8%AA%9E"
    private val atarashii = "%E6%96%B0%E3%81%97%E3%81%84"

    @Test
    fun `headerSafePath encodes non-ASCII segments as UTF-8 and keeps the slashes`() {
        assertEquals("/en/docs/$nihon", headerSafePath(wikiViewUrl("en", "docs/日本")))
        assertEquals("/e/en/docs/caf%C3%A9/$nihongo", headerSafePath(wikiEditUrl("en", "docs/café/日本語")))
        // A supplementary-plane character (a surrogate pair in Kotlin) is one 4-byte UTF-8 sequence.
        assertEquals("/en/docs/%F0%9F%98%80", headerSafePath("/en/docs/😀"))
    }

    @Test
    fun `headerSafePath leaves an ordinary ASCII path untouched`() {
        for (url in listOf("/en/home", "/e/pt-BR/guide/intro-page_2", "/h/en/a/b/c", "/en/x.y~z")) {
            assertEquals(url, headerSafePath(url))
        }
    }

    @Test
    fun `headerSafePath encodes a literal percent, query or fragment char so the path round-trips`() {
        // validateWikiPath forbids these on create, but an imported or API-made page can still have them.
        assertEquals("/en/docs/100%25-sure%3F%20%23x", headerSafePath("/en/docs/100%-sure? #x"))
        for (raw in listOf("/en/docs/日本", "/en/docs/100%-sure? #x", "/en/docs/caf%C3%A9")) {
            assertEquals(raw, headerSafePath(raw).decodeURLPart(), "decoding the header gives back the raw path")
        }
    }

    @Test
    fun `headerSafeUrl encodes only non-ASCII and leaves an existing URL's escapes and query alone`() {
        assertEquals("/en/docs/$nihon?x=1&y=%41#top", headerSafeUrl("/en/docs/日本?x=1&y=%41#top"))
        val ascii = "/en/docs/$nihon?x=1&y=%41#top"
        assertEquals(ascii, headerSafeUrl(ascii), "already-ASCII input is returned as-is, so it never double-encodes")
        assertEquals("/", headerSafeUrl("/"))
        // redirectCanonical runs the request's query string through it: escapes, `&`, `=` and `+` stay as sent.
        assertEquals("x=%E6%97%A5&y=%41+b", headerSafeUrl("x=日&y=%41+b"))
    }

    @Test
    fun `a sanitized Unicode return path stays same-site after header encoding`() {
        assertEquals("/en/docs/$nihon", headerSafeUrl(sanitizeSameSiteRedirect("/en/docs/日本")))
        // Encoding happens after the guard, and only replaces non-ASCII chars, so the escapes still fall back.
        assertEquals("/", headerSafeUrl(sanitizeSameSiteRedirect("//日本.example")))
        assertEquals("/", headerSafeUrl(sanitizeSameSiteRedirect("/\t/evil.com")))
        // A fullwidth solidus is not a '/': it's encoded, never turned into a protocol-relative URL.
        assertEquals("/%EF%BC%8Fevil.com", headerSafeUrl(sanitizeSameSiteRedirect("/／evil.com")))
    }

    @Test
    fun `saving, moving and creating a page at a Unicode path redirect to an ASCII Location`() = testApplication {
        testEnv("wikikt-unicode-redirect-save")
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()

        // Create through the editor.
        val created = client.editorSave(csrf, "/e/en/docs/$nihon") { append("path", "docs/日本"); append("published", "1") }
        assertEquals(HttpStatusCode.Found, created.status)
        assertLandsOn(client, created, "/en/docs/$nihon", "Unicode page")

        // A staged save comes back to the editor at the same path; discarding it does too.
        val staged = client.editorSave(csrf, "/e/en/docs/$nihon") { append("path", "docs/日本"); append("applyMode", "staged") }
        assertEquals(HttpStatusCode.Found, staged.status)
        assertLandsOn(client, staged, "/e/en/docs/$nihon", "Unicode page")
        val discarded = client.post("/e/en/docs/$nihon") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("discardStaged", "1") }))
        }
        assertLandsOn(client, discarded, "/e/en/docs/$nihon", "Unicode page")

        // Move to another Unicode path (Update now with a changed path).
        val moved = client.editorSave(csrf, "/e/en/docs/$nihon") { append("path", "docs/café/日本語"); append("applyMode", "now") }
        assertEquals(HttpStatusCode.Found, moved.status)
        assertLandsOn(client, moved, "/en/docs/caf%C3%A9/$nihongo", "Unicode page")
        assertEquals(HttpStatusCode.NotFound, client.get("/en/docs/$nihon").status, "the old path no longer resolves")

        // Page actions → Convert to HTML returns to the page.
        val converted = client.post("/e/en/docs/caf%C3%A9/$nihongo") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("convertFormat", "HTML") }))
        }
        assertLandsOn(client, converted, "/en/docs/caf%C3%A9/$nihongo", "Unicode page")

        // Restoring a revision returns to the page.
        val restored = client.post("/h/en/docs/caf%C3%A9/$nihongo") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("rev", "1") }))
        }
        assertLandsOn(client, restored, "/en/docs/caf%C3%A9/$nihongo", "Unicode page")

        // New page (Page actions → New page) sends the user to the editor for the chosen path.
        val fresh = client.post("/new") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("locale", "en"); append("path", "docs/新しい") }))
        }
        assertEquals(HttpStatusCode.Found, fresh.status)
        assertLandsOn(client, fresh, "/e/en/docs/$atarashii", null)
    }

    @Test
    fun `the canonical locale redirect encodes the path, keeps the query, and round-trips a literal percent`() = testApplication {
        testEnv("wikikt-unicode-redirect-canonical")
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()
        client.createSamplePage(csrf, path = "docs/日本", title = "Unicode page")
        // The JSON API is permissive about path characters, so a page can hold a literal % or ?.
        client.createSamplePage(csrf, path = "docs/100%-sure?", title = "Percent page")
        client.post("/a/settings/locale") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("localeForcePrefix", "1") }))
        }

        val bare = client.get("/docs/$nihon?x=1")
        assertEquals(HttpStatusCode.MovedPermanently, bare.status)
        assertLandsOn(client, bare, "/en/docs/$nihon?x=1", "Unicode page")
        assertLandsOn(client, client.get("/e/docs/$nihon"), "/e/en/docs/$nihon", "Unicode page")
        assertEquals("/h/en/docs/$nihon", asciiLocation(client.get("/h/docs/$nihon")))

        val percent = client.get("/docs/100%25-sure%3F")
        assertEquals(HttpStatusCode.MovedPermanently, percent.status)
        assertLandsOn(client, percent, "/en/docs/100%25-sure%3F", "Percent page")
    }

    @Test
    fun `a Unicode post-login return path redirects to an ASCII Location`() = testApplication {
        testEnv("wikikt-unicode-redirect-login")
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = Regex("""name="_csrf" value="([^"]+)"""").find(client.get("/login").bodyAsText())!!.groupValues[1]
        val login = client.post("/login") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf); append("username", "admin"); append("password", "test")
                        append("redirect", "/en/docs/日本?x=1")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Found, login.status)
        assertEquals("/en/docs/$nihon?x=1", asciiLocation(login))
    }

    private fun ApplicationTestBuilder.testEnv(db: String) {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///$db;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }
    }

    /** POSTs the editor form for a page titled "Unicode page"; [extra] adds path/applyMode/etc. */
    private suspend fun HttpClient.editorSave(csrf: String, url: String, extra: ParametersBuilder.() -> Unit) = post(url) {
        setBody(
            FormDataContent(
                Parameters.build {
                    append("_csrf", csrf); append("title", "Unicode page"); append("content", "hello")
                    append("contentFormat", "MARKDOWN"); append("locale", "en")
                    extra()
                },
            ),
        )
    }

    /** The response's `Location`, asserted to be printable ASCII. */
    private fun asciiLocation(response: HttpResponse): String {
        val location = response.headers[HttpHeaders.Location] ?: fail("expected a Location header, got ${response.status}")
        assertTrue(location.all { it in '!'..'~' }, "Location must be printable ASCII, got: $location")
        return location
    }

    /** Asserts the redirect goes to [expected] (ASCII), then follows it and checks it serves the page. */
    private suspend fun assertLandsOn(client: HttpClient, response: HttpResponse, expected: String, title: String?) {
        assertEquals(expected, asciiLocation(response))
        val landed = client.get(expected)
        assertEquals(HttpStatusCode.OK, landed.status, "following $expected")
        if (title != null) assertTrue(landed.bodyAsText().contains(title), "$expected should serve the \"$title\" page")
    }
}
