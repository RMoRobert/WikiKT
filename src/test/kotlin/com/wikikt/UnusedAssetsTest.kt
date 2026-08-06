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
 * `/f/unused` (linked from the asset manager's Tools section) lists assets no page or fragment links
 * and that aren't the site logo/favicon. Exercised end-to-end because the two exclusion paths run
 * through different machinery — the page/fragment reference scan, and settings → resolved AssetRef.
 */
class UnusedAssetsTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)

    @Test
    fun `lists only assets that no page links and that aren't the logo or favicon`() = testApplication {
        val storage = Files.createTempDirectory("wikikt-unused-test")
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-unused-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
                "wikikt.assets.storageDir" to storage.toString(),
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

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

        upload("images", "logo.png")
        upload("images", "inpage.png")
        upload("misc", "orphan.png")

        // Match the table cell, not a bare path: once the logo is set the site header renders the very
        // same URL in an <img> on every page, so a plain substring check would always "find" it.
        fun row(path: String) = "<td>$path</td>"

        // Nothing references anything yet, so all three are unused — including across different folders.
        client.get("/f/unused").bodyAsText().let { html ->
            assertTrue(html.contains(row("images/logo.png")), "logo listed while unreferenced")
            assertTrue(html.contains(row("images/inpage.png")), "inpage listed while unreferenced")
            assertTrue(html.contains(row("misc/orphan.png")), "orphan listed (a different folder is still scanned)")
        }

        // Reference one asset from a page, and select another as the site logo.
        assertEquals(
            HttpStatusCode.Created,
            client.post("/u/v1/pages") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody("""{"locale":"en","path":"uses-image","title":"Uses","content":"![x](/en/images/inpage.png)"}""")
            }.status,
        )
        client.post("/a/settings/appearance") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("siteLogoUrl", "/en/images/logo.png") }))
        }

        // Only the orphan is left: one excluded by the reference scan, one by the branding check.
        client.get("/f/unused").bodyAsText().let { html ->
            assertTrue(html.contains(row("misc/orphan.png")), "orphan still unused")
            assertFalse(html.contains(row("images/inpage.png")), "page-referenced asset excluded")
            assertFalse(html.contains(row("images/logo.png")), "site logo excluded")
        }

        storage.toFile().deleteRecursively()
    }

    /**
     * The non-obvious reference sources. Each of these is a first-class WikiKT feature that renders an
     * asset, but lives outside `pages.content` — so each was invisible to the original scan, and a
     * regression here silently re-arms "delete an asset that's actually in use".
     */
    @Test
    fun `infobox, scheduled draft, footer override and nav targets all count as used`() = testApplication {
        val storage = Files.createTempDirectory("wikikt-unused-sources-test")
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-unused-src-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
                "wikikt.assets.storageDir" to storage.toString(),
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        suspend fun upload(filename: String) = client.post("/f") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("_csrf", csrf)
                        append("folder", "shots")
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
        fun row(path: String) = "<td>$path</td>"

        listOf("infobox.png", "staged.png", "footer.png", "nav.png", "control.png").forEach { upload(it) }

        client.get("/f/unused").bodyAsText().let { html ->
            listOf("infobox", "staged", "footer", "nav", "control").forEach {
                assertTrue(html.contains(row("shots/$it.png")), "shots/$it.png unused before anything references it")
            }
        }

        // 1. Infobox values are Markdown-rendered, so an image there is a real reference.
        assertEquals(
            HttpStatusCode.Created,
            client.post("/u/v1/pages") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(
                    """{"locale":"en","path":"has-infobox","title":"Infobox","content":"no image here",
                       "infobox":"{\"photo\":\"![p](/en/shots/infobox.png)\"}"}""".trimIndent().replace("\n", ""),
                )
            }.status,
        )

        // 2. A scheduled draft: the reference exists only in not-yet-published content.
        assertEquals(
            HttpStatusCode.Created,
            client.post("/u/v1/pages") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody("""{"locale":"en","path":"will-change","title":"Later","content":"nothing yet"}""")
            }.status,
        )
        client.post("/e/en/will-change") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("title", "Later")
                        append("content", "![s](/en/shots/staged.png)")
                        append("applyMode", "staged")
                    },
                ),
            )
        }

        // Guard the guard: if applyMode were ignored and this saved live, the reference would sit in
        // page content and the staged scan would never actually be exercised.
        assertTrue(
            client.get("/u/v1/pages/by-path?path=will-change").bodyAsText().contains("nothing yet"),
            "the image went to the staged version, not the live page",
        )

        // 3. Footer override — free Markdown rendered into every page's footer.
        client.post("/a/settings") {
            setBody(
                FormDataContent(
                    Parameters.build { append("_csrf", csrf); append("siteFooterOverride", "![f](/en/shots/footer.png)") },
                ),
            )
        }

        // 4. A navigation entry pointing straight at an asset ("Label|target" per line).
        client.post("/a/navigation") {
            setBody(
                FormDataContent(
                    // A fresh site already has a default menu at the root scope, and the route refuses a
                    // duplicate — so this adds one under its own scope instead.
                    Parameters.build { append("_csrf", csrf); append("scope", "docs"); append("definition", "Download|/en/shots/nav.png") },
                ),
            )
        }

        client.get("/f/unused").bodyAsText().let { html ->
            assertFalse(html.contains(row("shots/infobox.png")), "infobox value counts as a reference")
            assertFalse(html.contains(row("shots/staged.png")), "scheduled draft counts as a reference")
            assertFalse(html.contains(row("shots/footer.png")), "footer override counts as a reference")
            assertFalse(html.contains(row("shots/nav.png")), "nav target counts as a reference")
            // The control proves the list itself still works — otherwise all four assertions above
            // would also pass on an empty/broken page.
            assertTrue(html.contains(row("shots/control.png")), "untouched asset is still reported unused")
        }

        storage.toFile().deleteRecursively()
    }

    /**
     * References that only serve because of resolution machinery — the locale fallback, reference-style
     * definitions, an image nested in a link — must keep their assets off the unused list. Each of these
     * renders a real image or download for a reader, and each was invisible to an earlier version of the
     * scan, which is precisely how a working asset gets deleted. `/f/unused` and `/f/broken` must also
     * agree: nothing referenced here may appear in either report.
     */
    @Test
    fun `locale fallback, reference-style and nested references keep assets off the unused list`() = testApplication {
        val storage = Files.createTempDirectory("wikikt-unused-resolution-test")
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-unused-res-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
                "wikikt.assets.storageDir" to storage.toString(),
                "wikikt.assets.localeFallback" to "true",
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        suspend fun upload(filename: String) = client.post("/f") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("_csrf", csrf)
                        append("folder", "pics")
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
        fun row(path: String) = "<td>$path</td>"

        listOf("fallback.png", "thumb.png", "full.png", "refstyle.png", "encoded.png", "control.png").forEach { upload(it) }

        // One page carrying all four reference shapes:
        //  - /de/pics/fallback.png — an explicit non-default locale; only the en asset exists, so the
        //    reader is served by the locale fallback (and /f/broken agrees nothing is broken).
        //  - a clickable thumbnail — the linked full-size image is a reference too.
        //  - a reference-style image, whose URL lives in a `[ref]: …` definition line.
        //  - a percent-encoded URL (`%6F` = o) — the router decodes it, so it serves.
        assertEquals(
            HttpStatusCode.Created,
            client.post("/u/v1/pages") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(
                    """{"locale":"en","path":"resolution-refs","title":"Refs","content":
                       "![f](/de/pics/fallback.png) [![t](/en/pics/thumb.png)](/en/pics/full.png) ![e](/en/pics/enc%6Fded.png) ![r][ref]\n\n[ref]: /en/pics/refstyle.png"}"""
                        .trimIndent().replace("\n", ""),
                )
            }.status,
        )

        client.get("/f/unused").bodyAsText().let { html ->
            assertFalse(html.contains(row("pics/fallback.png")), "asset served via locale fallback is used")
            assertFalse(html.contains(row("pics/thumb.png")), "thumbnail image is used")
            assertFalse(html.contains(row("pics/full.png")), "link target of a nested image is used")
            assertFalse(html.contains(row("pics/refstyle.png")), "reference-style image is used")
            assertFalse(html.contains(row("pics/encoded.png")), "percent-encoded reference is used")
            assertTrue(html.contains(row("pics/control.png")), "untouched asset is still reported unused")
        }

        // The mirror report must agree: everything above serves, so nothing here is broken.
        client.get("/f/broken").bodyAsText().let { html ->
            assertFalse(html.contains("pics/fallback.png"), "fallback-served reference is not broken")
            assertFalse(html.contains("pics/refstyle.png"), "reference-style reference is not broken")
            assertFalse(html.contains("pics/encoded.png"), "percent-encoded reference is not broken")
        }

        storage.toFile().deleteRecursively()
    }
}
