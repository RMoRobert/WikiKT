package com.wikikt

import com.wikikt.auth.LoginThrottle
import com.wikikt.config.DEFAULT_MAX_REQUEST_BODY_BYTES
import com.wikikt.service.SettingsService
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Request bodies are capped (RequestBodyLimit.kt): a form or JSON body over the configured limit gets a
 * plain 413, whether its size is declared up front or only shows as it streams in, while the multipart
 * routes that stream files to disk keep the larger ceilings their handlers enforce.
 */
class RequestBodyLimitTest {
    // Failed JSON logins below feed the JVM-wide login throttle; don't let them leak into other tests.
    @AfterTest
    fun clearThrottle() = LoginThrottle.reset()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(1000)
    private val cap = DEFAULT_MAX_REQUEST_BODY_BYTES.toInt()

    private fun ApplicationTestBuilder.configureApp(dbName: String, vararg extra: Pair<String, String>) {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///$dbName;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
                *extra,
            )
        }
        application { configure() }
    }

    /** A body sent without Content-Length, as a chunked request arrives, so only the streaming cut-off can stop it. */
    private fun streamed(bytes: ByteArray, type: ContentType) = object : OutgoingContent.WriteChannelContent() {
        override val contentType = type
        override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.writeFully(bytes)
        }
    }

    /** Login bodies whose username runs to [size] bytes, so the body lands just over or under the cap. */
    private fun loginJson(size: Int) = """{"username":"${"u".repeat(size)}","password":"x"}""".toByteArray()
    private fun loginForm(size: Int) = "username=${"u".repeat(size)}&password=x".toByteArray()

    @Test
    fun `an oversized JSON body is refused with a plain 413, declared or streamed`() = testApplication {
        configureApp("wikikt-body-json")
        val client = createClient { install(HttpCookies); followRedirects = false }
        val over = loginJson(cap) // the JSON around the name pushes it past the cap
        val declared = client.post("/u/v1/auth/login") { contentType(ContentType.Application.Json); setBody(over) }
        assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)
        assertEquals("Payload Too Large", declared.bodyAsText())
        val chunked = client.post("/u/v1/auth/login") { setBody(streamed(over, ContentType.Application.Json)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        assertEquals("Payload Too Large", chunked.bodyAsText())
        // A normal login reaches the route, which turns the credentials away.
        val small = client.post("/u/v1/auth/login") { contentType(ContentType.Application.Json); setBody(loginJson(8)) }
        assertEquals(HttpStatusCode.Unauthorized, small.status)
        // So does a page just under the cap -- a real page is nowhere near it.
        val csrf = client.loginAsAdmin()
        val big = client.post("/u/v1/pages") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"locale":"en","path":"big","title":"Big","content":"${"x".repeat(cap - 64 * 1024)}","published":true}""")
        }
        assertEquals(HttpStatusCode.Created, big.status)
    }

    @Test
    fun `an oversized form body is refused with 413, a normal one reaches the route`() = testApplication {
        configureApp("wikikt-body-form")
        val client = createClient { install(HttpCookies); followRedirects = false }
        val over = loginForm(cap)
        val declared = client.post("/login") { contentType(ContentType.Application.FormUrlEncoded); setBody(over) }
        assertEquals(HttpStatusCode.PayloadTooLarge, declared.status)
        assertEquals("Payload Too Large", declared.bodyAsText())
        val chunked = client.post("/login") { setBody(streamed(over, ContentType.Application.FormUrlEncoded)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        // Under the cap the form reaches the login route, which re-renders it (no CSRF token was sent).
        val under = client.post("/login") { contentType(ContentType.Application.FormUrlEncoded); setBody(loginForm(cap - 1024)) }
        assertEquals(HttpStatusCode.OK, under.status)
        assertTrue(under.bodyAsText().contains("Your session expired"), "reached the login route")
    }

    @Test
    fun `the cap is configurable`() = testApplication {
        configureApp("wikikt-body-config", "wikikt.server.maxRequestBodyBytes" to "1024")
        val client = createClient { followRedirects = false }
        suspend fun login(size: Int) = client.post("/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(loginForm(size))
        }.status
        assertEquals(HttpStatusCode.PayloadTooLarge, login(1024))
        assertEquals(HttpStatusCode.OK, login(512))
    }

    @Test
    fun `upload, replace and restore keep their own ceilings above the cap`() = testApplication {
        val storage = Files.createTempDirectory("wikikt-body-upload")
        configureApp(
            "wikikt-body-upload",
            "wikikt.server.maxRequestBodyBytes" to "1024", // below every body sent here
            "wikikt.assets.storageDir" to storage.toString(),
            "wikikt.assets.maxUploadSizeBytes" to "1024",
            "wikikt.assets.maxFilesPerUpload" to "2",
        )
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()
        fun multipart(vararg files: Pair<String, ByteArray>, fields: Map<String, String> = emptyMap()) = MultiPartFormDataContent(
            formData {
                append("_csrf", csrf)
                fields.forEach { (name, value) -> append(name, value) }
                for ((name, bytes) in files) {
                    append(
                        "file", bytes,
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"$name\"")
                            append(HttpHeaders.ContentType, "image/png")
                        },
                    )
                }
            },
        )

        // Two files at the per-file cap: a few KB, which the 1 KB default alone would have refused.
        val uploaded = client.post("/f") { setBody(multipart("a.png" to png, "b.png" to png, fields = mapOf("locale" to "en"))) }
        assertEquals(HttpStatusCode.OK, uploaded.status)
        assertTrue(uploaded.bodyAsText().contains("Uploaded 2 file(s)."), "both files stored")

        // The manager embeds the asset list as JSON (id, locale, path, ... in declaration order).
        val id = Regex("\"id\":\"(\\d+)\",\"locale\":\"en\",\"path\":\"a.png\"").find(client.get("/f").bodyAsText())
            ?.groupValues?.get(1) ?: error("a.png not found in the /f listing")
        val replaced = client.post("/f/$id/replace") { setBody(multipart("a2.png" to png)) }
        assertEquals(HttpStatusCode.Found, replaced.status, "replace redirects back to the asset")

        // A restore body passes the cap too; the handler then turns the (bogus) archive away on its own terms.
        val restored = client.post("/a/backup/restore") { setBody(multipart("backup.zip" to ByteArray(2048))) }
        assertEquals(HttpStatusCode.OK, restored.status)

        // Over the upload ceiling (the most files any site may allow, each at the per-file cap, plus room
        // for the form), sent without Content-Length so the handler's own check can't answer first: the
        // streaming cut-off does, mid-file.
        val ceiling = SettingsService.MAX_UPLOAD_FILE_LIMIT * 1024L + MULTIPART_OVERHEAD_BYTES
        val body = ByteArrayOutputStream()
        fun text(s: String) = body.write(s.toByteArray())
        text("--XB\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n$csrf\r\n")
        text("--XB\r\nContent-Disposition: form-data; name=\"locale\"\r\n\r\nen\r\n")
        text("--XB\r\nContent-Disposition: form-data; name=\"file\"; filename=\"huge.png\"\r\nContent-Type: image/png\r\n\r\n")
        body.write(png)
        body.write(ByteArray(ceiling.toInt()))
        text("\r\n--XB--\r\n")
        val cut = client.post("/f") { setBody(streamed(body.toByteArray(), ContentType.parse("multipart/form-data; boundary=XB"))) }
        assertEquals(HttpStatusCode.PayloadTooLarge, cut.status)
        assertEquals("Payload Too Large", cut.bodyAsText())

        storage.toFile().deleteRecursively()
    }
}
