package com.wikikt

import com.wikikt.auth.ResetRequestThrottle
import com.wikikt.db.EmailQueueTable
import com.wikikt.service.EmailTemplateService
import com.wikikt.service.SettingsService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards for the anonymous/public and non-admin-contributor attack surface.
 *  - H1: password-reset links must not derive their host from a spoofable Host header.
 *  - H2: the "duplicate page" (/new?from=) path must enforce read on the source and write on the target,
 *        so a path-scoped contributor can neither exfiltrate a page they can't read nor create outside scope.
 */
class PublicExposureGuardTest {

    // --- H1: reset-link host-header poisoning ---

    @Test
    fun `password-reset link uses the configured public URL, not the request Host`() = testApplication {
        ResetRequestThrottle.reset()
        lateinit var app: Application
        environment {
            config = h2Config("wikikt-h1-reset-host") + mapOf("wikikt.server.publicUrl" to "https://canonical.example")
        }
        application { app = this; configure() }

        val admin = newClient()
        val adminCsrf = admin.apiLogin("admin", "test")
        admin.post("/u/v1/users") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", adminCsrf)
            setBody("""{"username":"victim","password":"oldpassword","email":"victim@example.com"}""")
        }
        runBlocking {
            val siteId = app.appContext.sites.catchAll()!!.id
            app.appContext.settings.setBool(siteId, SettingsService.MAIL_ENABLED, true)
        }

        val anon = newClient()
        val forgotCsrf = Regex("""name="_csrf" value="([^"]+)"""").find(anon.get("/forgot-password").bodyAsText())!!.groupValues[1]
        val resp = anon.post("/forgot-password") {
            header(HttpHeaders.Host, "evil.example") // spoofed Host — must NOT end up in the emailed link
            setBody(FormDataContent(Parameters.build { append("_csrf", forgotCsrf); append("email", "victim@example.com") }))
        }
        assertEquals(HttpStatusCode.OK, resp.status)

        val emailContext = queuedResetContext(app)
        assertTrue(
            emailContext.contains("https://canonical.example/reset-password"),
            "reset link is built from the configured public URL",
        )
        assertFalse(emailContext.contains("evil.example"), "the spoofed Host never reaches the reset link")
    }

    // --- H2: duplicate-page read/write bypass ---

    @Test
    fun `duplicate page cannot read a source the contributor is denied, nor create outside their write scope`() = testApplication {
        environment { config = h2Config("wikikt-h2-duplicate") }
        application { configure() }

        val admin = newClient()
        val adminCsrf = admin.apiLogin("admin", "test")

        // Seed two source pages: one the contributor may read (sandbox/*), one they may not (hr/*).
        admin.createPage(adminCsrf, "sandbox/base", "PUBLIC BASE")
        admin.createPage(adminCsrf, "hr/salaries", "SECRET SALARIES")

        // A contributor group: write+read on sandbox/, and an explicit DENY read on hr/ (overriding the
        // seeded User-group ALLOW-read-everywhere). No admin verbs.
        val editors = admin.createGroup(adminCsrf, "Editors", emptyList())
        admin.addRule(adminCsrf, editors, effect = "ALLOW", pattern = "sandbox", verbs = listOf("read:pages", "write:pages"))
        admin.addRule(adminCsrf, editors, effect = "DENY", pattern = "hr", verbs = listOf("read:pages"))
        val bobId = admin.createUser(adminCsrf, "bob", "pw-bob-123", listOf(editors))
        assertTrue(bobId.isNotEmpty())

        val bob = newClient()
        val bobCsrf = bob.apiLogin("bob", "pw-bob-123")

        // (1) Read exfiltration attempt: duplicate hr/salaries (denied) into sandbox/leak (writable).
        // The copy must be refused, so no page is persisted at sandbox/leak.
        val exfil = bob.newPage(bobCsrf, path = "sandbox/leak", from = "hr/salaries")
        assertEquals(HttpStatusCode.Found, exfil.status, "the /new form redirects to the editor")
        assertEquals(HttpStatusCode.NotFound, admin.pageStatus(adminCsrf, "sandbox/leak"), "denied source was not copied")

        // (2) Write-outside-scope attempt: duplicate a readable source into docs/* (no write grant).
        // The per-path write check must reject it before any copy.
        val outside = bob.newPage(bobCsrf, path = "docs/secret", from = "sandbox/base")
        assertEquals(HttpStatusCode.Forbidden, outside.status, "cannot create a page outside the write scope")
        assertEquals(HttpStatusCode.NotFound, admin.pageStatus(adminCsrf, "docs/secret"), "no page created outside scope")

        // (3) Positive control: duplicating a readable source into a writable path still works.
        val ok = bob.newPage(bobCsrf, path = "sandbox/copy", from = "sandbox/base")
        assertEquals(HttpStatusCode.Found, ok.status)
        val copied = admin.get("/u/v1/pages/by-path?path=sandbox/copy") { header("X-CSRF-Token", adminCsrf) }.bodyAsText()
        assertTrue(copied.contains("PUBLIC BASE"), "a permitted duplicate copies the source content")
    }

