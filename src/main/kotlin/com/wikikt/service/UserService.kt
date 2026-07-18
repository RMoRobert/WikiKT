package com.wikikt.service

import com.wikikt.auth.PasswordHasher
import com.wikikt.db.ApiKeysTable
import com.wikikt.db.AssetRevisionsTable
import com.wikikt.db.AssetScheduledTable
import com.wikikt.db.AssetsTable
import com.wikikt.db.EmailVerificationTokensTable
import com.wikikt.db.FragmentsTable
import com.wikikt.db.GroupPermissionsTable
import com.wikikt.db.GroupsTable
import com.wikikt.db.PageEditAclTable
import com.wikikt.db.PageRevisionsTable
import com.wikikt.db.PageStagedTable
import com.wikikt.db.PageViewAclTable
import com.wikikt.db.PagesTable
import com.wikikt.db.PasswordResetTokensTable
import com.wikikt.db.SessionsTable
import com.wikikt.db.UserGroupsTable
import com.wikikt.db.UserMfaFactorsTable
import com.wikikt.db.UserMfaRecoveryCodesTable
import com.wikikt.db.UserStatus
import com.wikikt.db.UsersTable
import com.wikikt.model.CreateUserRequest
import com.wikikt.model.GroupRecord
import com.wikikt.model.UpdateUserRequest
import com.wikikt.model.UserRecord
import com.wikikt.model.nowMillis
import com.wikikt.model.parseId
import com.wikikt.model.toGroupRecord
import com.wikikt.model.toDto
import com.wikikt.model.toUserRecord
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

class UserService(private val database: R2dbcDatabase) {
    suspend fun list(): List<UserRecord> = suspendTransaction(database) {
        UsersTable.selectAll().map { it.toUserRecord() }.toList()
    }

    suspend fun findById(id: UInt): UserRecord? = suspendTransaction(database) {
        UsersTable.selectAll()
            .where { UsersTable.id eq id }
            .map { it.toUserRecord() }
            .singleOrNull()
    }

    suspend fun findByUsername(username: String): UserRecord? = suspendTransaction(database) {
        UsersTable.selectAll()
            .where { UsersTable.username eq username }
            .map { it.toUserRecord() }
            .singleOrNull()
    }

    /**
     * All users whose email matches [email] case-insensitively (email is nullable and not unique, so a
     * shared address can resolve to more than one account). Returns empty for a blank input. Used by the
     * password-reset request flow, which mails a reset link to each match.
     */
    suspend fun findByEmail(email: String): List<UserRecord> = suspendTransaction(database) {
        val needle = email.trim().lowercase()
        if (needle.isEmpty()) return@suspendTransaction emptyList()
        UsersTable.selectAll()
            .where { UsersTable.email.lowerCase() eq needle }
            .map { it.toUserRecord() }
            .toList()
    }

    suspend fun authenticate(username: String, password: String): UserRecord? {
        val user = findByUsername(username) ?: run {
            // Unknown user: burn a bcrypt verification anyway so the response takes as long as a
            // wrong password for a real account (no username-enumeration timing oracle).
            PasswordHasher.verifyDummy(password)
            return null
        }
        return if (PasswordHasher.verify(password, user.passwordHash)) user else null
    }

    suspend fun groupIdsForUser(userId: UInt): List<UInt> = suspendTransaction(database) {
        UserGroupsTable.selectAll()
            .where { UserGroupsTable.userId eq userId }
            .map { it[UserGroupsTable.groupId].value }
            .toList()
    }

    suspend fun groupsForUser(userId: UInt): List<GroupRecord> = suspendTransaction(database) {
        val groupIds = UserGroupsTable.selectAll()
            .where { UserGroupsTable.userId eq userId }
            .map { it[UserGroupsTable.groupId].value }
            .toList()
        if (groupIds.isEmpty()) {
            emptyList()
        } else {
            val perms = GroupPermissionsTable.selectAll()
                .where { GroupPermissionsTable.groupId inList groupIds }
                .toList()
                .groupBy({ it[GroupPermissionsTable.groupId].value }, { it[GroupPermissionsTable.permission] })
            GroupsTable.selectAll()
                .where { GroupsTable.id inList groupIds }
                .map { it.toGroupRecord(perms[it[GroupsTable.id].value].orEmpty().toSet()) }
                .toList()
        }
    }

