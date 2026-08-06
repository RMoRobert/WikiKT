package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
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
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Move/rename on the asset detail page: a metadata-only identity change (bytes are stored by id).
 * The contract under test: the new URL serves, the old one stops serving, references are NOT
 * rewritten — the broken-references report names them instead — and a conflicting or invalid
 * target is refused without touching the asset.
 */
class AssetRenameTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)

    @Test
    fun `rename moves the URL, keeps history, and reports stale references`() = testApplication {
        val storage = Files.createTempDirectory("wikikt-rename-test")
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-rename-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
                "wikikt.assets.storageDir" to storage.toString(),
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()

        suspend fun upload(folder: String, filename: String) = client.post("/f") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("_csrf", csrf)
                        append("folder", folder)
                        append("locale", "en")
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
        // The manager embeds the asset list as JSON (id, locale, path, ... in declaration order).
        suspend fun assetId(path: String): String {
            val html = client.get("/f").bodyAsText()
            return Regex("\"id\":\"(\\d+)\",\"locale\":\"en\",\"path\":\"${Regex.escape(path)}\"")
                .find(html)?.groupValues?.get(1) ?: error("asset $path not found in /f listing")
        }
        suspend fun rename(id: String, locale: String, path: String) = client.post("/f/$id/rename") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("locale", locale); append("path", path) }))
        }

        upload("images", "logo.png")
        upload("misc", "other.png")
        val logoId = assetId("images/logo.png")

        // A page references the asset at its original path.
        assertEquals(
            HttpStatusCode.Created,
            client.post("/u/v1/pages") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody("""{"locale":"en","path":"uses-logo","title":"Uses","content":"![x](/en/images/logo.png)"}""")
            }.status,
        )

        // The detail page shows the move form and, because the page references it, the stale-reference warning.
        client.get("/f/$logoId").bodyAsText().let { html ->
            assertTrue(html.contains("/f/$logoId/rename"), "move form present")
            assertTrue(html.contains("This asset is in use in"), "usage warning shown")
        }

        // Move it. The redirect goes back to the same detail page (the id never changes).
        assertEquals(HttpStatusCode.Found, rename(logoId, "en", "brand/logo.png").status)
        assertTrue(client.get("/f/$logoId").bodyAsText().contains("brand/logo.png"), "detail shows the new path")

        // New URL serves the bytes; the old URL no longer resolves.
        assertEquals(HttpStatusCode.OK, client.get("/en/brand/logo.png").status, "new URL serves")
        assertEquals(HttpStatusCode.NotFound, client.get("/en/images/logo.png").status, "old URL is gone")

        // The page's reference was deliberately NOT rewritten: the old path is now a broken reference,
        // attributed to the page, and the asset itself (nothing references the new path) is unused.
        client.get("/f/broken").bodyAsText().let { html ->
            assertTrue(html.contains("<code>images/logo.png</code>"), "old path listed as broken")
            assertTrue(html.contains("uses-logo"), "referencing page named")
        }
        assertTrue(
            client.get("/f/unused").bodyAsText().contains("<td>brand/logo.png</td>"),
            "moved asset with only stale references is unused",
        )

        // A target another asset occupies is refused with a message; the asset keeps its path.
        rename(assetId("misc/other.png"), "en", "brand/logo.png").bodyAsText().let { html ->
            assertTrue(html.contains("already exists"), "conflict reported")
        }
        assertEquals(HttpStatusCode.OK, client.get("/en/misc/other.png").status, "conflicting move changed nothing")

        // Invalid targets are refused by the same naming rules as uploads.
        assertTrue(rename(logoId, "en", "bad path!.png").bodyAsText().contains("unsafe characters"), "invalid path refused")
        assertTrue(rename(logoId, "not-a-locale", "brand/logo.png").bodyAsText().contains("valid locale"), "invalid locale refused")

        storage.toFile().deleteRecursively()
    }
}
