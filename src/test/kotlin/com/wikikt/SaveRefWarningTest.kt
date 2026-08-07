package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The post-save "references something that doesn't exist yet" banner: the save always goes through,
 * the redirect carries ?refwarn only when the saved content had a missing file/page reference, and the
 * landing page recomputes (so the banner self-heals once the file is uploaded / the page written).
 */
class SaveRefWarningTest {
    private fun ApplicationTestBuilder.configureApp(dbName: String) {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///$dbName;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }
    }

    @Test
    fun `save with missing refs warns, clean save does not, and the banner self-heals`() = testApplication {
        configureApp("wikikt-refwarn-test")

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        // An existing link target, so the test proves existing pages don't warn.
        client.post("/u/v1/pages") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"locale":"en","path":"docs/target","title":"T","content":"x","published":true}""")
        }

        val content = """
            ![missing](/img/missing.png)
            [existing page](/en/docs/target)
            [unwritten page](/en/docs/unwritten)
            [another unwritten page](/en/docs/unwritten-two)
            [tag route](/t/howto)
            [app route](/login)
            [external](https://example.com/pic.png)
        """.trimIndent()
        val saved = client.post("/e/docs/main") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf); append("title", "Main"); append("content", content)
                        append("contentFormat", "MARKDOWN"); append("locale", "en"); append("path", "docs/main")
                        append("published", "on")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Found, saved.status)
        val location = saved.headers["Location"]!!
        assertTrue(location.endsWith("/en/docs/main?refwarn"), "redirect carries the flag: $location")

        // The editor who saved sees the banner: the missing file, the unwritten pages (as
        // comma-separated create links), and nothing about the existing page, app routes, or the
        // external URL. Assertions anchor on the wk-refwarn-* class hooks and structural markup
        // (hrefs, <code> contents) — never on display copy, so the banner can be reworded freely.
        val body = client.get(location).bodyAsText()
        assertTrue(body.contains("wk-refwarn-assets"), "asset warning shown")
        assertTrue(body.contains("<code>/img/missing.png</code>"), "missing file listed")
        assertTrue(body.contains("wk-refwarn-pages"), "page warning shown")
        assertTrue(body.contains("href=\"/e/en/docs/unwritten\""), "missing page links to its create URL")
        assertTrue(body.contains("</a>, <a href=\"/e/en/docs/unwritten-two\""), "page links are comma-separated")
        assertFalse(body.contains("href=\"/e/en/docs/target\""), "existing page does not warn")
        assertFalse(body.contains("href=\"/e/en/t/howto\""), "tag route is not a missing page")
        assertFalse(body.contains("href=\"/e/en/login\""), "reserved route is not a missing page")

        // A reader without edit rights sees no banner on the same URL.
        val anon = createClient { followRedirects = false }
        val anonBody = anon.get(location).let { assertEquals(HttpStatusCode.OK, it.status); it.bodyAsText() }
        assertFalse(anonBody.contains("wk-refwarn"), "banner is editor-only")

        // Self-healing: once a linked page exists, a refresh drops it from the banner (the file and
        // the other page are still missing, so the banner itself stays).
        client.post("/u/v1/pages") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"locale":"en","path":"docs/unwritten","title":"U","content":"x","published":true}""")
        }
        val healed = client.get(location).bodyAsText()
        assertTrue(healed.contains("<code>/img/missing.png</code>"), "file still missing")
        assertFalse(healed.contains("href=\"/e/en/docs/unwritten\""), "created page no longer warns")
        assertTrue(healed.contains("href=\"/e/en/docs/unwritten-two\""), "still-missing page keeps warning")

        // A clean save redirects without the flag.
        val clean = client.post("/e/docs/main") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf); append("title", "Main")
                        append("content", "[fine](/en/docs/target)")
                        append("contentFormat", "MARKDOWN"); append("applyMode", "now")
                        append("locale", "en"); append("path", "docs/main")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Found, clean.status)
        assertFalse(clean.headers["Location"]!!.contains("refwarn"), "no flag on a clean save")
    }

    @Test
    fun `staged save lands on the editor with the banner and relative refs resolve against the page`() = testApplication {
        configureApp("wikikt-refwarn-staged-test")

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        client.post("/u/v1/pages") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"locale":"en","path":"guide/setup","title":"S","content":"x","published":true}""")
        }

        // Directory-relative refs resolve the way render does: the page path is a directory, so
        // `steps` on guide/setup targets guide/setup/steps, and `shot.png` its sibling asset path.
        val staged = client.post("/e/en/guide/setup") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf); append("title", "S")
                        append("content", "[next](steps)\n\n![shot](shot.png)")
                        append("contentFormat", "MARKDOWN"); append("applyMode", "staged")
                        append("locale", "en"); append("path", "guide/setup")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Found, staged.status)
        val location = staged.headers["Location"]!!
        assertTrue(location.endsWith("/e/en/guide/setup?refwarn"), "staged save flags the editor URL: $location")

        val editor = client.get(location).bodyAsText()
        assertTrue(editor.contains("wk-refwarn-assets"), "editor shows the banner")
        assertTrue(editor.contains("<code>/guide/setup/shot.png</code>"), "relative embed resolved against the page path")
        assertTrue(editor.contains("href=\"/e/en/guide/setup/steps\""), "relative link resolved and offered as create URL")

        // The live view (no staged preview) was not made warning-free by the staged save: visiting
        // the plain view URL without the flag shows no banner at all.
        val plainView = client.get("/en/guide/setup").bodyAsText()
        assertFalse(plainView.contains("wk-refwarn"), "no banner without the flag")
    }
}
