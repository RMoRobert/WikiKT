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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProfileSettingsTest {
    private fun ApplicationTestBuilder.configureApp(dbName: String) {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///$dbName;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }
    }

    @Test
    fun `profile fields are saved and shown, and group membership is listed`() = testApplication {
        configureApp("wikikt-profile-test")
        val client = createClient { install(HttpCookies) }

        // JSON login sets the session cookie and returns the CSRF token for later form posts.
        val login = client.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val csrf = assertNotNull(login.headers["X-CSRF-Token"])

        val save = client.post("/p/settings") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("displayName", "Robert")
                        append("jobTitle", "Docs Maintainer")
                        append("location", "Chicago, IL")
                        append("timezone", "America/Chicago")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, save.status)
        assertTrue(save.bodyAsText().contains("Settings saved"))

        // A fresh GET reflects the persisted values and the admin's group membership.
        val page = client.get("/p/settings").bodyAsText()
        assertTrue("value=\"Robert\"" in page, "display name should be persisted")
        assertTrue("value=\"Docs Maintainer\"" in page, "job title should be persisted")
        assertTrue("value=\"Chicago, IL\"" in page, "location should be persisted")
        assertTrue("Admin" in page, "the admin's group membership should be listed")
    }

    @Test
    fun `blank profile fields clear the stored values`() = testApplication {
        configureApp("wikikt-profile-clear-test")
        val client = createClient { install(HttpCookies) }

        val csrf = assertNotNull(
            client.post("/u/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"test"}""")
            }.headers["X-CSRF-Token"],
        )

        suspend fun save(displayName: String) = client.post("/p/settings") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", csrf)
                        append("displayName", displayName)
                        append("jobTitle", "")
                        append("location", "")
                        append("timezone", "")
                    },
                ),
            )
        }

        save("Robert")
        assertTrue("value=\"Robert\"" in client.get("/p/settings").bodyAsText())

        // Submitting the field blank clears it (the input falls back to the username placeholder).
        save("")
        val cleared = client.get("/p/settings").bodyAsText()
        assertTrue("value=\"Robert\"" !in cleared, "a blank submission should clear the display name")
    }

    @Test
    fun `a display name already used by another user is rejected in self-service`() = testApplication {
        configureApp("wikikt-dupe-display-test")

        // Admin claims the display name "Shared".
        val admin = createClient { install(HttpCookies) }
        val adminCsrf = assertNotNull(
            admin.post("/u/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"test"}""")
            }.headers["X-CSRF-Token"],
        )
        fun profile(csrf: String, name: String) = Parameters.build {
            append("_csrf", csrf); append("displayName", name); append("jobTitle", ""); append("location", ""); append("timezone", "")
        }
        admin.post("/p/settings") { setBody(FormDataContent(profile(adminCsrf, "Shared"))) }

        // Admin creates a second user, bob.
        admin.post("/a/users") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", adminCsrf); append("username", "bob"); append("password", "changeme123"); append("email", "")
                    },
                ),
            )
        }

        // Bob signs in and tries to take "Shared" (also case-insensitively) — both rejected, profile unchanged.
        val bob = createClient { install(HttpCookies) }
        val bobCsrf = assertNotNull(
            bob.post("/u/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"bob","password":"changeme123"}""")
            }.headers["X-CSRF-Token"],
        )
        assertTrue(
            bob.post("/p/settings") { setBody(FormDataContent(profile(bobCsrf, "Shared"))) }.bodyAsText().contains("already taken"),
            "an exact duplicate is rejected",
        )
        assertTrue(
            bob.post("/p/settings") { setBody(FormDataContent(profile(bobCsrf, "shared"))) }.bodyAsText().contains("already taken"),
            "a case-insensitive duplicate is rejected",
        )
        assertTrue("value=\"Shared\"" !in bob.get("/p/settings").bodyAsText(), "the rejected name was not saved")

        // A distinct name saves fine.
        assertTrue(
            bob.post("/p/settings") { setBody(FormDataContent(profile(bobCsrf, "Bobby"))) }.bodyAsText().contains("Settings saved"),
            "a unique display name is accepted",
        )
        assertTrue("value=\"Bobby\"" in bob.get("/p/settings").bodyAsText(), "the unique name persisted")

        // A display name may not impersonate another user's username (case-insensitively) either.
        assertTrue(
            bob.post("/p/settings") { setBody(FormDataContent(profile(bobCsrf, "Admin"))) }.bodyAsText().contains("already taken"),
            "a display name matching another user's username is rejected",
        )
        assertTrue("value=\"Bobby\"" in bob.get("/p/settings").bodyAsText(), "the rejected impersonation left the prior name intact")

        // ...but a user may use their OWN username as their display name (that's the default anyway).
        assertTrue(
            bob.post("/p/settings") { setBody(FormDataContent(profile(bobCsrf, "bob"))) }.bodyAsText().contains("Settings saved"),
            "a user may set their display name to their own username",
        )

        // Admin override: an administrator may assign bob the same display name as themselves — the
        // uniqueness rule is self-service only and does not apply to the admin user editor.
        val bobId = Regex("""<td>bob</td>[\s\S]*?/a/users/(\d+)/edit""").find(admin.get("/a/users").bodyAsText())!!.groupValues[1]
        admin.post("/a/users/$bobId") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", adminCsrf); append("username", "bob"); append("displayName", "Shared"); append("email", ""); append("password", "")
                    },
                ),
            )
        }
        assertTrue(
            "value=\"Shared\"" in admin.get("/a/users/$bobId/edit").bodyAsText(),
            "an admin can assign a display name already in use",
        )
    }
}