    suspend fun create(request: CreateUserRequest, actorIsRoot: Boolean = false): UserRecord = suspendTransaction(database) {
        val groupIds = request.groupIds.map(::parseId)
        // Only root may place a user in a system (root-bearing) group, or a delegated manage:users
        // admin could create/assign themselves into the Admin group and escalate to root.
        if (!actorIsRoot && groupIds.any { it in systemGroupIds() }) {
            throw IllegalArgumentException("Only a root administrator can place a user in a system group")
        }
        val now = nowMillis()
        val id = UsersTable.insert {
            it[username] = request.username
            it[passwordHash] = PasswordHasher.hash(request.password)
            it[email] = request.email
            it[createdAt] = now
        }[UsersTable.id].value

        groupIds.forEach { gid ->
            UserGroupsTable.insert {
                it[UserGroupsTable.userId] = id
                it[UserGroupsTable.groupId] = gid
            }
        }

        UsersTable.selectAll().where { UsersTable.id eq id }.map { it.toUserRecord() }.singleOrNull()!!
    }

    /**
     * Creates a self-registered account in [UserStatus.PENDING_EMAIL] (awaiting email confirmation),
     * optionally placing it in [defaultGroupId]. A [defaultGroupId] that names a system (root-bearing)
     * group is IGNORED — self-registration must never mint root, even if REGISTRATION_DEFAULT_GROUP were
     * misconfigured to point at one (see the same guard in [create]/[update]). If an *unconfirmed* account
     * already holds [username] (a never-confirmed registration or a squatter), it and its tokens/group
     * rows are removed first so a real person can claim the name — active/pending-approval accounts are
     * never touched (the caller rejects those before calling this).
     */
    suspend fun register(
        username: String,
        email: String,
        password: String,
        defaultGroupId: UInt?,
    ): UserRecord = suspendTransaction(database) {
        val stale = UsersTable.selectAll()
            .where { (UsersTable.username eq username) and (UsersTable.status eq UserStatus.PENDING_EMAIL.name) }
            .map { it[UsersTable.id].value }
            .toList()
        for (uid in stale) {
            EmailVerificationTokensTable.deleteWhere { EmailVerificationTokensTable.userId eq uid }
            UserGroupsTable.deleteWhere { UserGroupsTable.userId eq uid }
            UsersTable.deleteWhere { UsersTable.id eq uid }
        }
        val now = nowMillis()
        val id = UsersTable.insert {
            it[UsersTable.username] = username
            it[passwordHash] = PasswordHasher.hash(password)
            it[UsersTable.email] = email
            it[createdAt] = now
            it[status] = UserStatus.PENDING_EMAIL.name
        }[UsersTable.id].value
        // Defense in depth at the sink: never auto-place a self-registered account in a system
        // (root-bearing) group. The settings UI is the primary gate, but if a system group ever reached
        // here it would turn "anyone may register" into "anyone may become root".
        if (defaultGroupId != null && defaultGroupId !in systemGroupIds()) {
            UserGroupsTable.insert {
                it[UserGroupsTable.userId] = id
                it[UserGroupsTable.groupId] = defaultGroupId
            }
        }
        UsersTable.selectAll().where { UsersTable.id eq id }.map { it.toUserRecord() }.singleOrNull()!!
    }

    /**
     * Marks a [UserStatus.PENDING_EMAIL] account's address confirmed: it becomes [UserStatus.ACTIVE], or
     * [UserStatus.PENDING_APPROVAL] when [requireApproval] is set (an admin must then approve it). Returns
     * the resulting status, or null if the account wasn't awaiting email confirmation (already confirmed,
     * or purged) — which keeps a replayed link from re-activating a since-approved or deleted account.
     */
    suspend fun markEmailConfirmed(userId: UInt, requireApproval: Boolean): UserStatus? = suspendTransaction(database) {
        val target = if (requireApproval) UserStatus.PENDING_APPROVAL else UserStatus.ACTIVE
        val updated = UsersTable.update(
            { (UsersTable.id eq userId) and (UsersTable.status eq UserStatus.PENDING_EMAIL.name) },
        ) { it[status] = target.name }
        if (updated > 0) target else null
    }

    /** Admin action: activates a [UserStatus.PENDING_APPROVAL] account. Returns false if not pending approval. */
    suspend fun approve(userId: UInt): Boolean = suspendTransaction(database) {
        UsersTable.update(
            { (UsersTable.id eq userId) and (UsersTable.status eq UserStatus.PENDING_APPROVAL.name) },
        ) { it[status] = UserStatus.ACTIVE.name } > 0
    }

