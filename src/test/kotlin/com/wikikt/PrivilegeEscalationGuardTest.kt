package com.wikikt

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import com.wikikt.appContext
import com.wikikt.auth.Totp
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards against delegated-admin privilege escalation to root (`manage:system`).
 *
 * The model: `manage:groups` / `manage:users` are limited admin verbs an operator can grant to a
 * non-root user, while `manage:system` lives only on the seeded Admin group and is never assignable.
 * These tests assert a holder of a limited verb cannot reach root through the mutation surfaces that
 * don't go through the "Permissions" editor: group membership (from either the group or user side),
 * a root-user password reset, and minting a new root group via the JSON API. Root itself is
 * unaffected (positive controls). See GroupService/UserService guards + PermissionService.isRoot.
 */
class PrivilegeEscalationGuardTest {

    @Test
    fun `delegated admins cannot escalate to root via membership or password reset`() = testApplication {
        environment { config = h2Config("wikikt-privesc-membership") }
        application { configure() }

        val admin = newClient()
        val adminCsrf = admin.apiLogin("admin", "test")

        // Delegated, non-root admins: one holds only manage:groups, the other only manage:users.
        val groupAdmins = admin.createGroup(adminCsrf, "Group Admins", listOf("manage:groups"))
        val userAdmins = admin.createGroup(adminCsrf, "User Admins", listOf("manage:users"))
        val galtd = admin.createUser(adminCsrf, "galtd", "pw-galtd", listOf(groupAdmins))
        val ualtd = admin.createUser(adminCsrf, "ualtd", "pw-ualtd", listOf(userAdmins))

        val adminGroupId = admin.idOfGroupNamed("Admin")
        val adminUserId = admin.idOfUserNamed("admin")

        // --- Path A: manage:groups adds itself to the Admin group (console form POST) ---
        val ga = newClient()
        val gaCsrf = ga.apiLogin("galtd", "pw-galtd")

        // Control: the same token + verb DO work on a normal (non-system) group, so a 403 on the
        // Admin group below is the authorization guard, not a CSRF failure.
        val control = ga.postForm("/a/groups/$groupAdmins/users", gaCsrf, "userIds" to galtd)
        assertEquals(HttpStatusCode.Found, control.status, "manage:groups may edit a normal group's members")

        val attackA = ga.postForm("/a/groups/$adminGroupId/users", gaCsrf, "userIds" to galtd)
        assertEquals(HttpStatusCode.Forbidden, attackA.status, "manage:groups cannot join the Admin group")
        assertTrue(attackA.bodyAsText().contains("Access denied"), "blocked by the authz guard, not CSRF")
        assertFalse(admin.groupIdsOfUser(galtd).contains(adminGroupId), "galtd did not gain Admin membership")

        // --- Path B: manage:users assigns the Admin group to itself (JSON API) ---
        val ua = newClient()
        val uaCsrf = ua.apiLogin("ualtd", "pw-ualtd")

        val attackB = ua.putJson("/u/v1/users/$ualtd", uaCsrf, """{"groupIds":["$userAdmins","$adminGroupId"]}""")
        assertEquals(HttpStatusCode.Forbidden, attackB.status, "manage:users cannot assign itself the Admin group")
        assertFalse(admin.groupIdsOfUser(ualtd).contains(adminGroupId), "ualtd did not gain Admin membership")

        // --- Path C: manage:users resets the root admin's password (JSON API) ---
        val attackC = ua.putJson("/u/v1/users/$adminUserId", uaCsrf, """{"password":"HackedRoot123"}""")
        assertEquals(HttpStatusCode.Forbidden, attackC.status, "manage:users cannot reset the root admin's password")
        // Proof the password is unchanged: the original still authenticates.
        assertEquals(
            HttpStatusCode.OK,
            newClient().rawLogin("admin", "test").status,
            "root password left unchanged",
        )

        // --- Positive controls: root is unaffected by the guards ---
        // Root may modify Admin-group membership (kept as-is here).
        val rootMembers = admin.postForm("/a/groups/$adminGroupId/users", adminCsrf, "userIds" to adminUserId)
        assertEquals(HttpStatusCode.Found, rootMembers.status, "root may edit Admin-group membership")
        // Root may reset a normal user's password.
        val rootReset = admin.putJson("/u/v1/users/$ualtd", adminCsrf, """{"password":"FreshUaltdPw1"}""")
        assertEquals(HttpStatusCode.OK, rootReset.status, "root may reset a normal user's password")
    }

