package com.wikikt

import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssetUploadTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)

    @Test
    fun `upload validates type, size, csrf and then serves at its path`() = testApplication {
        val storage = Files.createTempDirectory("wikikt-upload-test")
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-upload-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
                "wikikt.assets.storageDir" to storage.toString(),
                "wikikt.assets.maxUploadSizeBytes" to "4096",
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        suspend fun upload(csrfToken: String?, filename: String, bytes: ByteArray, mime: String) = client.post("/f") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        if (csrfToken != null) append("_csrf", csrfToken)
                        append("folder", "images")
                        append("locale", "en")
                        append(
                            "file", bytes,
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                                append(HttpHeaders.ContentType, mime)
                            },
                        )
                    },
                )
            )
        }

        // Missing CSRF → forbidden, and nothing persisted.
        assertEquals(HttpStatusCode.Forbidden, upload(null, "evil.png", png, "image/png").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/images/evil.png").status)

        // Disallowed type (HTML bytes renamed .png) → skipped, not stored.
        upload(csrf, "fake.png", "<script>alert(1)</script>".toByteArray(), "image/png")
        assertEquals(HttpStatusCode.NotFound, client.get("/images/fake.png").status)

        // Oversize (> 4096) → skipped, not stored.
        upload(csrf, "big.png", png + ByteArray(8192), "image/png")
        assertEquals(HttpStatusCode.NotFound, client.get("/images/big.png").status)

        // Happy path: a real PNG is stored and served at its path with the right headers.
        val ok = upload(csrf, "logo.png", png, "image/png")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("Uploaded 1 file(s)."))

        val served = client.get("/images/logo.png")
        assertEquals(HttpStatusCode.OK, served.status)
        assertTrue(served.headers["Content-Type"].orEmpty().startsWith("image/png"))
        assertEquals("nosniff", served.headers["X-Content-Type-Options"])

        storage.toFile().deleteRecursively()
    }
}
