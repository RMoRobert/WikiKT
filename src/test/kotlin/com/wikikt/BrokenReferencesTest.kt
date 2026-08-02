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
 * The inverse reports of "unused": content pointing at something that isn't there. `/f/broken` for
 * assets, and the "Missing fragments" table on `/a/fragments`.
 *
 * The asset scan's whole risk is false positives — telling an editor a working image is broken. So the
 * cases that must stay OUT of the report (a link to a page, a locale-fallback hit) are asserted just as
 * hard as the ones that must appear.
 */
class BrokenReferencesTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)

    @Test
    fun `lists references with no asset behind them, and leaves page links alone`() = testApplication {
        val storage = Files.createTempDirectory("wikikt-broken-test")
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-broken-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
                "wikikt.assets.storageDir" to storage.toString(),
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()

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
                                append(HttpHeaders.ContentDisposition, "filename=\"present.png\"")
                                append(HttpHeaders.ContentType, "image/png")
                            },
                        )
                    },
                )
            )
        }

        // One page carrying every case at once, so a single scan decides all of them.
        assertEquals(
            HttpStatusCode.Created,
            client.post("/u/v1/pages") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(
                    """{"locale":"en","path":"mixed-refs","title":"Mixed","content":
                       "![a](/en/images/present.png) ![b](/en/images/gone.png) [c](/en/files/manual.pdf) [d](/en/not-written-yet) `![e](/en/images/incode.png)`"}"""
                        .trimIndent().replace("\n", ""),
                )
            }.status,
        )

        // Match the code cell, not a bare path: the "referenced from" column repeats page paths, so a
        // loose substring check would blur the two columns together.
        fun row(path: String) = "<code>$path</code>"

        client.get("/f/broken").bodyAsText().let { html ->
            assertTrue(html.contains(row("images/gone.png")), "embed with no asset is broken")
            assertTrue(html.contains(row("files/manual.pdf")), "link to a dotted path can only be a file, so it's broken")
            assertFalse(html.contains(row("images/present.png")), "an asset that exists is not broken")
            // A page path can never contain a period, so an extension-less link is a page link — a
            // page not written yet is a normal wiki state, not a broken asset.
            assertFalse(html.contains(row("not-written-yet")), "extension-less link is a page link, not an asset")
            assertFalse(html.contains(row("images/incode.png")), "refs inside code spans are not references")
            // The source of each broken ref is named, so it's actionable.
            assertTrue(html.contains("mixed-refs"), "the referencing page is listed")
        }

        storage.toFile().deleteRecursively()
    }

    /**
     * The "did exist but no longer" case the report is really for: deleting an asset has to move it
     * from "used" straight to "broken", with no state in between where neither report mentions it.
     */
    @Test
    fun `deleting a referenced asset makes its references broken`() = testApplication {
        val storage = Files.createTempDirectory("wikikt-broken-delete-test")
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-broken-del-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
                "wikikt.assets.storageDir" to storage.toString(),
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()

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
                                append(HttpHeaders.ContentDisposition, "filename=\"doomed.png\"")
                                append(HttpHeaders.ContentType, "image/png")
                            },
                        )
                    },
                )
            )
        }
        assertEquals(
            HttpStatusCode.Created,
            client.post("/u/v1/pages") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody("""{"locale":"en","path":"uses-doomed","title":"Uses","content":"![x](/en/images/doomed.png)"}""")
            }.status,
        )

        fun row(path: String) = "<code>$path</code>"
        assertFalse(
            client.get("/f/broken").bodyAsText().contains(row("images/doomed.png")),
            "not broken while the asset is still there",
        )

        // Find the asset's id, then delete it through the normal route. The manager renders its list
        // client-side from an embedded JSON blob, so the id comes from there rather than from an <a>.
        val id = Regex("\"id\":\"(\\d+)\"").find(client.get("/f").bodyAsText())?.groupValues?.get(1)
            ?: error("no asset id found in the manager's embedded list")
        client.post("/f/$id/delete") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf) }))
        }

        assertTrue(
            client.get("/f/broken").bodyAsText().contains(row("images/doomed.png")),
            "the reference is reported once the asset is gone",
        )
    }

    /**
     * Directory-relative references. PageRenderService.resolveRelativeLinks rewrites these against the
     * page treated as a *directory* (WikiJS's rule), so the scan has to resolve them the same way — or
     * it both misses broken ones and, worse, calls a live asset unused. Fragments are expanded into the
     * page before that pass, so a relative URL inside one resolves against the including page too.
     */
    @Test
    fun `relative references resolve against the page, in both reports`() = testApplication {
        val storage = Files.createTempDirectory("wikikt-relative-test")
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-relative-test;DB_CLOSE_DELAY=-1",
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

        // A page at `manual/intro` treats itself as a directory, so `shot.png` is manual/intro/shot.png.
        upload("manual/intro", "shot.png")
        upload("manual", "shared.png")

        assertEquals(
            HttpStatusCode.Created,
            client.post("/u/v1/pages") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(
                    """{"locale":"en","path":"manual/intro","title":"Intro","content":
                       "![a](shot.png) ![b](../shared.png) ![c](missing.png)"}""".trimIndent().replace("\n", ""),
                )
            }.status,
        )

        fun brokenRow(path: String) = "<code>$path</code>"
        client.get("/f/broken").bodyAsText().let { html ->
            assertTrue(html.contains(brokenRow("manual/intro/missing.png")), "relative ref with no asset is broken")
            assertFalse(html.contains(brokenRow("manual/intro/shot.png")), "relative ref that resolves is fine")
            assertFalse(html.contains(brokenRow("manual/shared.png")), "'..' climbs out of the page directory")
        }

        // The same resolution has to feed /f/unused, or these two assets look deletable.
        fun unusedRow(path: String) = "<td>$path</td>"
        client.get("/f/unused").bodyAsText().let { html ->
            assertFalse(html.contains(unusedRow("manual/intro/shot.png")), "relatively-referenced asset is not unused")
            assertFalse(html.contains(unusedRow("manual/shared.png")), "'..' reference counts as use")
        }

        // A fragment's relative URL resolves against whichever page includes it — the fragment is
        // expanded into the body before the relative pass runs.
        upload("guides/install", "step1.png")
        client.post("/a/fragments") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("locale", "en")
                        append("key", "steps")
                        append("title", "Steps")
                        append("content", "![s](step1.png) ![t](step2.png)")
                    },
                ),
            )
        }
        assertEquals(
            HttpStatusCode.Created,
            client.post("/u/v1/pages") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody("""{"locale":"en","path":"guides/install","title":"Install","content":"{{fragment:steps}}"}""")
            }.status,
        )

        client.get("/f/unused").bodyAsText().let { html ->
            assertFalse(
                html.contains(unusedRow("guides/install/step1.png")),
                "an asset referenced only relatively from a fragment must not read as deletable",
            )
        }
        assertTrue(
            client.get("/f/broken").bodyAsText().contains(brokenRow("guides/install/step2.png")),
            "a fragment's relative ref with no asset is broken on the including page",
        )

        storage.toFile().deleteRecursively()
    }

    /**
     * A directory-relative reference in an infobox value or a fragment has no one page to resolve
     * against, so it can't be checked — and shouldn't be used. It's reported separately from "broken"
     * (we can't claim it resolves nowhere, only that we can't tell), with the absolute form to use
     * instead: `/folder/file.png` binds to the page's locale and falls back to the default.
     */
    @Test
    fun `relative references in infoboxes and fragments are reported as uncheckable`() = testApplication {
        val storage = Files.createTempDirectory("wikikt-unresolvable-test")
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-unresolv-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
                "wikikt.assets.storageDir" to storage.toString(),
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()

        client.post("/a/fragments") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("locale", "en")
                        append("key", "badge")
                        append("title", "Badge")
                        append("content", "![b](badge.png)")
                    },
                ),
            )
        }
        assertEquals(
            HttpStatusCode.Created,
            client.post("/u/v1/pages") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(
                    """{"locale":"en","path":"specs/widget","title":"Widget","content":"body",
                       "infobox":"{\"photo\":\"![p](photo.png)\"}"}""".trimIndent().replace("\n", ""),
                )
            }.status,
        )

        client.get("/f/broken").bodyAsText().let { html ->
            assertTrue(html.contains("Should be absolute"), "the section renders")
            assertTrue(html.contains("<code>photo.png</code>"), "infobox relative ref is flagged")
            assertTrue(html.contains("<code>badge.png</code>"), "fragment relative ref is flagged")
            // The suggested fix is the locale-relative absolute form, not a locale-pinned path.
            assertTrue(html.contains("<code>/photo.png</code>"), "suggests the absolute form")
            // And it must not be mistaken for a confirmed-broken reference: those are a separate table
            // keyed by a resolved (locale, path), which these deliberately don't have.
            assertFalse(html.contains("<code>specs/widget/photo.png</code>"), "not resolved as if it were a page body")
        }

        storage.toFile().deleteRecursively()
    }

    /**
     * The editor rejects a relative reference in an infobox value, because an infobox card is rendered
     * without the page-relative pass — the browser resolves it a segment higher than the same markup in
     * the body, so it silently points somewhere else. There is always a correct absolute rewrite, so
     * this is a hard stop rather than a warning. The guard is editor-only: the JSON API stays permissive
     * (the test above relies on that to seed legacy content), and the page body is untouched.
     */
    @Test
    fun `editor rejects a relative reference in an infobox value but not in the body`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-ib-guard-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()

        client.post("/a/infoboxes") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("name", "Spec")
                        append("slug", "spec")
                        append("description", "")
                        append("fields", "photo|Photo")
                    },
                ),
            )
        }
        val templateId = Regex("/a/infoboxes/(\\d+)/edit").find(client.get("/a/infoboxes").bodyAsText())
            ?.groupValues?.get(1) ?: error("template was not created")
        client.post("/a/infoboxes/rules") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("pattern", "specs/**")
                        append("templateId", templateId)
                    },
                ),
            )
        }
        assertEquals(
            HttpStatusCode.Created,
            client.post("/u/v1/pages") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody("""{"locale":"en","path":"specs/widget","title":"Widget","content":"body"}""")
            }.status,
        )

        suspend fun save(photo: String) = client.post("/e/en/specs/widget") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("title", "Widget")
                        append("path", "specs/widget")
                        append("locale", "en")
                        // A relative ref in the BODY is fine — it resolves against the page path.
                        append("content", "![body](diagram.png)")
                        append("infobox.spec.photo", photo)
                    },
                ),
            )
        }

        save("![p](photo.png)").let { response ->
            val html = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, "re-renders the editor rather than redirecting")
            assertTrue(html.contains("must use absolute file paths"), "explains the rule")
            assertTrue(html.contains("/photo.png"), "names the absolute form to use")
            // Nothing may be lost: the submitted body has to come back in the textarea.
            assertTrue(html.contains("![body](diagram.png)"), "the submitted body survives the rejection")
        }

        // The same value written absolutely saves, and the relative body reference is untouched.
        assertEquals(HttpStatusCode.Found, save("![p](/photo.png)").status, "absolute path saves")
        assertTrue(
            client.get("/u/v1/pages/by-path?path=specs/widget").bodyAsText().contains("![body](diagram.png)"),
            "a relative reference in the page body is still allowed",
        )
    }

    /** `{{fragment:key}}` with nothing behind it renders "[missing fragment: key]" — surface that. */
    @Test
    fun `fragments page lists references to fragments that do not exist`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-missing-frag-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()

        client.post("/a/fragments") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("locale", "en")
                        append("key", "greeting")
                        append("title", "Greeting")
                        append("content", "Hello")
                    },
                ),
            )
        }
        assertEquals(
            HttpStatusCode.Created,
            client.post("/u/v1/pages") {
                contentType(ContentType.Application.Json)
                header("X-CSRF-Token", csrf)
                setBody(
                    """{"locale":"en","path":"uses-fragments","title":"Uses","content":
                       "{{fragment:greeting}} and {{fragment:nosuchkey}}"}""".trimIndent().replace("\n", ""),
                )
            }.status,
        )

        fun row(key: String) = "<code>$key</code>"
        client.get("/a/fragments").bodyAsText().let { html ->
            assertTrue(html.contains("Missing fragments"), "the section renders when something is missing")
            assertTrue(html.contains(row("nosuchkey")), "the unresolved key is listed")
            assertFalse(html.contains(row("greeting")), "a key that resolves is not listed as missing")
            assertTrue(html.contains("uses-fragments"), "the referencing page is named")
            // The row's action prefills the new-fragment form with the key it's missing.
            assertTrue(html.contains("/a/fragments/new?key=nosuchkey"), "offers to create the missing fragment")
        }

        // And that prefill actually lands in the form's key field.
        assertTrue(
            client.get("/a/fragments/new?key=nosuchkey").bodyAsText().contains("value=\"nosuchkey\""),
            "the key is prefilled on the create form",
        )
    }
}