    @Test
    fun `manage groups cannot mint a root group via the JSON API`() = testApplication {
        environment { config = h2Config("wikikt-privesc-rootgroup") }
        application { configure() }

        val admin = newClient()
        val adminCsrf = admin.apiLogin("admin", "test")
        val groupAdmins = admin.createGroup(adminCsrf, "Group Admins", listOf("manage:groups"))
        admin.createUser(adminCsrf, "galtd", "pw-galtd", listOf(groupAdmins))

        val ga = newClient()
        val gaCsrf = ga.apiLogin("galtd", "pw-galtd")

        // Create: manage:system is filtered out of caller input (structural guard in setPermissions).
        val created = ga.postJson(
            "/u/v1/groups",
            gaCsrf,
            """{"name":"Sneaky","permissions":["manage:system","manage:groups"]}""",
        )
        assertEquals(HttpStatusCode.Created, created.status, "group creation itself is allowed")
        assertFalse(created.bodyAsText().contains("manage:system"), "manage:system stripped from the response")
        val sneakyId = idIn(created.bodyAsText())
        val fetched = ga.get("/u/v1/groups/$sneakyId").bodyAsText()
        assertFalse(fetched.contains("manage:system"), "manage:system not persisted")
        assertTrue(fetched.contains("manage:groups"), "the assignable verb was persisted")

        // Update: the same filter applies to editing an existing group's permissions.
        val updated = ga.putJson(
            "/u/v1/groups/$groupAdmins",
            gaCsrf,
            """{"permissions":["manage:system","manage:groups"]}""",
        )
        assertEquals(HttpStatusCode.OK, updated.status)
        assertFalse(updated.bodyAsText().contains("manage:system"), "manage:system stripped on update")
    }

    @Test
    fun `manage groups cannot point self-registration at a root group`() = testApplication {
        environment { config = h2Config("wikikt-privesc-regdefault") }
        application { configure() }

        val admin = newClient()
        val adminCsrf = admin.apiLogin("admin", "test")
        val groupAdmins = admin.createGroup(adminCsrf, "Group Admins", listOf("manage:groups"))
        admin.createUser(adminCsrf, "galtd", "pw-galtd", listOf(groupAdmins))
        val adminGroupId = admin.idOfGroupNamed("Admin")

        val ga = newClient()
        val gaCsrf = ga.apiLogin("galtd", "pw-galtd")

        // The root Admin group must not even be OFFERED as a self-registration default.
        assertFalse(
            ga.get("/a/registration").bodyAsText().contains("<option value=\"$adminGroupId\""),
            "the root Admin group is not offered as a registration default",
        )

        // Control: a normal (non-system) group IS accepted and stored — proves the form + CSRF work, so a
        // dropped Admin group below is the authz guard rather than a CSRF failure.
        val control = ga.postForm(
            "/a/registration", gaCsrf,
            "registrationEnabled" to "1",
            "registrationDefaultGroup" to groupAdmins,
        )
        assertEquals(HttpStatusCode.OK, control.status)
        assertTrue(
            ga.get("/a/registration").bodyAsText().contains("<option value=\"$groupAdmins\" selected"),
            "a normal group is stored as the registration default",
        )

        // Attack: a crafted POST naming the root Admin group must be DROPPED, not stored.
        val attack = ga.postForm(
            "/a/registration", gaCsrf,
            "registrationEnabled" to "1",
            "registrationDefaultGroup" to adminGroupId,
        )
        assertEquals(HttpStatusCode.OK, attack.status)
        assertFalse(
            ga.get("/a/registration").bodyAsText().contains("<option value=\"$adminGroupId\" selected"),
            "the root Admin group was not stored as the registration default",
        )
    }

