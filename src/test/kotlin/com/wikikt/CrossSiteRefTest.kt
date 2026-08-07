package com.wikikt

import com.wikikt.model.CreateGroupRequest
import com.wikikt.model.CreateUserRequest
import com.wikikt.model.RuleEffect
import com.wikikt.model.RuleMatchType
import com.wikikt.service.AccessResolver
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
 * Cross-site references: absolute URLs naming another site of the SAME instance by hostname are
 * recognized as internal — existence-checked by the save-time banner and /f/broken, counted as uses
 * by /f/unused and the asset detail's "Used by", and listed in the editor's "Linked from," while
 * targets the user has no read access to show only as "not checked" because they aren't probed.
 */
class CrossSiteRefTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)

    @Test
    fun `cross-site refs are verified in the banner, counted as uses, and listed as backlinks`() = testApplication {
        val storage = Files.createTempDirectory("wikikt-crosssite-test")
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-crosssite-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
                "wikikt.assets.storageDir" to storage.toString(),
            )
        }
        var appCtx: AppContext? = null
        application { configure(); appCtx = appContext }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.loginAsAdmin()
        val csrfEncoded = java.net.URLEncoder.encode(csrf, "UTF-8")

        // A second, hostname-scoped site with one page and one uploaded asset. Requests reach it by
        // Host header; the session cookie rides along (the jar keys on the request URL, not the header).
        client.post("/a/sites") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Docs&hostname=docs.test&_csrf=$csrfEncoded")
        }.let { assertEquals(HttpStatusCode.Found, it.status, "site create") }
        client.post("/u/v1/pages") {
            header("Host", "docs.test")
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"locale":"en","path":"docs/guide","title":"Guide","content":"hello","published":true}""")
        }.let { assertEquals(HttpStatusCode.Created, it.status, "page create on site B") }
        suspend fun uploadOnB(filename: String) = client.post("/f") {
            header("Host", "docs.test")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("_csrf", csrf)
                        append("folder", "img")
                        append("locale", "en")
                        append(
                            "file", png,
                            Headers.build {
                                append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                                append(HttpHeaders.ContentType, "image/png")
                            },
                        )
                    },
                ),
            )
        }
        uploadOnB("shot.png")
        assertEquals(HttpStatusCode.OK, client.get("/img/shot.png") { header("Host", "docs.test") }.status, "asset serves on B")

        // A page on the main site referencing site B every which way: an existing page, a missing
        // page, a missing file, an existing file, and an unrelated external host (ignored).
        val content = """
            [good](//docs.test/en/docs/guide)
            [bad](//docs.test/en/docs/nope)
            [tag route](//docs.test/t/howto)
            ![missing](https://docs.test/img/x.png)
            ![present](//docs.test/img/shot.png)
            ![external](https://unknown.example/foo.png)
        """.trimIndent()
        val saved = client.post("/e/docs/linker") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf); append("title", "LinkerPage"); append("content", content)
                        append("contentFormat", "MARKDOWN"); append("locale", "en"); append("path", "docs/linker")
                        append("published", "on")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Found, saved.status)
        val location = saved.headers["Location"]!!
        assertTrue(location.endsWith("/en/docs/linker?refwarn"), "cross-site misses flag the redirect: $location")

        // Banner: the missing page (as a cross-host create link) and missing file are warned; the
        // existing page/file and the unknown external host are not; nothing is unverifiable (admin).
        val body = client.get(location).bodyAsText()
        assertTrue(body.contains("wk-refwarn-pages"), "missing cross-site page warned")
        assertTrue(body.contains("href=\"//docs.test/e/en/docs/nope\""), "create link crosses to site B")
        assertTrue(body.contains("wk-refwarn-assets"), "missing cross-site file warned")
        assertTrue(body.contains("<code>//docs.test/img/x.png</code>"), "missing file shown with its host")
        assertFalse(body.contains("href=\"//docs.test/e/en/docs/guide\""), "existing cross-site page does not warn")
        assertFalse(body.contains("href=\"//docs.test/e/en/t/howto\""), "app-route-shaped cross links stay silent")
        assertFalse(body.contains("<code>//docs.test/img/shot.png</code>"), "existing cross-site file does not warn")
        assertFalse(body.contains("unknown.example/foo.png</code>"), "unknown hosts stay external")
        assertFalse(body.contains("wk-refwarn-unverified"), "admin can read everything")

        // /f/broken on the main site: the missing cross-site file appears in its own section.
        val broken = client.get("/f/broken").bodyAsText()
        assertTrue(broken.contains("<code>//docs.test/img/x.png</code>"), "cross-site missing file on /f/broken")
        assertFalse(broken.contains("<code>//docs.test/img/shot.png</code>"), "existing file not reported")

        // /f/unused on site B: shot.png is used (from the main site), a fresh upload is not.
        uploadOnB("unused.png")
        val unused = client.get("/f/unused") { header("Host", "docs.test") }.bodyAsText()
        assertFalse(unused.contains("img/shot.png"), "cross-site-referenced asset is not unused")
        assertTrue(unused.contains("img/unused.png"), "unreferenced asset still listed")

        // Asset detail on B: the "Used by" list names the main site's page, site-labeled and linkless
        // (the main site is the catch-all with no hostname of its own to link through).
        val siteB = appCtx!!.sites.byHostname("docs.test")!!.id
        val shotId = appCtx!!.assets.list(siteB).first { it.path == "img/shot.png" }.id
        val detail = client.get("/f/$shotId") { header("Host", "docs.test") }.bodyAsText()
        assertTrue(detail.contains("page on Main site"), "usage row names the source site")

        // Backlinks: site B's page lists the main-site page that links to it, site-labeled.
        val editor = client.get("/e/en/docs/guide") { header("Host", "docs.test") }.bodyAsText()
        assertTrue(editor.contains("LinkerPage"), "cross-site backlink listed")
        assertTrue(editor.contains("Main site: en/docs/linker"), "backlink row is site-labeled")
    }

    @Test
    fun `cross-site targets the editor cannot read are unverifiable, never probed`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-crosssite-unverif-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        var appCtx: AppContext? = null
        application { configure(); appCtx = appContext }

        val admin = createClient { install(HttpCookies); followRedirects = false }
        val adminCsrf = admin.loginAsAdmin()
        admin.post("/a/sites") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Docs&hostname=docs.test&_csrf=${java.net.URLEncoder.encode(adminCsrf, "UTF-8")}")
        }.let { assertEquals(HttpStatusCode.Found, it.status) }
        admin.post("/u/v1/pages") {
            header("Host", "docs.test")
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", adminCsrf)
            setBody("""{"locale":"en","path":"docs/guide","title":"Guide","content":"hello","published":true}""")
        }.let { assertEquals(HttpStatusCode.Created, it.status) }

        // A writer who can edit the main site but has a more-specific DENY hiding site B's kb/* and
        // img/* — the seeded User group's broad read ALLOW loses to it. Their save must not learn
        // whether docs/guide (exists) differs from docs/nope (doesn't).
        val ctx = appCtx!!
        val siteB = ctx.sites.byHostname("docs.test")!!.id
        val mainSite = ctx.sites.catchAll()!!.id
        val noB = ctx.groups.create(CreateGroupRequest(name = "NoB"))
        ctx.groupPageRules.create(
            noB.id, RuleEffect.ALLOW, RuleMatchType.PREFIX, "",
            setOf(AccessResolver.Perm.READ_PAGES, AccessResolver.Perm.WRITE_PAGES), setOf(mainSite), emptySet(),
        )
        ctx.groupPageRules.create(
            noB.id, RuleEffect.DENY, RuleMatchType.PREFIX, "docs/",
            setOf(AccessResolver.Perm.READ_PAGES), setOf(siteB), emptySet(),
        )
        ctx.groupPageRules.create(
            noB.id, RuleEffect.DENY, RuleMatchType.PREFIX, "img/",
            setOf(AccessResolver.Perm.READ_ASSETS), setOf(siteB), emptySet(),
        )
        ctx.users.create(CreateUserRequest("writer", "pw12345", null, listOf(noB.id.toString())))

        val writer = createClient { install(HttpCookies); followRedirects = false }
        val writerCsrf = writer.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"writer","password":"pw12345"}""")
        }.headers["X-CSRF-Token"]!!

        val saved = writer.post("/e/docs/mine") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", writerCsrf); append("title", "Mine")
                        append(
                            "content",
                            "[a](//docs.test/en/docs/guide)\n[b](//docs.test/en/docs/nope)\n\n![c](//docs.test/img/zzz.png)",
                        )
                        append("contentFormat", "MARKDOWN"); append("locale", "en"); append("path", "docs/mine")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Found, saved.status)
        val location = saved.headers["Location"]!!
        assertTrue(location.endsWith("?refwarn"), "unverifiable refs still flag the redirect: $location")

        val body = writer.get(location).bodyAsText()
        assertTrue(body.contains("wk-refwarn-unverified"), "unverifiable section shown")
        // Existing and missing page look IDENTICAL to this editor — both merely "not checked".
        assertTrue(body.contains("<code>//docs.test/en/docs/guide</code>"), "existing-but-unreadable listed")
        assertTrue(body.contains("<code>//docs.test/en/docs/nope</code>"), "missing-and-unreadable listed")
        assertTrue(body.contains("<code>//docs.test/img/zzz.png</code>"), "unreadable file listed")
        assertFalse(body.contains("wk-refwarn-pages"), "nothing claimed missing")
        assertFalse(body.contains("wk-refwarn-assets"), "nothing claimed missing")
    }
}
