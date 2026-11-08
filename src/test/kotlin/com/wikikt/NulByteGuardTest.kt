package com.wikikt

import com.wikikt.auth.LoginThrottle
import com.wikikt.model.AssetRef
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NUL (U+0000) in request input is refused with a 400 (NulByteGuard.kt), and no exception's message
 * ever reaches a response (the StatusPages catch-all in Application.kt). These run on H2, which stores
 * NUL without complaint, so every 400 here is the guard answering; PostgreSQL would have failed the
 * query instead, and the request with it.
 */
class NulByteGuardTest {
    // Failed JSON logins below feed the JVM-wide login throttle; don't let them leak into other tests.
    @AfterTest
    fun clearThrottle() = LoginThrottle.reset()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)

    private fun ApplicationTestBuilder.configureApp(dbName: String, extra: Application.() -> Unit = {}) {
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
        application {
            configure()
            extra()
        }
    }

    @Test
    fun `NUL in the path or a query parameter is refused on every route`() = testApplication {
        configureApp("wikikt-nul-url")
        val client = createClient { followRedirects = false }
        val urls = listOf(
            "/en/%00", "/en/a%00b", "/a%00b", // wiki catch-all, with and without a locale
            "/s?q=a%00b", "/s?a%00b=1", // search: value and name
            "/t/a%00b", // tag view
            "/u/v1/search?q=a%00b", // JSON API
            "/static/a%00b.css", // even a route that never reads the database
        )
        for (url in urls) {
            val res = client.get(url)
            assertEquals(HttpStatusCode.BadRequest, res.status, url)
            assertEquals("Bad Request", res.bodyAsText(), url)
            // Installed after the security headers, so the refusal still carries them.
            assertEquals("nosniff", res.headers["X-Content-Type-Options"], url)
        }
        // An encoded percent sign that merely spells out %00 is ordinary text.
        assertEquals(HttpStatusCode.OK, client.get("/s?q=a%2500b").status)
        assertEquals(HttpStatusCode.OK, client.get("/en/home").status)
    }

    @Test
    fun `NUL in a form field or field name is refused before the route reads it`() = testApplication {
        configureApp("wikikt-nul-form")
        val client = createClient { install(HttpCookies); followRedirects = false }
        // No CSRF token: the guard answers when the route receives the form, before its own checks.
        val forms = listOf(
            Parameters.build { append("username", "a\u0000b"); append("password", "x") },
            Parameters.build { append("username", "admin"); append("password", "a\u0000b") },
            Parameters.build { append("username", "admin"); append("a\u0000b", "1") },
        )
        for (form in forms) {
            val res = client.post("/login") { setBody(FormDataContent(form)) }
            assertEquals(HttpStatusCode.BadRequest, res.status, form.names().toString())
        }
        // The same form without a NUL reaches the route, which re-renders it (CSRF token missing).
        val ok = client.post("/login") {
            setBody(FormDataContent(Parameters.build { append("username", "admin"); append("password", "x") }))
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("Your session expired"), "reached the login route")
    }

    @Test
    fun `NUL in a JSON string is refused, but JSON that only spells out the escape is not`() = testApplication {
        configureApp("wikikt-nul-json")
        val client = createClient { install(HttpCookies); followRedirects = false }
        suspend fun login(body: String) = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }.status
        // Raw strings: the backslashes below are sent to the server as written.
        assertEquals(HttpStatusCode.BadRequest, login("""{"username":"a\u0000b","password":"x"}"""), "escaped NUL")
        assertEquals(HttpStatusCode.BadRequest, login("{\"username\":\"a\u0000b\",\"password\":\"x\"}"), "raw NUL")
        assertEquals(HttpStatusCode.Unauthorized, login("""{"username":"a\\u0000b","password":"x"}"""), "text, not a NUL")

        val csrf = client.loginAsAdmin()
        suspend fun createPage(path: String, fields: String) = client.post("/u/v1/pages") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"locale":"en","path":"$path","published":true,$fields}""")
        }.status
        assertEquals(HttpStatusCode.BadRequest, createPage("nul/title", """"title":"a\u0000b","content":"x""""))
        assertEquals(HttpStatusCode.BadRequest, createPage("nul/content", """"title":"t","content":"a\u0000b""""))
        assertEquals(HttpStatusCode.BadRequest, createPage("nul/tag", """"title":"t","content":"x","tags":["a\u0000b"]"""))
        for (path in listOf("nul/title", "nul/content", "nul/tag")) {
            assertEquals(HttpStatusCode.NotFound, client.get("/en/$path").status, "$path was not created")
        }
        // Content that documents the escape saves as written.
        assertEquals(HttpStatusCode.Created, createPage("nul/spelled-out", """"title":"t","content":"use \\u0000 for NUL""""))
        assertTrue(client.get("/en/nul/spelled-out").bodyAsText().contains("use \\u0000 for NUL"))
    }

    @Test
    fun `NUL in a multipart field, part name or filename is refused`() = testApplication {
        configureApp("wikikt-nul-multipart")
        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()
        // Built by hand: the client's form builder refuses a NUL in a part header (name, filename).
        suspend fun upload(localeField: String = "locale", locale: String = "en", filename: String = "ok.png") =
            client.post("/f") {
                header("X-Wk-Ajax", "1")
                contentType(ContentType.parse("multipart/form-data; boundary=XB"))
                val body = ByteArrayOutputStream()
                fun text(s: String) = body.write(s.toByteArray())
                text("--XB\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n$csrf\r\n")
                text("--XB\r\nContent-Disposition: form-data; name=\"$localeField\"\r\n\r\n$locale\r\n")
                text("--XB\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\nContent-Type: image/png\r\n\r\n")
                body.write(png)
                text("\r\n--XB--\r\n")
                setBody(body.toByteArray())
            }.status
        assertEquals(HttpStatusCode.BadRequest, upload(locale = "e\u0000n"), "field value")
        assertEquals(HttpStatusCode.BadRequest, upload(localeField = "lo\u0000cale"), "part name")
        assertEquals(HttpStatusCode.BadRequest, upload(filename = "a\u0000b.png"), "filename")
        assertEquals(HttpStatusCode.OK, upload(), "the same upload without a NUL")
    }

    @Test
    fun `an encoded NUL in a content URL is never decoded into a lookup path`() = testApplication {
        lateinit var app: Application
        configureApp("wikikt-nul-content-url") { app = this }
        startApplication()
        val assets = app.appContext.assets
        // Kept raw, so the save-time reference check never asks the database about a NUL path.
        assertEquals(AssetRef("en", "a%00b.png"), assets.resolveLocalAssetUrl("/en/a%00b.png", "en"))
        // Every other escape still decodes as before.
        assertEquals(AssetRef("en", "café.png"), assets.resolveLocalAssetUrl("/en/caf%C3%A9.png", "en"))
    }

    @Test
    fun `jsonHasNul counts an escape only when its backslash is not itself escaped`() {
        assertTrue(jsonHasNul("\"a\u0000b\""), "raw")
        assertTrue(jsonHasNul("""{"a":"x\u0000"}"""), "escape")
        assertTrue(jsonHasNul("""{"a\u0000":1}"""), "in a key")
        assertTrue(jsonHasNul("""{"a":"\\\u0000"}"""), "an escaped backslash, then a real escape")
        assertFalse(jsonHasNul("""{"a":"\\u0000"}"""), "a backslash, then text")
        assertFalse(jsonHasNul("""{"a":"\\\\u0000"}"""), "two backslashes, then text")
        assertFalse(jsonHasNul("""{"a":"\u0001 u0000"}"""), "other escapes")
    }

    @Test
    fun `an unexpected exception gets the generic error page, never its message`() = testApplication {
        configureApp("wikikt-catch-all") {
            routing {
                get("/test/boom") { throw IllegalStateException("secret internal detail") }
                get("/test/bad-argument") { throw IllegalArgumentException("secret internal detail") }
            }
        }
        val client = createClient { followRedirects = false }
        val res = client.get("/test/boom")
        assertEquals(HttpStatusCode.InternalServerError, res.status)
        assertTrue(res.headers[HttpHeaders.ContentType]!!.startsWith("text/html"), "the chrome-wrapped error page")
        val body = res.bodyAsText()
        assertTrue(body.contains("Error 500"), "error page")
        assertFalse(body.contains("secret internal detail"), "message not echoed")
        // The specific IllegalArgumentException handler still wins over the catch-all.
        val bad = client.get("/test/bad-argument")
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("Bad request", bad.bodyAsText())
    }

    @Test
    fun `when the error page itself fails, the fallback is still generic`() = testApplication {
        configureApp("wikikt-catch-all-fallback") {
            // Stands in for the database being down: the error page's chrome and branding both read it.
            sendPipeline.intercept(ApplicationSendPipeline.Before) { message ->
                if (message is MustacheContent && message.template == "error.hbs") throw IllegalStateException("chrome detail")
            }
            routing { get("/test/boom") { throw IllegalStateException("secret internal detail") } }
        }
        val res = createClient { followRedirects = false }.get("/test/boom")
        assertEquals(HttpStatusCode.InternalServerError, res.status)
        assertEquals("Internal server error", res.bodyAsText())
    }

    @Test
    fun `Ktor's own request errors keep their status but not their message`() = testApplication {
        configureApp("wikikt-catch-all-ktor")
        val client = createClient { followRedirects = false }
        // Truncated JSON: Ktor's BadRequestException carries the parser's message, which quotes the input.
        val truncated = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username": """)
        }
        assertEquals(HttpStatusCode.BadRequest, truncated.status)
        assertEquals("Bad Request", truncated.bodyAsText())
        // A body type nothing here can decode.
        val unsupported = client.post("/u/v1/auth/login") {
            contentType(ContentType.Text.Plain)
            setBody("username=admin")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, unsupported.status)
        assertEquals("Unsupported Media Type", unsupported.bodyAsText())
    }
}