    // --- H3: the page-list endpoints are editor-gated, not anonymously enumerable ---

    @Test
    fun `page list and paths endpoints are gated to editors, not anonymous`() = testApplication {
        environment { config = h2Config("wikikt-h3-pages-gate") }
        application { configure() }

        val admin = newClient()
        val adminCsrf = admin.apiLogin("admin", "test")
        admin.createPage(adminCsrf, "guide/intro", "hello")

        // Anonymous: both list endpoints are refused, so no anonymous request can force a full-site load.
        val anon = newClient()
        assertEquals(HttpStatusCode.Forbidden, anon.get("/u/v1/pages").status, "anon cannot list pages")
        assertEquals(HttpStatusCode.Forbidden, anon.get("/u/v1/pages/paths").status, "anon cannot list paths")

        // A logged-in reader (can read pages, but no write grant) is still refused — proving this is an
        // editor gate, not merely an authentication check.
        val readers = admin.createGroup(adminCsrf, "Readers", emptyList())
        admin.addRule(adminCsrf, readers, effect = "ALLOW", pattern = "guide", verbs = listOf("read:pages"))
        admin.createUser(adminCsrf, "rita", "pw-rita-123", listOf(readers))
        val rita = newClient()
        rita.apiLogin("rita", "pw-rita-123")
        assertEquals(HttpStatusCode.Forbidden, rita.get("/u/v1/pages").status, "reader cannot list pages")
        assertEquals(HttpStatusCode.Forbidden, rita.get("/u/v1/pages/paths").status, "reader cannot list paths")

        // An editor (admin here) gets both, and /paths still returns the seeded page.
        assertEquals(HttpStatusCode.OK, admin.get("/u/v1/pages").status, "editor lists pages")
        val paths = admin.get("/u/v1/pages/paths")
        assertEquals(HttpStatusCode.OK, paths.status, "editor lists paths")
        assertTrue(paths.bodyAsText().contains("guide/intro"), "paths includes the seeded page")
    }

    // --- F1: password change voids outstanding reset tokens ---

