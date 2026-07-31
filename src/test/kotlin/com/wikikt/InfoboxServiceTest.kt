package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.ContentFormat
import com.wikikt.db.DatabaseFactory
import com.wikikt.markdown.MarkdownRenderer
import com.wikikt.model.InfoboxFieldDef
import com.wikikt.model.PageRecord
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

    /** A minimal page for [InfoboxService.usageReport], which reads only locale/path/title/tags/infobox. */
    private fun page(siteId: UInt, path: String, title: String, infobox: String?, tags: List<String> = emptyList()) =
        PageRecord(
            id = 0u, siteId = siteId, locale = "en", path = path, title = title, description = null,
            content = "", contentFormat = ContentFormat.MARKDOWN, published = true, publishAt = null,
            createdAt = 0L, updatedAt = 0L, updatedBy = null, tags = tags, infobox = infobox,
        )

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
    fun `the usage report separates filled, unfilled, missing-required and leftover data`() = runBlocking {
        val f = Fixture("wikikt-infobox-usage")
        val siteId = f.setup()
        val city = f.infobox.createTemplate(
            siteId, "city", "City", null,
            listOf(
                InfoboxFieldDef("population", "Population", required = true),
                InfoboxFieldDef("region", "Region"),
            ),
        )
        f.infobox.createTemplate(siteId, "unused", "Unused", null, listOf(InfoboxFieldDef("x", "X")))
        f.infobox.createPathRule(siteId, "cities/**", city, InfoboxService.MATCH_PATH)

        val pages = listOf(
            page(siteId, "cities/paris", "Paris", """{"city":{"population":"2.1M","region":"IDF"}}"""),
            page(siteId, "cities/lyon", "Lyon", """{"city":{"region":"ARA"}}"""),  // required field blank
            page(siteId, "cities/nice", "Nice", null),                             // eligible, untouched
            page(siteId, "about", "About", """{"city":{"population":"x"}}"""),     // no rule matches here
        )
        val report = f.infobox.usageReport(siteId, pages)

        val city1 = report.templates.single { it.slug == "city" }
        assertEquals(3, city1.matched, "only the three pages under cities/ are selected")
        assertEquals(2, city1.filled)
        assertEquals(1, city1.unfilled)
        assertEquals(1, city1.incomplete)
        assertEquals(1, city1.ruleCount)

        val unused = report.templates.single { it.slug == "unused" }
        assertEquals(0, unused.ruleCount, "a template no rule points at selects nothing")
        assertEquals(0, unused.matched)

        assertEquals(listOf("Population"), report.pages.single { it.path == "cities/lyon" }.missingRequired)
        assertTrue(report.pages.single { it.path == "cities/nice" }.isUnfilled)
        assertTrue(report.pages.single { it.path == "cities/paris" }.let { !it.isUnfilled && !it.isIncomplete })

        // "about" holds city data but no rule selects it there: it's leftover, not a usage row.
        assertTrue(report.pages.none { it.path == "about" }, "an unmatched page contributes no usage row")
        assertEquals(listOf("city"), report.orphans.single { it.path == "about" }.keys)
    }

    @Test
    fun `a field with help text renders a label readers can ask about, one without stays plain`() = runBlocking {
        val f = Fixture("wikikt-infobox-help")
        val siteId = f.setup()
        val template = f.infobox.createTemplate(
            siteId, "city", "City", null,
            listOf(
                InfoboxFieldDef("population", "Population", help = "Residents at the last census."),
                InfoboxFieldDef("region", "Region"),
            ),
        )
        f.infobox.createPathRule(siteId, "cities/**", template, InfoboxService.MATCH_PATH)

        val card = f.infobox.renderCard(siteId, "cities/paris", emptyList(), """{"city":{"population":"2.1M","region":"Ile-de-France"}}""")!!
        assertTrue(card.contains("""data-bs-content="Residents at the last census.""""), "help text drives the popover: $card")
        assertTrue(card.contains("""data-bs-title="Population""""), "the popover is headed by the field's label")
        assertTrue(card.contains("""title="Residents at the last census."""), "and doubles as a no-JS native tooltip")
        assertEquals(1, Regex("wk-infobox-help\"").findAll(card).count(), "only the field with help gets a button")
        assertTrue(card.contains("<dt>Region</dt>"), "a field without help keeps a plain label: $card")
    }

    @Test
    fun `section headings group the card, and a section with nothing filled in is dropped whole`() = runBlocking {
        val f = Fixture("wikikt-infobox-sections")
        val siteId = f.setup()
        val template = f.infobox.createTemplate(
            siteId, "city", "City", null,
            listOf(
                InfoboxFieldDef("population", "Population"),
                InfoboxFieldDef.heading("Geography"),
                InfoboxFieldDef("area", "Area"),
                InfoboxFieldDef.heading("Government"),
                InfoboxFieldDef("mayor", "Mayor"),
            ),
        )
        f.infobox.createPathRule(siteId, "cities/**", template, InfoboxService.MATCH_PATH)

        // Population (before any heading) and Area (under Geography) are filled; Government is not.
        val card = f.infobox.renderCard(
            siteId, "cities/paris", emptyList(),
            """{"city":{"population":"2.1M","area":"105 km²"}}""",
        )!!
        assertTrue(card.contains("""<p class="wk-infobox-section">Geography</p>"""), "the filled section keeps its heading: $card")
        assertTrue(!card.contains("Government"), "a section with every field blank is dropped, heading included: $card")
        // Fields before the first heading form their own unheaded list, so two <dl>s here, not one.
        assertEquals(2, Regex("<dl").findAll(card).count(), "one list per rendered section: $card")
        assertTrue(card.indexOf("Population") < card.indexOf("Geography"), "unheaded fields come first")
    }

    @Test
    fun `a template with no headings renders exactly one list, as before`() = runBlocking {
        val f = Fixture("wikikt-infobox-no-sections")
        val siteId = f.setup()
        val template = f.infobox.createTemplate(
            siteId, "city", "City", null,
            listOf(InfoboxFieldDef("population", "Population"), InfoboxFieldDef("region", "Region")),
        )
        f.infobox.createPathRule(siteId, "cities/**", template, InfoboxService.MATCH_PATH)

        val card = f.infobox.renderCard(siteId, "cities/paris", emptyList(), """{"city":{"population":"2.1M"}}""")!!
        assertEquals(1, Regex("<dl").findAll(card).count())
        assertTrue(!card.contains("wk-infobox-section"), "no heading markup when the template uses none")
    }

    @Test
    fun `headings are not counted as fields by the usage report`() = runBlocking {
        val f = Fixture("wikikt-infobox-section-usage")
        val siteId = f.setup()
        val template = f.infobox.createTemplate(
            siteId, "city", "City", null,
            listOf(
                InfoboxFieldDef.heading("Geography"),
                InfoboxFieldDef("population", "Population", required = true),
                InfoboxFieldDef("area", "Area"),
            ),
        )
        f.infobox.createPathRule(siteId, "cities/**", template, InfoboxService.MATCH_PATH)

        val pages = listOf(page(siteId, "cities/paris", "Paris", """{"city":{"population":"2.1M","area":"105"}}"""))
        val row = f.infobox.usageReport(siteId, pages).pages.single()
        assertEquals(2, row.totalFields, "the heading isn't a field to fill in")
        assertEquals(2, row.filledFields)
        assertTrue(row.missingRequired.isEmpty(), "a complete page reads as complete, not 2 of 3")
    }

    @Test
    fun `help text is escaped into its attributes`() = runBlocking {
        val f = Fixture("wikikt-infobox-help-escaping")
        val siteId = f.setup()
        val template = f.infobox.createTemplate(
            siteId, "city", "City", null,
            listOf(InfoboxFieldDef("population", "Population", help = """Counted "residents" & <guests>""")),
        )
        f.infobox.createPathRule(siteId, "cities/**", template, InfoboxService.MATCH_PATH)

        val card = f.infobox.renderCard(siteId, "cities/paris", emptyList(), """{"city":{"population":"2.1M"}}""")!!
        assertTrue(card.contains("&quot;residents&quot; &amp; &lt;guests&gt;"), "quotes and angle brackets can't break out of the attribute: $card")
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