    @Test
    fun `manage users cannot mint an API key for the root admin`() = testApplication {
        environment { config = h2Config("wikikt-privesc-apikey") }
        application { configure() }

        val admin = newClient()
        val adminCsrf = admin.apiLogin("admin", "test")
        val userAdmins = admin.createGroup(adminCsrf, "User Admins", listOf("manage:users"))
        val ualtd = admin.createUser(adminCsrf, "ualtd", "pw-ualtd", listOf(userAdmins))
        val adminUserId = admin.idOfUserNamed("admin")

        val ua = newClient()
        val uaCsrf = ua.apiLogin("ualtd", "pw-ualtd")

        // Control: manage:users may mint a key for a normal (non-root) account — proves CSRF + perms work,
        // so the 403 below is the authorization guard rather than a CSRF failure.
        val control = ua.postForm("/a/api-keys", uaCsrf, "name" to "ok", "userId" to ualtd)
        assertEquals(HttpStatusCode.OK, control.status, "manage:users may mint a key for a normal user")

        // Attack: a key owned by the root admin would authenticate AS root.
        val attack = ua.postForm("/a/api-keys", uaCsrf, "name" to "pwn", "userId" to adminUserId)
        assertEquals(HttpStatusCode.Forbidden, attack.status, "manage:users cannot mint a key for the root admin")
        assertTrue(attack.bodyAsText().contains("Access denied"), "blocked by the authz guard, not CSRF")
    }

    @Test
    fun `manage users can reset a normal user's MFA but not a root account's`() = testApplication {
        lateinit var app: Application
        environment { config = h2Config("wikikt-privesc-mfareset") }
        application { app = this; configure() }

        val admin = newClient()
        val adminCsrf = admin.apiLogin("admin", "test")
        val userAdmins = admin.createGroup(adminCsrf, "User Admins", listOf("manage:users"))
        admin.createUser(adminCsrf, "ualtd", "pw-ualtd", listOf(userAdmins))
        admin.createUser(adminCsrf, "victim", "pw-victim", emptyList())

        // Enrol both a normal user and the root admin in MFA (through the service).
        val victimId = userIdOf(app, "victim")
        val adminId = userIdOf(app, "admin")
        enrollMfa(app, victimId)
        enrollMfa(app, adminId)
        assertTrue(hasMfa(app, victimId) && hasMfa(app, adminId), "both accounts start with MFA on")

        val ua = newClient()
        val uaCsrf = ua.apiLogin("ualtd", "pw-ualtd")

        // manage:users MAY reset a normal user's MFA (the lockout-recovery use case).
        assertEquals(HttpStatusCode.Found, ua.postForm("/a/users/$victimId/reset-mfa", uaCsrf).status)
        assertFalse(hasMfa(app, victimId), "the normal user's MFA was reset")

        // manage:users MAY NOT reset the root admin's MFA — that's a delegated-admin→root boundary.
        val blocked = ua.postForm("/a/users/$adminId/reset-mfa", uaCsrf)
        assertEquals(HttpStatusCode.Forbidden, blocked.status, "manage:users cannot reset a root account's MFA")
        assertTrue(hasMfa(app, adminId), "the root admin's MFA is untouched")

        // Root MAY reset a root account's MFA.
        assertEquals(HttpStatusCode.Found, admin.postForm("/a/users/$adminId/reset-mfa", adminCsrf).status)
        assertFalse(hasMfa(app, adminId), "root can reset a root account's MFA")
    }

    @Test
    fun `full backup export requires root`() = testApplication {
        environment { config = h2Config("wikikt-privesc-backup") }
        application { configure() }

        val admin = newClient()
        val adminCsrf = admin.apiLogin("admin", "test")
        val groupAdmins = admin.createGroup(adminCsrf, "Group Admins", listOf("manage:groups"))
        admin.createUser(adminCsrf, "galtd", "pw-galtd", listOf(groupAdmins))

        val ga = newClient()
        val gaCsrf = ga.apiLogin("galtd", "pw-galtd")

        // A full export dumps every account's password hash, sessions, API keys, and instance secrets —
        // root only. (The root gate is the same one that downgrades a non-root full *restore* to a no-op.)
        val attack = ga.postForm("/a/backup/export/full", gaCsrf)
        assertEquals(HttpStatusCode.Forbidden, attack.status, "manage:groups cannot full-export the instance")

        val ok = admin.postForm("/a/backup/export/full", adminCsrf)
        assertEquals(HttpStatusCode.OK, ok.status, "root can full-export")
    }

