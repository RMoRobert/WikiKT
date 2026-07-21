package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertTrue

class AdminPageTitleTest {
    @Test
    fun `admin and assets page titles carry the configured site name`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-admin-title-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val csrf = admin.loginAsAdmin()

        // Give the site a distinctive name so we can prove it lands in the <title>.
        val siteName = "Acme Knowledge Base"
        admin.post("/a/settings") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("siteName", siteName)
                    },
                ),
            )
        }

        suspend fun titleOf(path: String): String {
            val html = admin.get(path).bodyAsText()
            return Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        }

        // Admin section pages: "Admin | <Section> | <Site>".
        val rendering = titleOf("/a/settings/rendering")
        assertTrue(rendering.startsWith("Admin |"), "admin title leads with the section: $rendering")
        assertTrue(rendering.contains("Rendering"), "admin title names the page: $rendering")
        assertTrue(rendering.endsWith(siteName), "admin title ends with the site name: $rendering")

        // The assets area also carries the site name.
        val assets = titleOf("/f")
        assertTrue(assets.endsWith(siteName), "assets title ends with the site name: $assets")

        // The admin dashboard root keeps the site name too.
        assertTrue(titleOf("/a").endsWith(siteName), "admin dashboard title ends with the site name")
    }
}
