package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A fresh install renders a single newline as a `<br>` rather than joining the lines into one paragraph
 * (the default in `RenderOptions`, not CommonMark's joining behaviour). Guards both halves of that: the
 * default, and the admin's ability to turn it back off (which is also what proves it is a real setting,
 * not a hardcoded renderer change).
 */
class LineBreakDefaultTest {
    @Test
    fun `a new install renders single newlines as line breaks, and the setting still wins`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-linebreak-default-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()

        // Three hand-wrapped lines: three lines in Wiki.js, one joined paragraph under strict CommonMark.
        val create = client.post("/u/v1/pages") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"locale":"en","path":"breaks","title":"Breaks","content":"alpha\nbeta\ngamma","published":true}""")
        }
        assertEquals(HttpStatusCode.Created, create.status, "page created: ${create.bodyAsText()}")

        val fresh = articleOf(client.get("/en/breaks").bodyAsText())
        assertTrue(fresh.contains("<br>"), "new install renders line breaks by default: $fresh")

        // Unchecking the box in Administration > Settings > Rendering turns it off (and bumps the render
        // epoch, so the cached HTML is rebuilt on the next view).
        val off = client.post("/a/settings/rendering") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("_csrf=${java.net.URLEncoder.encode(csrf, "UTF-8")}")
        }
        assertEquals(HttpStatusCode.OK, off.status, "rendering settings saved")

        val plain = articleOf(client.get("/en/breaks").bodyAsText())
        assertFalse(plain.contains("<br>"), "setting off wins over the default: $plain")
    }

    /** Just the rendered page body — the surrounding shell has its own markup to not match against. */
    private fun articleOf(html: String): String =
        html.substringAfter("<article class=\"wiki-content\"").substringBefore("</article>")
}
