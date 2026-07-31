package com.wikikt

import com.wikikt.routing.infoboxFieldsError
import com.wikikt.routing.parseInfoboxFieldLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The admin fields textarea (`name | label | type | options | help`). Parsing is total by design —
 * whatever is typed re-renders — so the guard against a mistake being saved is the separate error
 * check; both sides are covered here.
 */
class InfoboxFieldSyntaxTest {
    @Test
    fun `a full line parses every column`() {
        val f = parseInfoboxFieldLines("climate | Climate | enum* | Tropical, Temperate | Koppen group.").single()
        assertEquals("climate", f.name)
        assertEquals("Climate", f.label)
        assertEquals("enum", f.type)
        assertTrue(f.required, "a trailing * on the type marks the field required")
        assertEquals(listOf("Tropical", "Temperate"), f.options)
        assertEquals("Koppen group.", f.help)
    }

    @Test
    fun `trailing columns and the label may be omitted`() {
        val f = parseInfoboxFieldLines("population").single()
        assertEquals("population", f.label, "the label defaults to the name")
        assertEquals("string", f.type, "the type defaults to string")
        assertTrue(!f.required && f.options.isEmpty() && f.help == null)
    }

    @Test
    fun `an unknown type is not silently taken as a near-miss of a real one`() {
        // There are exactly four type names and no synonyms: anything else is refused on save.
        listOf("text", "choice", "tags", "list", "yesno").forEach {
            assertTrue(infoboxFieldsError("a | A | $it") != null, "'$it' is not a type name")
        }
    }

    @Test
    fun `a valid block reports no error`() {
        val text = """
            population | Population | string | | Residents at the last census.
            climate | Climate | enum | Tropical, Temperate

            languages | Languages | multi | English, French
            capital | Capital city | boolean
        """.trimIndent()
        assertNull(infoboxFieldsError(text), "blank lines and omitted trailing columns are fine")
        assertEquals(4, parseInfoboxFieldLines(text).size, "the blank line is skipped, not parsed as a field")
    }

    @Test
    fun `a misspelt type is refused rather than silently becoming a string`() {
        val error = infoboxFieldsError("population | Population | strong")
        assertTrue(error != null && error.contains("Line 1") && error.contains("strong"), "got: $error")
        assertEquals(
            "string",
            parseInfoboxFieldLines("population | Population | strong").single().type,
            "parsing still falls back so the form can re-render with the bad input shown",
        )
    }

    @Test
    fun `enum and multi must list their options`() {
        // Their choices ARE the options column: without it the field can't be filled in at all.
        assertTrue(infoboxFieldsError("climate | Climate | enum")?.contains("Line 1") == true)
        assertTrue(infoboxFieldsError("langs | Languages | multi | ")?.contains("Line 1") == true)
        // The other two types don't take options, so their absence is not an error.
        assertNull(infoboxFieldsError("population | Population | string"))
        assertNull(infoboxFieldsError("capital | Capital city | boolean"))
    }

    @Test
    fun `the error names the offending line`() {
        val error = infoboxFieldsError("population | Population | string\nclimate | Climate | enum")
        assertTrue(error != null && error.startsWith("Line 2"), "got: $error")
    }

    @Test
    fun `a nameless line is refused`() {
        assertTrue(infoboxFieldsError(" | Population | string")?.contains("needs a name") == true)
    }

    @Test
    fun `a hash line is a section heading, and round-trips as one`() {
        val parsed = parseInfoboxFieldLines("# Geography\npopulation | Population | string\n#Government\ncapital | Capital | boolean")
        assertEquals(listOf(true, false, true, false), parsed.map { it.isHeading }, "the # lines are headings")
        assertEquals(listOf("Geography", "Government"), parsed.filter { it.isHeading }.map { it.label })
        assertEquals("", parsed.first().name, "a heading holds no value, so it has no key")
        assertTrue(parsed.first().let { !it.isValueField }, "and is not a value field")
        assertNull(infoboxFieldsError("# Geography\npopulation | Population | string"))
    }

    @Test
    fun `a heading needs a title`() {
        assertTrue(infoboxFieldsError("#  ")?.contains("needs a title") == true)
        assertEquals(0, parseInfoboxFieldLines("#   ").size, "a titleless heading is dropped, not saved blank")
    }

    @Test
    fun `heading as a type column points at the right syntax`() {
        val error = infoboxFieldsError("geo | Geography | section")
        assertTrue(error != null && error.contains("# Geography"), "got: $error")
    }

    @Test
    fun `a template with no headings parses exactly as before`() {
        val text = "population | Population | string* | | Census count.\nclimate | Climate | enum | Tropical, Arid"
        val parsed = parseInfoboxFieldLines(text)
        assertTrue(parsed.none { it.isHeading })
        assertEquals(2, parsed.size)
    }
}