    /**
     * Deletes unconfirmed ([UserStatus.PENDING_EMAIL]) accounts created before [olderThan], with their
     * verification tokens and group rows. Runs periodically so an abandoned or squatted registration
     * doesn't hold its username forever. Returns how many were removed.
     */
    suspend fun purgeUnverified(olderThan: Long): Int = suspendTransaction(database) {
        val stale = UsersTable.selectAll()
            .where { (UsersTable.status eq UserStatus.PENDING_EMAIL.name) and (UsersTable.createdAt less olderThan) }
            .map { it[UsersTable.id].value }
            .toList()
        for (uid in stale) {
            EmailVerificationTokensTable.deleteWhere { EmailVerificationTokensTable.userId eq uid }
            UserGroupsTable.deleteWhere { UserGroupsTable.userId eq uid }
            UsersTable.deleteWhere { UsersTable.id eq uid }
        }
        stale.size
    }

    suspend fun update(id: UInt, request: UpdateUserRequest, actorIsRoot: Boolean = false): UserRecord? = suspendTransaction(database) {
        val existing = UsersTable.selectAll()
            .where { UsersTable.id eq id }
            .map { it.toUserRecord() }
            .singleOrNull() ?: return@suspendTransaction null

        if (!actorIsRoot) {
            val systemGroups = systemGroupIds()
            // A non-root admin may not modify an account that itself holds root (any system-group
            // member): that would let manage:users reset the built-in admin's password — or strip its
            // groups — and take over root. Root membership changes go through a root actor only.
            val targetGroups = UserGroupsTable.selectAll()
                .where { UserGroupsTable.userId eq id }
                .map { it[UserGroupsTable.groupId].value }
                .toList()
            if (targetGroups.any { it in systemGroups }) {
                throw IllegalArgumentException("Only a root administrator can modify a system-group member")
            }
            // Nor may they add anyone (incl. themselves) into a system group.
            if (request.groupIds?.map(::parseId)?.any { it in systemGroups } == true) {
                throw IllegalArgumentException("Only a root administrator can place a user in a system group")
            }
        }

        UsersTable.update({ UsersTable.id eq id }) {
            request.username?.let { value -> it[username] = value }
            request.password?.let { value -> it[passwordHash] = PasswordHasher.hash(value) }
            if (request.email != null) {
                it[email] = request.email
            }
            // Present (non-null) → set it, treating blank as "clear" (fall back to username). Absent
            // (null) → leave unchanged. No uniqueness check here: admins may assign a duplicate.
            request.displayName?.let { value -> it[displayName] = value.ifBlank { null } }
        }

        if (request.password != null) {
            // A password change (e.g. resetting a compromised account) must end that account's
            // existing sessions, or a hijacked session would outlive the reset. The user logs in
            // again with the new password; their API keys are separate credentials and unaffected.
            SessionsTable.deleteWhere { SessionsTable.userId eq id }
            // Likewise void any outstanding reset tokens so an attacker-held reset link can't undo it.
            PasswordResetTokensTable.deleteWhere { PasswordResetTokensTable.userId eq id }
        }

        request.groupIds?.let { groupIds ->
            UserGroupsTable.deleteWhere { UserGroupsTable.userId eq id }
            groupIds.map(::parseId).forEach { gid ->
                UserGroupsTable.insert {
                    it[UserGroupsTable.userId] = id
                    it[UserGroupsTable.groupId] = gid
                }
            }
        }

        UsersTable.selectAll().where { UsersTable.id eq id }.map { it.toUserRecord() }.singleOrNull()
    }

    /** Sets (or clears, with null) the user's display timezone. Returns false if no such user. */
    suspend fun setTimezone(id: UInt, timezone: String?): Boolean = suspendTransaction(database) {
        UsersTable.update({ UsersTable.id eq id }) { it[UsersTable.timezone] = timezone } > 0
    }

    /**
     * Replaces the user's password hash (self-service change). Session invalidation is the caller's
     * responsibility — the account flow rotates the current session and drops the others itself.
     * Returns false if no such user.
     */
    suspend fun setPassword(id: UInt, password: String): Boolean = suspendTransaction(database) {
        UsersTable.update({ UsersTable.id eq id }) { it[passwordHash] = PasswordHasher.hash(password) } > 0
    }

    /**
     * Self-service profile update: display name, job title, location, and display timezone in one
     * write. Each argument is stored as-is (callers pass null to clear a field). Returns false if no
     * such user.
     */
    suspend fun updateProfile(
        id: UInt,
        displayName: String?,
        jobTitle: String?,
        location: String?,
        timezone: String?,
    ): Boolean = suspendTransaction(database) {
        UsersTable.update({ UsersTable.id eq id }) {
            it[UsersTable.displayName] = displayName
            it[UsersTable.jobTitle] = jobTitle
            it[UsersTable.location] = location
            it[UsersTable.timezone] = timezone
        } > 0
    }

