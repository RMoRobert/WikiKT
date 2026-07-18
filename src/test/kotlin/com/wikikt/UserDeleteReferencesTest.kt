package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.model.CreateGroupRequest
import com.wikikt.model.CreatePageRequest
import com.wikikt.model.CreateUserRequest
import com.wikikt.model.PageAclDto
import com.wikikt.model.UpdatePageRequest
import com.wikikt.service.ApiKeyService
import com.wikikt.service.AssetService
import com.wikikt.service.FragmentService
import com.wikikt.service.GroupPageRuleService
import com.wikikt.service.GroupService
import com.wikikt.service.MigrationService
import com.wikikt.service.PageService
import com.wikikt.service.UserService
import com.wikikt.model.RuleEffect
import com.wikikt.model.RuleMatchType
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deleting a user or group must never delete (or refuse to delete because of) the content they
 * touched: authorship becomes unattributed, per-user/group grants and credentials go away.
 */
class UserDeleteReferencesTest {

    private fun connect(name: String) = DatabaseFactory.connect(
        DatabaseConfig(
            type = DatabaseType.H2,
            connection = DatabaseConnectionConfig(
                r2dbcUrl = "r2dbc:h2:mem:///wikikt-$name;DB_CLOSE_DELAY=-1",
                username = "sa",
                password = "",
            ),
        ),
    )

    @Test
    fun `deleting a user keeps their content, unattributed, and drops their grants and keys`() = runBlocking<Unit> {
        val database = connect("userdel-test")
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val users = UserService(database)
        val pages = PageService(database)
        val fragments = FragmentService(database)
        val apiKeys = ApiKeyService(database)
        val storage = Files.createTempDirectory("wikikt-userdel-assets")
        Files.createDirectories(storage.resolve("tmp"))
        val assets = AssetService(database, storage)

        val author = users.create(CreateUserRequest("author", "Str0ngPass!word", null, emptyList()))
        // Touch everything a user can leave a mark on: page (+ revision via update), a per-user ACL
        // entry, an asset, a fragment, and an API key.
        val page = pages.create(
            siteId,
            CreatePageRequest(
                locale = "en", path = "authored", title = "T", content = "v1", contentFormat = "MARKDOWN",
                viewAcl = PageAclDto(userIds = listOf(author.id.toString())),
            ),
            updatedBy = author.id,
        )
        pages.update(page.id, UpdatePageRequest(content = "v2"), updatedBy = author.id)
        val temp = assets.newTempFile()
        Files.write(temp, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val asset = assets.create(siteId, "en", "a.png", "a.png", "image/png", 4, temp, uploadedBy = author.id)
        val fragment = fragments.create(siteId, "en", "frag", "Frag", "body", updatedBy = author.id)
        apiKeys.create(author.id, "authors-key", ttlMillis = null)

        assertTrue(users.delete(author.id), "delete must succeed despite authored content")

        // Content survives, unattributed.
        val pageAfter = pages.findById(page.id)
        assertNotNull(pageAfter, "page kept")
        assertNull(pageAfter.updatedBy, "page authorship cleared")
        assertEquals("v2", pageAfter.content)
        assertTrue(pages.revisions(page.id).isNotEmpty() && pages.revisions(page.id).all { it.createdBy == null })
        assertNull(assets.findById(asset.id)!!.uploadedBy, "asset uploader cleared")
        assertNotNull(fragments.findById(fragment.id), "fragment kept")
        // Grants and credentials are gone.
        assertTrue(pages.viewAcl(page.id).userIds.isEmpty(), "per-user ACL entry removed")
        assertTrue(apiKeys.list().none { it.name == "authors-key" }, "API keys deleted with their user")
    }

    @Test
    fun `deleting a group drops memberships, rules, and ACL entries but keeps pages`() = runBlocking<Unit> {
        val database = connect("groupdel-test")
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val users = UserService(database)
        val groups = GroupService(database)
        val pages = PageService(database)
        val rules = GroupPageRuleService(database)

        val group = groups.create(CreateGroupRequest(name = "editors"))
        val member = users.create(CreateUserRequest("member", "Str0ngPass!word", null, listOf(group.id.toString())))
        rules.create(group.id, RuleEffect.DENY, RuleMatchType.PREFIX, "private/", setOf(com.wikikt.service.AccessResolver.Perm.READ_PAGES), emptySet(), emptySet())
        val page = pages.create(
            siteId,
            CreatePageRequest(
                locale = "en", path = "team", title = "Team", content = "c", contentFormat = "MARKDOWN",
                viewAcl = PageAclDto(groupIds = listOf(group.id.toString())),
            ),
            updatedBy = null,
        )

        assertTrue(groups.delete(group.id), "delete must succeed despite members/rules/ACLs")
        assertNotNull(pages.findById(page.id), "page kept")
        assertTrue(pages.viewAcl(page.id).groupIds.isEmpty(), "group ACL entry removed")
        assertTrue(rules.rulesForGroup(group.id).isEmpty(), "page rules removed")
        assertNotNull(users.findById(member.id), "member user untouched")
    }
}
