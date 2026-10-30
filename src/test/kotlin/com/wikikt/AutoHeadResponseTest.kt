package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AutoHeadResponseTest {
    private fun config(name: String, vararg extra: Pair<String, String>) = MapApplicationConfig(
        "wikikt.defaultLocale" to "en",
        "wikikt.defaultAdmin.username" to "admin",
        "wikikt.defaultAdmin.password" to "test",
        "wikikt.database.type" to "h2",
        "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-autohead-$name;DB_CLOSE_DELAY=-1",
        "wikikt.database.h2.username" to "sa",
        "wikikt.database.h2.password" to "",
        *extra,
    )

    @Test
    fun `HEAD answers with the status and type of the GET and no body`() = testApplication {
        environment { config = config("routes") }
        application { configure() }
        val client = createClient { followRedirects = false }

        assertEquals(HttpStatusCode.OK, client.head("/en/home").status, "an existing page is found")

        // /logout is POST-only, so landing on the POST handler would show up as a status mismatch.
        val paths = listOf(
            "/", "/en/home", "/login", "/healthz", "/favicon.svg", "/static/site.css",
            "/en/no-such-page", "/a", "/a/backup/export", "/logout", "/en/%zz",
        )
        for (path in paths) {
            val get = client.get(path)
            val head = client.head(path)
            assertEquals(get.status, head.status, "status of $path")
            assertEquals(get.headers[HttpHeaders.ContentType], head.headers[HttpHeaders.ContentType], "type of $path")
            assertEquals("", head.bodyAsText(), "body of $path")
        }

        assertNotEquals(HttpStatusCode.OK, client.head("/a/backup/export").status, "HEAD gets past no permission check")
    }

    @Test
    fun `HEAD skips compression and reports the page's real length`() = testApplication {
        environment { config = config("gzip") }
        application { configure() }

        // Compressed, the length is unknown and the engine would send Content-Length: 0 -- an empty page.
        val gzipped = client.get("/en/home") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals("gzip", gzipped.headers[HttpHeaders.ContentEncoding], "the GET does compress")

        val head = client.head("/en/home") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertNull(head.headers[HttpHeaders.ContentEncoding])
        assertEquals(client.get("/en/home").bodyAsBytes().size.toString(), head.headers[HttpHeaders.ContentLength])
        assertEquals("", head.bodyAsText())
    }

    @Test
    fun `HEAD works behind a trusted proxy`() = testApplication {
        // XForwardedHeaders rewrites the same request origin that AutoHeadResponse turns into a GET.
        environment { config = config("proxy", "wikikt.server.trustProxy" to "true") }
        application { configure() }

        val head = client.head("/en/home") {
            header("X-Forwarded-Proto", "https")
            header("X-Forwarded-For", "203.0.113.7")
        }
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals("", head.bodyAsText())
    }

    @Test
    fun `HEAD on an asset and a backup export sends headers only`() = testApplication {
        val storage = Files.createTempDirectory("wikikt-autohead-test")
        environment { config = config("asset", "wikikt.assets.storageDir" to storage.toString()) }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)
        client.post("/f") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("_csrf", csrf)
                        append("folder", "images")
                        append("locale", "en")
                        append(
                            "file", png,
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"logo.png\"")
                                append(HttpHeaders.ContentType, "image/png")
                            },
                        )
                    },
                )
            )
        }

        val get = client.get("/images/logo.png")
        val head = client.head("/images/logo.png")
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals(get.headers[HttpHeaders.ETag], head.headers[HttpHeaders.ETag])
        assertEquals(get.headers[HttpHeaders.ContentDisposition], head.headers[HttpHeaders.ContentDisposition])
        assertEquals(get.bodyAsBytes().size.toString(), head.headers[HttpHeaders.ContentLength])
        assertEquals("", head.bodyAsText())

        val etag = head.headers[HttpHeaders.ETag]!!
        val revalidated = client.head("/images/logo.png") { header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, revalidated.status)

        val export = client.head("/a/backup/export")
        assertEquals(HttpStatusCode.OK, export.status)
        assertEquals(ContentType.Application.Zip.toString(), export.headers[HttpHeaders.ContentType])
        assertEquals("", export.bodyAsText())

        storage.toFile().deleteRecursively()
    }
}
