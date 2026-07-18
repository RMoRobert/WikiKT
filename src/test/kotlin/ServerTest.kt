package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerTest {
    @Test
    fun `root redirects to the reserved home page`() = testApplication {
        testEnvironment()
        application { configure() }
        val client = createClient { followRedirects = false }
        val response = client.get("/")
        assertEquals(HttpStatusCode.Found, response.status)
        // Locale prefixes are not forced by default, so the root lands on the unprefixed home page.
        assertEquals("/home", response.headers["Location"])
    }

    @Test
    fun `sample wiki page is publicly viewable`() = testApplication {
        testEnvironment()
        application { configure() }
        val admin = createClient { install(HttpCookies) }
        admin.createSamplePage(admin.loginAsAdmin())
        val response = client.get("/$SAMPLE_PAGE_PATH")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(SAMPLE_PAGE_TITLE))
    }

    @Test
    fun `localized wiki page path works`() = testApplication {
        testEnvironment()
        application { configure() }
        val admin = createClient { install(HttpCookies) }
        admin.createSamplePage(admin.loginAsAdmin())
        val response = client.get("/en/$SAMPLE_PAGE_PATH")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `edit page requires authentication`() = testApplication {
        testEnvironment()
        application { configure() }
        val response = client.get("/e/$SAMPLE_PAGE_PATH")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}

private fun io.ktor.server.testing.ApplicationTestBuilder.testEnvironment() {
    environment {
        config = MapApplicationConfig(
            "wikikt.defaultLocale" to "en",
            "wikikt.defaultAdmin.username" to "admin",
            "wikikt.defaultAdmin.password" to "test",
            "wikikt.database.type" to "h2",
            "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-test;DB_CLOSE_DELAY=-1",
            "wikikt.database.h2.username" to "sa",
            "wikikt.database.h2.password" to "",
        )
    }
}
