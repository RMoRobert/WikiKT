package com.wikikt

import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
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

class PublishedTest {
    @Test
    fun `unpublished pages are hidden from non-editors but visible to editors`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-published-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val csrf = admin.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        val create = admin.post("/u/v1/pages") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", csrf)
            setBody("""{"locale":"en","path":"secret-draft","title":"Secret","content":"hidden","published":false}""")
        }
        assertEquals(HttpStatusCode.Created, create.status)

        // Anonymous reader cannot see the draft...
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/u/v1/pages/by-path?path=secret-draft").status,
        )
        // ...but the editor who can edit it can.
        assertEquals(
            HttpStatusCode.OK,
            admin.get("/u/v1/pages/by-path?path=secret-draft").status,
        )
    }
}