    /**
     * Whether this [displayName] would collide with a *different* user — either their display name or
     * their username — compared trimmed and case-insensitively. The username is included because a blank
     * display name falls back to the username, so letting someone display as another user's username
     * invites impersonation. A blank name never collides (many accounts leave it unset), and the current
     * user is excluded so their own username stays a valid choice. This backs the self-service uniqueness
     * rule only — it's a UI/route check, not a database constraint, so an administrator can still assign a
     * duplicate from the user editor.
     */
    suspend fun displayNameTaken(displayName: String, excludingUserId: UInt): Boolean = suspendTransaction(database) {
        val needle = displayName.trim().lowercase()
        if (needle.isEmpty()) return@suspendTransaction false
        UsersTable.selectAll()
            .where {
                ((UsersTable.displayName.lowerCase() eq needle) or (UsersTable.username.lowerCase() eq needle)) and
                    (UsersTable.id neq excludingUserId)
            }
            .limit(1)
            .map { it[UsersTable.id] }
            .toList()
            .isNotEmpty()
    }

    /** Sets the per-user color-theme override (light|dark|auto, or null to follow the site default). */
    suspend fun updateTheme(id: UInt, theme: String?): Boolean = suspendTransaction(database) {
        UsersTable.update({ UsersTable.id eq id }) { it[UsersTable.theme] = theme } > 0
    }

    /**
     * Sets the per-user date/time display preferences (keys into DateDisplay's catalogs, or null to
     * follow the code defaults). Display-only, saved alongside the timezone on the account form.
     * Returns false if no such user.
     */
    suspend fun updateDateTimeFormats(
        id: UInt,
        dateFormatShort: String?,
        dateFormatLong: String?,
        timeFormat: String?,
    ): Boolean = suspendTransaction(database) {
        UsersTable.update({ UsersTable.id eq id }) {
            it[UsersTable.dateFormatShort] = dateFormatShort
            it[UsersTable.dateFormatLong] = dateFormatLong
            it[UsersTable.timeFormat] = timeFormat
        } > 0
    }

    /**
     * Deletes a user. Their content is never deleted with them: authorship references (pages,
     * revisions, staged versions, assets, fragments) are nulled so the content survives
     * unattributed, while things that only make sense for a live user — sessions, group
     * memberships, per-user ACL entries, and API keys (which authenticate AS the user) — are removed.
     */
    suspend fun delete(id: UInt): Boolean = suspendTransaction(database) {
        SessionsTable.deleteWhere { SessionsTable.userId eq id }
        PasswordResetTokensTable.deleteWhere { PasswordResetTokensTable.userId eq id }
        EmailVerificationTokensTable.deleteWhere { EmailVerificationTokensTable.userId eq id }
        UserMfaFactorsTable.deleteWhere { UserMfaFactorsTable.userId eq id }
        UserMfaRecoveryCodesTable.deleteWhere { UserMfaRecoveryCodesTable.userId eq id }
        UserGroupsTable.deleteWhere { UserGroupsTable.userId eq id }
        ApiKeysTable.deleteWhere { ApiKeysTable.userId eq id }
        PageViewAclTable.deleteWhere { PageViewAclTable.userId eq id }
        PageEditAclTable.deleteWhere { PageEditAclTable.userId eq id }
        PagesTable.update({ PagesTable.updatedBy eq id }) { it[updatedBy] = null }
        PageRevisionsTable.update({ PageRevisionsTable.createdBy eq id }) { it[createdBy] = null }
        PageStagedTable.update({ PageStagedTable.updatedBy eq id }) { it[updatedBy] = null }
        AssetsTable.update({ AssetsTable.uploadedBy eq id }) { it[uploadedBy] = null }
        AssetRevisionsTable.update({ AssetRevisionsTable.createdBy eq id }) { it[createdBy] = null }
        AssetScheduledTable.update({ AssetScheduledTable.createdBy eq id }) { it[createdBy] = null }
        FragmentsTable.update({ FragmentsTable.updatedBy eq id }) { it[updatedBy] = null }
        UsersTable.deleteWhere { UsersTable.id eq id } > 0
    }

    suspend fun toDto(user: UserRecord) = user.toDto(groupIdsForUser(user.id))

    /**
     * Group ids that carry root (`manage:system`). Used by [create]/[update] to keep a delegated
     * `manage:users` admin from assigning anyone into — or editing a member of — a root group. Must
     * run inside an existing transaction.
     */
    private suspend fun systemGroupIds(): Set<UInt> =
        GroupPermissionsTable.selectAll()
            .where { GroupPermissionsTable.permission eq AccessResolver.Perm.MANAGE_SYSTEM }
            .map { it[GroupPermissionsTable.groupId].value }
            .toList()
            .toSet()
}
