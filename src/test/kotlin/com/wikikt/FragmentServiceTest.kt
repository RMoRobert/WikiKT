package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.service.FragmentService
import com.wikikt.service.MigrationService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FragmentServiceTest {
    @Test
    fun `expand handles fragments, nesting, cycles, unknowns, code, and locale fallback`() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-fragment-test;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val fragments = FragmentService(database)
        fragments.create(siteId, "en", "note", "Note", "**Hi**", null)
        fragments.create(siteId, "en", "a", "A", "A {{fragment:b}}", null)
        fragments.create(siteId, "en", "b", "B", "B-content", null)
        fragments.create(siteId, "en", "x", "X", "{{fragment:y}}", null)
        fragments.create(siteId, "en", "y", "Y", "{{fragment:x}}", null)
        fragments.create(siteId, "en", "shared", "Shared", "en-shared", null)
        fragments.create(siteId, "en", "/shared/footer", "Footer", "the footer", null)

        assertEquals("**Hi**", fragments.expand(siteId, "{{fragment:note}}", "en", "en"))
        assertEquals("A B-content", fragments.expand(siteId, "{{fragment:a}}", "en", "en"), "nested fragments expand")
        assertTrue(fragments.expand(siteId, "{{fragment:x}}", "en", "en").contains("fragment cycle"), "cycles are broken")
        assertEquals("[missing fragment: nope]", fragments.expand(siteId, "{{fragment:nope}}", "en", "en"))
        assertEquals(
            "`{{fragment:note}}`",
            fragments.expand(siteId, "`{{fragment:note}}`", "en", "en"),
            "references inside code are left literal",
        )
        assertEquals("en-shared", fragments.expand(siteId, "{{fragment:shared}}", "fr", "en"), "falls back to default locale")
        assertEquals("the footer", fragments.expand(siteId, "{{fragment:/shared/footer}}", "en", "en"), "path-like keys work")

        assertEquals(
            setOf("a", "b"),
            fragments.referencedKeys("Uses {{fragment:a}} and {{fragment:b}} and again {{fragment:a}}"),
            "referencedKeys extracts the distinct keys",
        )
        assertTrue(fragments.referencedKeys("`{{fragment:note}}`").isEmpty(), "references inside code are not counted")
    }
}