    // --- Test helpers ---

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
}

private suspend fun HttpClient.rawLogin(username: String, password: String) =
    post("/u/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username","password":"$password"}""")
    }

private suspend fun HttpClient.apiLogin(username: String, password: String): String {
    val res = rawLogin(username, password)
    assertEquals(HttpStatusCode.OK, res.status, "login $username")
    return res.headers["X-CSRF-Token"] ?: error("no CSRF token returned for $username")
}

private suspend fun HttpClient.postJson(path: String, csrf: String, body: String) =
    post(path) {
        contentType(ContentType.Application.Json)
        header("X-CSRF-Token", csrf)
        setBody(body)
    }

private suspend fun HttpClient.putJson(path: String, csrf: String, body: String) =
    put(path) {
        contentType(ContentType.Application.Json)
        header("X-CSRF-Token", csrf)
        setBody(body)
    }

/** Form POST with proper URL-encoding (CSRF tokens are base64 and contain +,/,= that a raw body mangles). */
private suspend fun HttpClient.postForm(path: String, csrf: String, vararg fields: Pair<String, String>) =
    post(path) {
        setBody(
            FormDataContent(
                Parameters.build {
                    append("_csrf", csrf)
                    for ((k, v) in fields) append(k, v)
                },
            ),
        )
    }

private suspend fun HttpClient.createGroup(csrf: String, name: String, perms: List<String>): String {
    val permsJson = perms.joinToString(",") { "\"$it\"" }
    val res = postJson("/u/v1/groups", csrf, """{"name":"$name","permissions":[$permsJson]}""")
    assertEquals(HttpStatusCode.Created, res.status, "create group $name")
    return idIn(res.bodyAsText())
}

private suspend fun HttpClient.createUser(csrf: String, username: String, password: String, groupIds: List<String>): String {
    val gids = groupIds.joinToString(",") { "\"$it\"" }
    val res = postJson("/u/v1/users", csrf, """{"username":"$username","password":"$password","groupIds":[$gids]}""")
    assertEquals(HttpStatusCode.Created, res.status, "create user $username")
    return idIn(res.bodyAsText())
}

private suspend fun HttpClient.idOfGroupNamed(name: String): String {
    val json = get("/u/v1/groups").bodyAsText()
    return Regex("\\{\"id\":\"(\\d+)\",\"name\":\"$name\"").find(json)?.groupValues?.get(1)
        ?: error("group '$name' not found in $json")
}

private suspend fun HttpClient.idOfUserNamed(username: String): String {
    val json = get("/u/v1/users").bodyAsText()
    return Regex("\\{\"id\":\"(\\d+)\",\"username\":\"$username\"").find(json)?.groupValues?.get(1)
        ?: error("user '$username' not found in $json")
}

/** The groupIds array of a single user's DTO, as a set of id strings. */
private suspend fun HttpClient.groupIdsOfUser(userId: String): Set<String> {
    val json = get("/u/v1/users/$userId").bodyAsText()
    val inner = Regex("\"groupIds\":\\[(.*?)]").find(json)?.groupValues?.get(1) ?: return emptySet()
    return Regex("\"(\\d+)\"").findAll(inner).map { it.groupValues[1] }.toSet()
}

/** First `"id":"N"` in a JSON object (the created resource's id). */
private fun idIn(json: String): String =
    Regex("\"id\":\"(\\d+)\"").find(json)?.groupValues?.get(1) ?: error("no id in $json")

private fun userIdOf(app: Application, username: String): UInt = runBlocking {
    app.appContext.users.findByUsername(username)!!.id
}

/** Enrols [userId] in TOTP through the app's service (begin + confirm with a live code). */
private fun enrollMfa(app: Application, userId: UInt) = runBlocking {
    val ctx = app.appContext
    val enrollment = ctx.mfa.beginTotpEnrollment(userId, "WikiKT", "u")
    ctx.mfa.confirmTotpEnrollment(userId, Totp.codeAt(Totp.base32Decode(enrollment.secretBase32), System.currentTimeMillis() / 1000))
}

private fun hasMfa(app: Application, userId: UInt): Boolean = runBlocking { app.appContext.mfa.hasMfa(userId) }
