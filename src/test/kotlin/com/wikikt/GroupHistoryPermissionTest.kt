package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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

class GroupHistoryPermissionTest {
    @Test
    fun `viewing page history requires the read history verb (default-deny)`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-history-perm-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val anon = createClient { install(HttpCookies); followRedirects = false }
        val admin = createClient { install(HttpCookies) }

        // Admin creates a normal content page for the test to read.
        admin.createSamplePage(admin.loginAsAdmin())

        val historyUrl = "/h/en/$SAMPLE_PAGE_PATH"
        // WikiJS-3 model: the seeded Guest group is granted only read:pages / read:assets, NOT
        // read:history — so history is hidden by default while the page itself stays viewable.
        assertEquals(HttpStatusCode.OK, anon.get("/en/$SAMPLE_PAGE_PATH").status, "page viewable")
        assertEquals(HttpStatusCode.Forbidden, anon.get(historyUrl).status, "history hidden by default")

        // The admin (manage:system root) can view history.
        assertEquals(HttpStatusCode.OK, admin.get(historyUrl).status, "root sees history")
    }
}
