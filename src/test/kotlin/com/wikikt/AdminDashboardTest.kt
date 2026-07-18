package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminDashboardTest {
    @Test
    fun `admin dashboard shows counts, recent activity, and version`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-dashboard-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val client = createClient { install(HttpCookies); followRedirects = false }
        val csrf = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        client.post("/u/v1/pages") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"locale":"en","path":"guide/dashboard-probe","title":"Dashboard Probe","content":"hi","published":true}""")
        }

        val res = client.get("/a")
        assertEquals(HttpStatusCode.OK, res.status)
        val html = res.bodyAsText()

        // Stat tiles (Bootstrap colored count cards) + version info.
        assertTrue(html.contains("text-bg-primary"), "stat cards rendered")
        assertTrue(html.contains("1.0.0-SNAPSHOT"), "app version baked + shown")

        // Recent pages: the page just created shows up.
        assertTrue(html.contains("Recently edited pages"), "recent pages panel")
        assertTrue(html.contains("Dashboard Probe"), "the new page appears in recent pages")

        // Recent logins: the admin who just logged in shows up.
        assertTrue(html.contains("Recent logins"), "recent logins panel")
        assertTrue(html.contains("admin"), "logged-in admin appears")
    }
}
