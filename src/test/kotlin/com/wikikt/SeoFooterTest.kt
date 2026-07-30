package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeoFooterTest {
    @Test
    fun `footer and SEO meta reflect the configured settings`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-seo-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }

        // Default footer (no settings yet) — the public login page carries it.
        client.get("/login").bodyAsText().let { html ->
            assertTrue(html.contains("Powered by WikiKT"), "default footer shows the engine name")
            assertTrue(html.contains("©"), "default footer shows a copyright line")
        }

        val csrf = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!
        client.createSamplePage(csrf)

        // The General settings page renders the Footer/SEO sections; Appearance hosts the logo asset select.
        client.get("/a/settings").bodyAsText().let { html ->
            assertTrue(html.contains("<h2>Footer</h2>") && html.contains("<h2>SEO</h2>"), "new settings sections render")
            assertTrue(html.contains("""name="siteMetaRobots"""") && html.contains("noindex,nofollow"), "robots select renders")
        }
        client.get("/a/settings/appearance").bodyAsText().let { html ->
            assertTrue(html.contains("""name="siteLogoUrl"""") && html.contains("— Default (logo.svg) —"), "logo is an asset select")
        }

        // Configure footer (org + license) and SEO defaults (description + robots).
        client.post("/a/settings") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("siteName", "Acme Wiki")
                        append("siteOrgName", "Acme")
                        append("siteContentLicense", "All rights reserved")
                        append("siteFooterOverride", "")
                        append("siteDescription", "The Acme knowledge base")
                        append("siteMetaRobots", "noindex,nofollow")
                    },
                ),
            )
        }

        // The sample page has no description/robots of its own, so it inherits the site defaults.
        val pageRes = client.get("/en/$SAMPLE_PAGE_PATH")
        assertEquals(HttpStatusCode.OK, pageRes.status)
        pageRes.bodyAsText().let { html ->
            assertTrue(html.contains("""<meta name="robots" content="noindex,nofollow">"""), "site default robots applied to the page")
            assertTrue(html.contains("""<meta name="description" content="The Acme knowledge base">"""), "site default description used when the page sets none")
            assertTrue(html.contains("Acme") && html.contains("All rights reserved"), "footer built from org + license")
            assertTrue(html.contains("Powered by WikiKT"), "default footer keeps the Powered-by line")
        }

        // An org name that ends in a period supplies its own sentence break before the license.
        client.post("/a/settings") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("siteName", "Acme Wiki")
                        append("siteOrgName", "Acme, Inc.")
                        append("siteContentLicense", "All rights reserved")
                        append("siteFooterOverride", "")
                        append("siteDescription", "The Acme knowledge base")
                        append("siteMetaRobots", "noindex,nofollow")
                    },
                ),
            )
        }
        client.get("/en/$SAMPLE_PAGE_PATH").bodyAsText().let { html ->
            assertTrue(html.contains("Acme, Inc. All rights reserved"), "no period is added after an org name that has one")
            assertFalse(html.contains("Acme, Inc.. "), "the org name's period is not doubled")
        }

        // A Markdown footer override replaces the generated line; {{year}} resolves to the current year.
        client.post("/a/settings") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("siteName", "Acme Wiki")
                        append("siteOrgName", "Acme, Inc.")
                        append("siteContentLicense", "All rights reserved")
                        append("siteFooterOverride", "Custom **footer** {{year}} [text](/en/terms)")
                        append("siteDescription", "The Acme knowledge base")
                        append("siteMetaRobots", "noindex,nofollow")
                    },
                ),
            )
        }
        client.get("/en/$SAMPLE_PAGE_PATH").bodyAsText().let { html ->
            val year = java.time.Year.now().value.toString()
            assertTrue(html.contains("<strong>footer</strong>"), "override is rendered as Markdown")
            assertTrue(html.contains("""<a href="/en/terms">text</a>"""), "override keeps Markdown links")
            assertTrue(html.contains("Custom <strong>footer</strong> $year"), "{{year}} resolves to the current year")
            assertFalse(html.contains("All rights reserved"), "override replaces the generated footer")
            assertTrue(html.contains("Powered by WikiKT"), "override keeps powered-by tag")
        }
    }
}
