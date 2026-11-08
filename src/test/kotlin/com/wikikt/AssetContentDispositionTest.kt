package com.wikikt

import com.wikikt.routing.withFilename
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Asset responses name the file for save-as. Netty writes header chars as single bytes, so a non-ASCII
 * name must travel as `filename*=UTF-8''…` (RFC 6266/5987) with an ASCII-only `filename` fallback.
 * testApplication doesn't go through Netty's byte encoding, so these assert the header is pure ASCII.
 */
class AssetContentDispositionTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)

    private fun inline(name: String) = ContentDisposition.Inline.withFilename(name).toString()

    @Test
    fun `printable ASCII names keep a single filename parameter`() {
        assertEquals("inline; filename=logo.png", inline("logo.png"))
        assertEquals("inline; filename=\"my logo.png\"", inline("my logo.png"))
        assertEquals("inline; filename=\"say \\\"hi\\\".png\"", inline("say \"hi\".png"))
        // Already-encoded-looking ASCII is just a name: no filename* is added, nothing is decoded.
        assertEquals("inline; filename=utf-8''x%41.png", inline("utf-8''x%41.png"))
    }

    @Test
    fun `non-ASCII names get an ASCII fallback plus a UTF-8 filename-star`() {
        assertEquals(
            "inline; filename=\"naive \\\"q\\\".png\"; filename*=UTF-8''na%C3%AFve%20%22q%22.png",
            inline("naïve \"q\".png"),
        )
        assertEquals(
            "inline; filename=\"__ x.png\"; filename*=UTF-8''%E6%97%A5%E6%9C%AC%20x.png",
            inline("日本 x.png"),
        )
        // One '_' per code point, not per UTF-16 char (emoji) or per decomposed jamo (Hangul).
        assertEquals("inline; filename=_.png; filename*=UTF-8''%F0%9F%98%80.png", inline("😀.png"))
        assertEquals("inline; filename=_.png; filename*=UTF-8''%ED%95%9C.png", inline("한.png"))
        // Characters that are special inside an ext-value are percent-encoded, never passed raw.
        assertEquals(
            "inline; filename=e'%*_.png; filename*=UTF-8''%C3%A9%27%25%2A%E2%82%AC.png",
            inline("é'%*€.png"),
        )
        // A non-ASCII name that starts with Ktor's own "already encoded" marker is still encoded.
        assertEquals(
            "inline; filename=utf-8''_.png; filename*=UTF-8''utf-8%27%27%E6%97%A5.png",
            inline("utf-8''日.png"),
        )
        // Control chars never reach the header raw either.
        assertEquals("inline; filename=a_b.png; filename*=UTF-8''a%09b.png", inline("a\tb.png"))
        // A decomposed accent (i + U+0308) falls back to its base letter, not to `_`.
        assertEquals("inline; filename=naive.png; filename*=UTF-8''nai%CC%88ve.png", inline("nai\u0308ve.png"))
    }

    @Test
    fun `asset and revision routes serve RFC 6266 filenames`() = testApplication {
        val storage = Files.createTempDirectory("wikikt-disposition-test")
        lateinit var app: Application
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-disposition-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
                "wikikt.assets.storageDir" to storage.toString(),
            )
        }
        application { app = this; configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        suspend fun upload(filename: String, overwrite: Boolean = false) = client.post("/f") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("_csrf", csrf)
                        append("folder", "images")
                        append("locale", "en")
                        if (overwrite) append("overwrite", "1")
                        append(
                            "file", png,
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                                append(HttpHeaders.ContentType, "image/png")
                            },
                        )
                    },
                )
            )
        }

        suspend fun dispositionOf(url: String): String {
            val resp = client.get(url)
            assertEquals(HttpStatusCode.OK, resp.status, url)
            val value = assertNotNull(resp.headers[HttpHeaders.ContentDisposition], url)
            assertTrue(value.all { it in ' '..'~' }, "header must be printable ASCII: $value")
            return value
        }

        // ASCII: unchanged single parameter. Latin-1 and CJK: ASCII fallback + filename*. The URL keeps
        // the letters (spaces become hyphens); the save-as name keeps the name as uploaded.
        assertEquals(HttpStatusCode.OK, upload("logo.png").status)
        assertEquals(HttpStatusCode.OK, upload("naïve x.png").status)
        assertEquals(HttpStatusCode.OK, upload("日本 x.png").status)
        assertEquals("inline; filename=logo.png", dispositionOf("/images/logo.png"))
        assertEquals(
            "inline; filename=\"naive x.png\"; filename*=UTF-8''na%C3%AFve%20x.png",
            dispositionOf("/images/naïve-x.png"),
        )
        val cjk = "inline; filename=\"__ x.png\"; filename*=UTF-8''%E6%97%A5%E6%9C%AC%20x.png"
        assertEquals(cjk, dispositionOf("/images/日本-x.png"))
        assertEquals(cjk, dispositionOf("/images/%E6%97%A5%E6%9C%AC-x.png"))

        // PNG bytes under another extension: the URL and the save-as name both end in .png.
        assertEquals(HttpStatusCode.OK, upload("invoice.hta").status)
        assertEquals("inline; filename=invoice.hta.png", dispositionOf("/images/invoice-hta.png"))

        // Overwriting archives the old file as a revision; the History "View" route names it the same way.
        assertEquals(HttpStatusCode.OK, upload("日本 X.png", overwrite = true).status)
        assertEquals(
            "inline; filename=\"__ X.png\"; filename*=UTF-8''%E6%97%A5%E6%9C%AC%20X.png",
            dispositionOf("/images/日本-x.png"),
        )
        val ctx = app.appContext
        val asset = ctx.assets.findByLocaleAndPath(ctx.sites.catchAll()!!.id, "en", "images/日本-x.png")!!
        val rev = ctx.assets.revisions(asset.id).single()
        assertEquals(cjk, dispositionOf("/f/${asset.id}/revision/${rev.id}"))

        storage.toFile().deleteRecursively()
    }
}