    @Test
    fun `changing the password voids outstanding reset tokens`() = testApplication {
        ResetRequestThrottle.reset()
        lateinit var app: Application
        environment { config = h2Config("wikikt-f1-reset-revoke") }
        application { app = this; configure() }

        val admin = newClient()
        val adminCsrf = admin.apiLogin("admin", "test")
        admin.post("/u/v1/users") {
            contentType(ContentType.Application.Json)
            header("X-CSRF-Token", adminCsrf)
            setBody("""{"username":"victim","password":"oldpassword","email":"victim@example.com"}""")
        }
        runBlocking {
            val siteId = app.appContext.sites.catchAll()!!.id
            app.appContext.settings.setBool(siteId, SettingsService.MAIL_ENABLED, true)
        }

        // Victim requests a reset; capture the token from the queued email.
        val anon = newClient()
        val forgotCsrf = Regex("""name="_csrf" value="([^"]+)"""").find(anon.get("/forgot-password").bodyAsText())!!.groupValues[1]
        anon.post("/forgot-password") {
            setBody(FormDataContent(Parameters.build { append("_csrf", forgotCsrf); append("email", "victim@example.com") }))
        }
        val token = Regex("""token=([^&"\s]+)""").find(queuedResetContext(app))!!.groupValues[1]
        assertTrue(anon.get("/reset-password?token=$token").bodyAsText().contains("New password"), "token valid before change")

        // Victim logs in and changes their password in account settings.
        val victim = newClient()
        val victimCsrf = victim.apiLogin("victim", "oldpassword")
        val change = victim.post("/p/password") {
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("_csrf", victimCsrf)
                        append("currentPassword", "oldpassword")
                        append("newPassword", "brand-new-pw")
                        append("confirmPassword", "brand-new-pw")
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, change.status)

        // The previously issued reset token is now void — the reset form is no longer shown.
        assertFalse(
            anon.get("/reset-password?token=$token").bodyAsText().contains("New password"),
            "reset token is voided after the password change",
        )
    }

    // --- F2: form login requires anti-CSRF ---

    @Test
    fun `form login requires an anti-CSRF token`() = testApplication {
        environment { config = h2Config("wikikt-f2-login-csrf") }
        application { configure() }

        val client = newClient()
        // No token (as a cross-site auto-submit would send) → login refused, no session created.
        val noCsrf = client.post("/login") {
            setBody(FormDataContent(Parameters.build { append("username", "admin"); append("password", "test") }))
        }
        assertEquals(HttpStatusCode.OK, noCsrf.status, "a login POST without CSRF just re-renders the form")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/u/v1/auth/me").status, "no session was established")

        // With the token issued by GET /login → login succeeds.
        val csrf = Regex("""name="_csrf" value="([^"]+)"""").find(client.get("/login").bodyAsText())!!.groupValues[1]
        val withCsrf = client.post("/login") {
            setBody(FormDataContent(Parameters.build { append("_csrf", csrf); append("username", "admin"); append("password", "test") }))
        }
        assertEquals(HttpStatusCode.Found, withCsrf.status, "a login POST with a valid CSRF token succeeds")
        assertEquals(HttpStatusCode.OK, client.get("/u/v1/auth/me").status, "session established")
    }

    // --- helpers ---

    private fun ApplicationTestBuilder.newClient(): HttpClient =
        createClient { install(HttpCookies); followRedirects = false }

    private fun h2Config(dbName: String) = MapApplicationConfig(
        "wikikt.defaultLocale" to "en",
        "wikikt.defaultAdmin.username" to "admin",
        "wikikt.defaultAdmin.password" to "test",
        "wikikt.database.type" to "h2",
        "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///$dbName;DB_CLOSE_DELAY=-1",
        "wikikt.database.h2.username" to "sa",
        "wikikt.database.h2.password" to "",
    )

    private operator fun MapApplicationConfig.plus(extra: Map<String, String>): MapApplicationConfig {
        extra.forEach { (k, v) -> put(k, v) }
        return this
    }

    /** The stored Mustache context of the queued password-reset email (contains the reset link). Reset
     *  emails are enqueued off the response path, so poll briefly until the row lands. */
    private fun queuedResetContext(app: Application): String = runBlocking {
        repeat(200) {
            val rows = suspendTransaction(app.appContext.database) {
                EmailQueueTable.selectAll()
                    .where { EmailQueueTable.templateKey eq EmailTemplateService.PASSWORD_RESET }
                    .map { it[EmailQueueTable.context] }
                    .toList()
            }
            if (rows.isNotEmpty()) return@runBlocking rows.single()
            delay(25)
        }
        error("no password-reset email was queued")
    }
}

private suspend fun HttpClient.apiLogin(username: String, password: String): String {
    val res = post("/u/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username","password":"$password"}""")
    }
    assertEquals(HttpStatusCode.OK, res.status, "login $username")
    return res.headers["X-CSRF-Token"] ?: error("no CSRF token for $username")
}

private suspend fun HttpClient.createPage(csrf: String, path: String, content: String) {
    val res = post("/u/v1/pages") {
        contentType(ContentType.Application.Json)
        header("X-CSRF-Token", csrf)
        setBody("""{"locale":"en","path":"$path","title":"$path","content":"$content","published":true}""")
    }
    assertEquals(HttpStatusCode.Created, res.status, "create page $path")
}

private suspend fun HttpClient.createGroup(csrf: String, name: String, perms: List<String>): String {
    val permsJson = perms.joinToString(",") { "\"$it\"" }
    val res = post("/u/v1/groups") {
        contentType(ContentType.Application.Json)
        header("X-CSRF-Token", csrf)
        setBody("""{"name":"$name","permissions":[$permsJson]}""")
    }
    assertEquals(HttpStatusCode.Created, res.status, "create group $name")
    return Regex("\"id\":\"(\\d+)\"").find(res.bodyAsText())!!.groupValues[1]
}

private suspend fun HttpClient.createUser(csrf: String, username: String, password: String, groupIds: List<String>): String {
    val gids = groupIds.joinToString(",") { "\"$it\"" }
    val res = post("/u/v1/users") {
        contentType(ContentType.Application.Json)
        header("X-CSRF-Token", csrf)
        setBody("""{"username":"$username","password":"$password","groupIds":[$gids]}""")
    }
    assertEquals(HttpStatusCode.Created, res.status, "create user $username")
    return Regex("\"id\":\"(\\d+)\"").find(res.bodyAsText())!!.groupValues[1]
}

/** Creates a group page rule via the admin console form (effect ALLOW/DENY, PREFIX match). */
private suspend fun HttpClient.addRule(csrf: String, groupId: String, effect: String, pattern: String, verbs: List<String>) {
    val res = post("/a/groups/$groupId/rules") {
        setBody(
            FormDataContent(
                Parameters.build {
                    append("_csrf", csrf)
                    append("effect", effect)
                    append("matchType", "PREFIX")
                    append("pattern", pattern)
                    verbs.forEach { append(it, "on") }
                },
            ),
        )
    }
    assertEquals(HttpStatusCode.Found, res.status, "add $effect rule on $pattern")
}

/** Submits the "new page" form, optionally duplicating from [from]. Returns the raw response (302 on success). */
private suspend fun HttpClient.newPage(csrf: String, path: String, from: String? = null) =
    post("/new") {
        setBody(
            FormDataContent(
                Parameters.build {
                    append("_csrf", csrf)
                    append("locale", "en")
                    append("path", path)
                    if (from != null) {
                        append("from", from)
                        append("fromLocale", "en")
                    }
                },
            ),
        )
    }

private suspend fun HttpClient.pageStatus(csrf: String, path: String): HttpStatusCode =
    get("/u/v1/pages/by-path?path=$path") { header("X-CSRF-Token", csrf) }.status
