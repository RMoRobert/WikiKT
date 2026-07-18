package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.db.GroupPermissionsTable
import com.wikikt.db.GroupsTable
import com.wikikt.db.UserStatus
import com.wikikt.model.CreateGroupRequest
import com.wikikt.service.AccessResolver
import com.wikikt.service.EmailVerificationService
import com.wikikt.service.GroupService
import com.wikikt.service.MigrationService
import com.wikikt.service.SettingsService
import com.wikikt.service.SiteService
import com.wikikt.service.UserService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistrationFlowTest {
    private class Fx(
        val users: UserService,
        val verify: EmailVerificationService,
        val settings: SettingsService,
        val groups: GroupService,
        val database: R2dbcDatabase,
        val siteId: UInt,
    )

    private fun fixture() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-registration-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = SiteService(database).create("Test", null, true).id
        Fx(UserService(database), EmailVerificationService(database), SettingsService(database), GroupService(database), database, siteId)
    }

    /**
     * Seeds a system (root-bearing) group directly at the table level. GroupService.create() strips
     * manage:system, so a test that needs a real root group inserts it the way SeedService does for Admin.
     */
    private suspend fun Fx.seedRootGroup(name: String): UInt = suspendTransaction(database) {
        val gid = GroupsTable.insert {
            it[GroupsTable.name] = name
            it[GroupsTable.isSystem] = true
        }[GroupsTable.id].value
        GroupPermissionsTable.insert {
            it[GroupPermissionsTable.groupId] = gid
            it[GroupPermissionsTable.permission] = AccessResolver.Perm.MANAGE_SYSTEM
        }
        gid
    }

    @Test
    fun `a new registration is pending email and cannot sign in`() = runBlocking {
        val fx = fixture()
        val user = fx.users.register("alice", "alice@example.com", "hunter2pw", defaultGroupId = null)
        assertEquals(UserStatus.PENDING_EMAIL, user.status)
        // Correct credentials still authenticate (the login ROUTE enforces the status gate), but the
        // account is not ACTIVE, so the route refuses to create a session.
        val authed = fx.users.authenticate("alice", "hunter2pw")
        assertNotNull(authed)
        assertEquals(UserStatus.PENDING_EMAIL, authed.status)
        // A registrant with no default group has no explicit memberships (they rely on the implicit "User" group).
        assertTrue(fx.users.groupIdsForUser(user.id).isEmpty())
    }

    @Test
    fun `confirming email activates the account and the token is single-use`() = runBlocking {
        val fx = fixture()
        val user = fx.users.register("bob", "bob@example.com", "hunter2pw", defaultGroupId = null)
        val token = fx.verify.createToken(user.id)

        val consumedFor = fx.verify.consume(token)
        assertEquals(user.id, consumedFor)
        assertEquals(UserStatus.ACTIVE, fx.users.markEmailConfirmed(user.id, requireApproval = false))
        assertEquals(UserStatus.ACTIVE, fx.users.findById(user.id)?.status)

        // The link can't be replayed, and a second confirm is a no-op.
        assertNull(fx.verify.consume(token), "confirmation token is single-use")
        assertNull(fx.users.markEmailConfirmed(user.id, requireApproval = false), "already-active account can't be re-confirmed")
    }

    @Test
    fun `an expired confirmation token cannot be consumed`() = runBlocking {
        val fx = fixture()
        val user = fx.users.register("carol", "carol@example.com", "hunter2pw", defaultGroupId = null)
        val token = fx.verify.createToken(user.id, ttlMillis = 1_000)
        assertNull(fx.verify.consume(token, now = System.currentTimeMillis() + 60_000))
    }

    @Test
    fun `approval flow holds the account until an admin approves`() = runBlocking {
        val fx = fixture()
        val user = fx.users.register("dave", "dave@example.com", "hunter2pw", defaultGroupId = null)
        assertEquals(UserStatus.PENDING_APPROVAL, fx.users.markEmailConfirmed(user.id, requireApproval = true))
        assertEquals(UserStatus.PENDING_APPROVAL, fx.users.findById(user.id)?.status)

        assertTrue(fx.users.approve(user.id), "admin approval activates the account")
        assertEquals(UserStatus.ACTIVE, fx.users.findById(user.id)?.status)
        assertFalse(fx.users.approve(user.id), "an already-active account isn't pending approval")
    }

    @Test
    fun `re-registering reclaims an unconfirmed username but not an active one`() = runBlocking {
        val fx = fixture()
        fx.users.register("eve", "eve-old@example.com", "hunter2pw", defaultGroupId = null)
        // Same (still unconfirmed) username with a new address: the stale pending row is replaced.
        val reclaimed = fx.users.register("eve", "eve-new@example.com", "hunter2pw", defaultGroupId = null)
        assertEquals("eve-new@example.com", fx.users.findById(reclaimed.id)?.email)
        assertEquals(1, fx.users.list().count { it.username == "eve" }, "only one 'eve' remains")

        // Once confirmed/active, the username is no longer a reclaimable pending row (the route rejects it).
        fx.users.markEmailConfirmed(reclaimed.id, requireApproval = false)
        assertEquals(UserStatus.ACTIVE, fx.users.findByUsername("eve")?.status)
    }

    @Test
    fun `purgeUnverified drops stale pending accounts but keeps active and recent ones`() = runBlocking {
        val fx = fixture()
        val pending = fx.users.register("frank", "frank@example.com", "hunter2pw", defaultGroupId = null)
        val active = fx.users.register("grace", "grace@example.com", "hunter2pw", defaultGroupId = null)
        fx.users.markEmailConfirmed(active.id, requireApproval = false)

        // A cutoff BEFORE these were created purges nothing.
        assertEquals(0, fx.users.purgeUnverified(olderThan = System.currentTimeMillis() - 60_000))
        assertNotNull(fx.users.findById(pending.id))

        // A cutoff AFTER creation purges the still-pending account only; the active one survives.
        assertEquals(1, fx.users.purgeUnverified(olderThan = System.currentTimeMillis() + 60_000))
        assertNull(fx.users.findById(pending.id), "stale pending account was purged")
        assertNotNull(fx.users.findById(active.id), "active account is kept")
        Unit
    }

    @Test
    fun `the email-domain allowlist gates who may register`() = runBlocking {
        val fx = fixture()
        // Empty allowlist → any address is allowed.
        assertTrue(fx.settings.isRegistrationDomainAllowed(fx.siteId, "anyone@anywhere.net"))

        fx.settings.set(fx.siteId, SettingsService.REGISTRATION_ALLOWED_DOMAINS, "example.com, foo.org")
        assertTrue(fx.settings.isRegistrationDomainAllowed(fx.siteId, "a@example.com"))
        assertTrue(fx.settings.isRegistrationDomainAllowed(fx.siteId, "a@FOO.ORG"), "domain match is case-insensitive")
        assertFalse(fx.settings.isRegistrationDomainAllowed(fx.siteId, "a@bar.com"))
        assertFalse(fx.settings.isRegistrationDomainAllowed(fx.siteId, "no-at-sign"))
    }

    @Test
    fun `register never places a self-registered account in a system root group`() = runBlocking {
        val fx = fixture()
        val rootGroup = fx.seedRootGroup("Admins")
        assertTrue(rootGroup in fx.groups.systemGroupIds(), "sanity: the seeded group is a system group")

        // A default group pointing at the root group must be refused at the sink — self-registration must
        // never mint root, even if REGISTRATION_DEFAULT_GROUP were misconfigured to name a root group.
        val mallory = fx.users.register("mallory", "mallory@example.com", "hunter2pw", defaultGroupId = rootGroup)
        assertTrue(fx.users.groupIdsForUser(mallory.id).isEmpty(), "a root default group is dropped, not applied")

        // A normal (non-system) default group is still honored.
        val editors = fx.groups.create(CreateGroupRequest(name = "Editors")).id
        val trent = fx.users.register("trent", "trent@example.com", "hunter2pw", defaultGroupId = editors)
        assertEquals(listOf(editors), fx.users.groupIdsForUser(trent.id), "a normal default group is applied")
    }

    @Test
    fun `resolve validates a confirmation token without spending it`() = runBlocking {
        val fx = fixture()
        val user = fx.users.register("iris", "iris@example.com", "hunter2pw", defaultGroupId = null)
        val token = fx.verify.createToken(user.id)

        // Resolving (what the GET does) is read-only: a mail scanner that fetches the link repeatedly
        // leaves the token usable.
        assertEquals(user.id, fx.verify.resolve(token))
        assertEquals(user.id, fx.verify.resolve(token), "resolve is repeatable and does not spend the token")

        // The token is still spendable afterward (what the POST does), and only once.
        assertEquals(user.id, fx.verify.consume(token))
        assertNull(fx.verify.resolve(token), "a spent token no longer resolves")
        assertNull(fx.verify.consume(token), "a spent token can't be consumed twice")
    }
}
