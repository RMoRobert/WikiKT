package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.markdown.MarkdownRenderer
import com.wikikt.model.InfoboxFieldDef
import com.wikikt.service.InfoboxService
import com.wikikt.service.MigrationService
import com.wikikt.service.SettingsService
import com.wikikt.service.SiteService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfoboxServiceTest {
    private class Fixture(name: String) {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///$name;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        val settings = SettingsService(database)
        val infobox = InfoboxService(database, MarkdownRenderer(), settings)
    }

    private suspend fun Fixture.setup(): UInt {
        MigrationService(database).migrate()
        return SiteService(database).create("Test site", null, isCatchAll = true).id
    }

    @Test
    fun `a page matching two rules gets a tab for each, most-specific path first`() = runBlocking {
        val f = Fixture("wikikt-infobox-multi")
        val siteId = f.setup()
        val appInfo = f.infobox.createTemplate(siteId, "app_info", "Integration", null, listOf(InfoboxFieldDef("platform_version", "Introduced in")))
        val person = f.infobox.createTemplate(siteId, "person", "Person", null, listOf(InfoboxFieldDef("role", "Role")))
        // Person via a path rule; Integration via a tag rule — path rules rank above tag rules.
        f.infobox.createPathRule(siteId, "apps/**", person, InfoboxService.MATCH_PATH)
        f.infobox.createPathRule(siteId, "integration", appInfo, InfoboxService.MATCH_TAG)

        val resolved = f.infobox.resolveAllFor(siteId, "apps/x", listOf("integration"))
        assertEquals(listOf("Person", "Integration"), resolved.map { it.name }, "path rule outranks tag rule")
    }

    @Test
    fun `same template matched by two different rules appears once`() = runBlocking {
        val f = Fixture("wikikt-infobox-dedupe")
        val siteId = f.setup()
        val appInfo = f.infobox.createTemplate(siteId, "app_info", "Integration", null, emptyList())
        f.infobox.createPathRule(siteId, "apps/**", appInfo, InfoboxService.MATCH_PATH)
        f.infobox.createPathRule(siteId, "integration", appInfo, InfoboxService.MATCH_TAG)

        val resolved = f.infobox.resolveAllFor(siteId, "apps/x", listOf("integration"))
        assertEquals(1, resolved.size, "the same template matched twice collapses to one entry")
    }

    @Test
    fun `data under keys that map to no applied template is orphaned - not rendered, flagged for cleanup`() = runBlocking {
        val f = Fixture("wikikt-infobox-orphan")
        val siteId = f.setup()
        val appInfo = f.infobox.createTemplate(siteId, "app_info", "Integration", null, listOf(InfoboxFieldDef("platform_version", "Introduced in")))
        val person = f.infobox.createTemplate(siteId, "person", "Person", null, listOf(InfoboxFieldDef("role", "Role")))
        f.infobox.createPathRule(siteId, "apps/**", person, InfoboxService.MATCH_PATH)
        f.infobox.createPathRule(siteId, "integration", appInfo, InfoboxService.MATCH_TAG)

        // Legacy flat data (no template-slug nesting) from before multi-template support: no top-level key
        // is a matched slug, so nothing reads it — it renders no card, pre-fills no tab, and is flagged.
        val legacyFlat = """{"role":"Maintainer"}"""
        assertEquals(null, f.infobox.renderCard(siteId, "apps/x", listOf("integration"), legacyFlat), "legacy flat data no longer renders")
        val forms = f.infobox.formFor(siteId, "apps/x", listOf("integration"), legacyFlat)
        @Suppress("UNCHECKED_CAST")
        val allBlank = forms.all { tab -> (tab["fields"] as List<Map<String, Any?>>).all { it["value"] == "" } }
        assertTrue(allBlank, "no tab is pre-filled from legacy flat data")
        assertTrue(f.infobox.hasOrphanedData(siteId, "apps/x", listOf("integration"), legacyFlat), "legacy flat data is flagged as orphaned")

        // Keyed data for a template that no longer applies here (person doesn't match at "other") is
        // orphaned; the same keyed data where the template DOES apply is not.
        assertTrue(
            f.infobox.hasOrphanedData(siteId, "other", emptyList(), """{"person":{"role":"x"}}"""),
            "keyed data for an unmatched template is orphaned",
        )
        assertTrue(
            !f.infobox.hasOrphanedData(siteId, "apps/x", listOf("integration"), """{"person":{"role":"x"}}"""),
            "keyed data for a matched template is not orphaned",
        )
        // An empty object under an unmatched key carries no content, so it isn't flagged.
        assertTrue(
            !f.infobox.hasOrphanedData(siteId, "other", emptyList(), """{"person":{}}"""),
            "an empty object isn't treated as orphaned data",
        )
    }

    @Test
    fun `saving via the new keyed format keeps each template's data separate`() = runBlocking {
        val f = Fixture("wikikt-infobox-keyed")
        val siteId = f.setup()
        val appInfo = f.infobox.createTemplate(siteId, "app_info", "Integration", null, listOf(InfoboxFieldDef("platform_version", "Introduced in")))
        val person = f.infobox.createTemplate(siteId, "person", "Person", null, listOf(InfoboxFieldDef("role", "Role")))
        f.infobox.createPathRule(siteId, "apps/**", person, InfoboxService.MATCH_PATH)
        f.infobox.createPathRule(siteId, "integration", appInfo, InfoboxService.MATCH_TAG)

        val keyed = """{"app_info":{"platform_version":"2.4.0"},"person":{"role":"Maintainer"}}"""
        val card = f.infobox.renderCard(siteId, "apps/x", listOf("integration"), keyed)
        assertTrue(card != null)
        assertTrue(card!!.contains("2.4.0") && card.contains("Maintainer"), "both templates' own cards render: $card")
        // Two distinct <aside> cards, one per template — not merged into a single card.
        assertEquals(2, Regex("<aside").findAll(card).count())
    }

    @Test
    fun `unfilledTemplateNames lists matched templates that still have no data, and only those`() = runBlocking {
        val f = Fixture("wikikt-infobox-unfilled")
        val siteId = f.setup()
        val appInfo = f.infobox.createTemplate(siteId, "app_info", "Integration", null, listOf(InfoboxFieldDef("platform_version", "Introduced in")))
        val person = f.infobox.createTemplate(siteId, "person", "Person", null, listOf(InfoboxFieldDef("role", "Role")))
        f.infobox.createPathRule(siteId, "apps/**", person, InfoboxService.MATCH_PATH)
        f.infobox.createPathRule(siteId, "integration", appInfo, InfoboxService.MATCH_TAG)

        assertEquals(setOf("Integration", "Person"), f.infobox.unfilledTemplateNames(siteId, "apps/x", listOf("integration"), null).toSet())
        val onlyAppInfoFilled = """{"app_info":{"platform_version":"2.4.0"}}"""
        assertEquals(listOf("Person"), f.infobox.unfilledTemplateNames(siteId, "apps/x", listOf("integration"), onlyAppInfoFilled))
    }

    @Test
    fun `a template with nothing filled in contributes no card even when others on the same page do`() = runBlocking {
        val f = Fixture("wikikt-infobox-optional")
        val siteId = f.setup()
        val appInfo = f.infobox.createTemplate(siteId, "app_info", "Integration", null, listOf(InfoboxFieldDef("platform_version", "Introduced in")))
        val person = f.infobox.createTemplate(siteId, "person", "Person", null, listOf(InfoboxFieldDef("role", "Role")))
        f.infobox.createPathRule(siteId, "apps/**", person, InfoboxService.MATCH_PATH)
        f.infobox.createPathRule(siteId, "integration", appInfo, InfoboxService.MATCH_TAG)

        // Only Integration has data; Person is matched but empty — every infobox is optional, so Person
        // simply doesn't render (opting out is just leaving it blank, never an error or omission bug).
        val onlyAppInfoFilled = """{"app_info":{"platform_version":"2.4.0"}}"""
        val card = f.infobox.renderCard(siteId, "apps/x", listOf("integration"), onlyAppInfoFilled)
        assertTrue(card != null && card.contains("2.4.0"))
        assertEquals(1, Regex("<aside").findAll(card!!).count(), "only the filled-in template renders a card")
    }
}
