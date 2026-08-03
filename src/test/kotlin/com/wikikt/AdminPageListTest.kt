package com.wikikt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The admin page list (`/a/pages`) is server-side sorted and paginated: the URL carries the sort
 * column, direction, page number and page size, and only that window of rows is fetched and rendered.
 * These tests read the rendered table back, so they cover the routing, the SQL ordering/paging in
 * `PageService.listPaged`, and the template together.
 */
class AdminPageListTest {
    private fun config(db: String) = MapApplicationConfig(
        "wikikt.defaultLocale" to "en",
        "wikikt.defaultAdmin.username" to "admin",
        "wikikt.defaultAdmin.password" to "test",
        "wikikt.database.type" to "h2",
        "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-$db;DB_CLOSE_DELAY=-1",
        "wikikt.database.h2.username" to "sa",
        "wikikt.database.h2.password" to "",
    )

    /** The first cell of every rendered row — the page title, in the order the table lists them. */
    private fun titlesOf(html: String): List<String> {
        val body = html.substringAfter("<tbody>").substringBefore("</tbody>")
        return Regex("""<tr>\s*<td>([^<]*)""").findAll(body).map { it.groupValues[1].trim() }.toList()
    }

    /** Creates a page over the JSON API and returns its id. */
    private suspend fun HttpClient.createPage(
        csrf: String,
        path: String,
        title: String,
        locale: String = "en",
        tags: List<String> = emptyList(),
    ): String {
        val tagList = tags.joinToString(",") { "\"$it\"" }
        val body = post("/u/v1/pages") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody(
                """{"locale":"$locale","path":"$path","title":"$title","content":"Body of $title.",""" +
                    """"published":true,"tags":[$tagList]}""",
            )
        }.bodyAsText()
        return Regex(""""id":"(\d+)"""").find(body)?.groupValues?.get(1) ?: error("no id in $body")
    }

    private suspend fun HttpClient.retitle(csrf: String, id: String, title: String) {
        put("/u/v1/pages/$id") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"title":"$title"}""")
        }
    }

    @Test
    fun `pages are split into windows and every row shows up exactly once`() = testApplication {
        environment { config = config("admin-page-list-paging") }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val csrf = admin.loginAsAdmin()
        // 14 pages + the seeded "home" = 15 rows, so a size of 10 gives two uneven pages.
        val titles = (1..14).map { "Page %02d".format(it) }
        titles.forEachIndexed { i, title -> admin.createPage(csrf, "p/%02d".format(i + 1), title) }

        val first = admin.get("/a/pages?sort=title&size=10").bodyAsText()
        val second = admin.get("/a/pages?sort=title&size=10&page=2").bodyAsText()
        val firstRows = titlesOf(first)
        val secondRows = titlesOf(second)

        assertEquals(10, firstRows.size, "a full first window")
        assertEquals(5, secondRows.size, "the remainder — 14 pages plus the seeded home page")
        assertEquals(emptyList(), firstRows.intersect(secondRows.toSet()).toList(), "windows don't overlap")
        assertEquals(
            (firstRows + secondRows).sortedBy { it.lowercase() },
            firstRows + secondRows,
            "the two windows read as one title-ordered list",
        )
        // The point of paging: rows outside the window aren't fetched, so they aren't in the HTML.
        assertFalse(first.contains("Page 14"), "page 2's rows are absent from page 1")
        assertTrue(first.contains("1–10 of 15"), "the header counts the whole list, not the window: $first")

        // A page number past the end lands on the last page rather than an empty table.
        assertEquals(secondRows, titlesOf(admin.get("/a/pages?sort=title&size=10&page=999").bodyAsText()))
    }

    @Test
    fun `every column sorts, in both directions`() = testApplication {
        environment { config = config("admin-page-list-sorting") }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val csrf = admin.loginAsAdmin()
        admin.createPage(csrf, "zulu", "Alpha", locale = "de", tags = listOf("research"))
        admin.createPage(csrf, "alpha", "Zulu", locale = "fr", tags = listOf("draft"))
        val mikeId = admin.createPage(csrf, "mike", "Mike", locale = "en", tags = emptyList())

        suspend fun titles(query: String) = titlesOf(admin.get("/a/pages?$query").bodyAsText())

        // Title: the default, and its reverse. ("Welcome" is the seeded home page.)
        assertEquals(listOf("Alpha", "Mike", "Welcome", "Zulu"), titles("sort=title"))
        assertEquals(listOf("Zulu", "Welcome", "Mike", "Alpha"), titles("sort=title&dir=desc"))

        // Path: "alpha" (titled Zulu) leads, "zulu" (titled Alpha) trails — proving the path column,
        // not the title, drove the order.
        assertEquals(listOf("Zulu", "Welcome", "Mike", "Alpha"), titles("sort=path"))
        assertEquals(listOf("Alpha", "Mike", "Welcome", "Zulu"), titles("sort=path&dir=desc"))

        // Locale, with the title breaking ties inside a locale (Mike and the home page are both "en").
        assertEquals(listOf("Alpha", "Mike", "Welcome", "Zulu"), titles("sort=locale"))
        assertEquals(listOf("Zulu", "Mike", "Welcome", "Alpha"), titles("sort=locale&dir=desc"))

        // Tags: ordered by each page's first tag; the untagged pages sort last either way.
        assertEquals(listOf("Zulu", "Alpha"), titles("sort=tags").take(2))
        assertEquals(listOf("Alpha", "Zulu"), titles("sort=tags&dir=desc").take(2))

        // Updated: touching a page floats it to the top of the newest-first order.
        admin.retitle(csrf, mikeId, "Mike edited")
        assertEquals("Mike edited", titles("sort=updated&dir=desc").first())
        assertEquals("Mike edited", titles("sort=updated").last())
    }

    @Test
    fun `unusable query parameters fall back to the defaults`() = testApplication {
        environment { config = config("admin-page-list-bad-params") }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val csrf = admin.loginAsAdmin()
        admin.createPage(csrf, "b", "Bravo")
        admin.createPage(csrf, "a", "Alpha")

        val html = admin.get("/a/pages?sort=whatever&dir=sideways&page=zero&size=7").bodyAsText()
        assertEquals(listOf("Alpha", "Bravo", "Welcome"), titlesOf(html), "title ascending, first page, all rows")
        assertTrue(html.contains("1–3 of 3"))
    }
}
