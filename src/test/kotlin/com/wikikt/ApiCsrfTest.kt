package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ApiCsrfTest {
    @Test
    fun `cookie-authenticated api mutation requires csrf header`() = testApplication {
        testEnvironment()
        application { configure() }
        val client = createClient { install(HttpCookies) }

        val login = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val token = login.headers["X-CSRF-Token"]
        assertNotNull(token, "login should return a CSRF token header")

        // Same session cookie, but no CSRF header -> rejected.
        val withoutHeader = client.post("/u/v1/groups") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"csrf-blocked"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, withoutHeader.status)

        // Same session cookie WITH the matching CSRF header -> allowed.
        val withHeader = client.post("/u/v1/groups") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", token)
            setBody("""{"name":"csrf-allowed"}""")
        }
        assertEquals(HttpStatusCode.Created, withHeader.status)
    }
}

private fun io.ktor.server.testing.ApplicationTestBuilder.testEnvironment() {
    environment {
        config = MapApplicationConfig(
            "wikikt.defaultLocale" to "en",
            "wikikt.defaultAdmin.username" to "admin",
            "wikikt.defaultAdmin.password" to "test",
            "wikikt.database.type" to "h2",
            "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-csrf-test;DB_CLOSE_DELAY=-1",
            "wikikt.database.h2.username" to "sa",
            "wikikt.database.h2.password" to "",
        )
    }
}
