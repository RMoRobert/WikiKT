package com.wikikt

import com.wikikt.routing.DateDisplay
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TimeZoneDisplayTest {
    // A fixed instant: 2026-01-02 03:04 UTC.
    private val instant = ZoneId.of("UTC").let {
        java.time.LocalDateTime.of(2026, 1, 2, 3, 4).atZone(it).toInstant().toEpochMilli()
    }

    // Builds a DisplayFormats bundle; nulls fall back to the code defaults (iso / long / 24-hour).
    private fun formats(
        zone: String,
        short: String? = null,
        long: String? = null,
        time: String? = null,
        locale: java.util.Locale = java.util.Locale.ENGLISH,
    ) = DateDisplay.resolve(ZoneId.of(zone), short, long, time, locale)

    @Test
    fun `DateDisplay formats an instant in the given zone`() {
        assertEquals("2026-01-02 03:04", DateDisplay.format(instant, formats("UTC")))
        // Asia/Kolkata is UTC+5:30 with no DST.
        assertEquals("2026-01-02 08:34", DateDisplay.format(instant, formats("Asia/Kolkata")))
        // A zone behind UTC rolls back to the previous day.
        assertEquals("2026-01-01 22:04", DateDisplay.format(instant, formats("America/New_York")))
    }

    @Test
    fun `DateDisplay honors the short-date and time-format preferences`() {
        assertEquals("2026-01-02 03:04", DateDisplay.format(instant, formats("UTC", short = "iso", time = "24")))
        assertEquals("01/02/2026 03:04", DateDisplay.format(instant, formats("UTC", short = "us")))
        assertEquals("02/01/2026 03:04", DateDisplay.format(instant, formats("UTC", short = "eu")))
        assertEquals("02.01.2026 03:04", DateDisplay.format(instant, formats("UTC", short = "dot")))
        assertEquals("Jan 2, 2026 03:04", DateDisplay.format(instant, formats("UTC", short = "abbrev")))
        // 12-hour clock swaps the time portion for an AM/PM value; 03:04 UTC → 3:04 AM.
        assertEquals("2026-01-02 3:04 AM", DateDisplay.format(instant, formats("UTC", time = "12")))
        // An afternoon instant reads as PM. 2026-01-02 13:04 UTC.
        val pm = java.time.LocalDateTime.of(2026, 1, 2, 13, 4).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("2026-01-02 1:04 PM", DateDisplay.format(pm, formats("UTC", time = "12")))
        // Unknown keys fall back to the defaults rather than throwing.
        assertEquals("2026-01-02 03:04", DateDisplay.format(instant, formats("UTC", short = "bogus", time = "bogus")))
    }

    @Test
    fun `DateDisplay formats a date-only value in the locale's style and long-date preference`() {
        assertEquals("January 2, 2026", DateDisplay.formatDate(instant, formats("UTC", long = "long")))
        // The zone still applies before the time is dropped: behind UTC it's the previous day.
        assertEquals("January 1, 2026", DateDisplay.formatDate(instant, formats("America/New_York", long = "long")))
        // Medium is abbreviated; Full adds the weekday.
        assertEquals("Jan 2, 2026", DateDisplay.formatDate(instant, formats("UTC", long = "medium")))
        assertEquals("Friday, January 2, 2026", DateDisplay.formatDate(instant, formats("UTC", long = "full")))
        // A non-English locale gets its own conventions, not a hardcoded pattern.
        assertEquals("2. Januar 2026", DateDisplay.formatDate(instant, formats("UTC", long = "long", locale = java.util.Locale.GERMAN)))
    }

    @Test
    fun `datetime-local input round-trips through a zone`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val millis = DateDisplay.parseInput("2026-01-02T08:34", zone)
        assertNotNull(millis)
        assertEquals(instant, millis)
        assertEquals("2026-01-02T08:34", DateDisplay.toInput(millis, zone))
    }

    private fun config(name: String) = MapApplicationConfig(
        "wikikt.defaultLocale" to "en",
        "wikikt.defaultAdmin.username" to "admin",
        "wikikt.defaultAdmin.password" to "test",
        "wikikt.database.type" to "h2",
        "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-$name;DB_CLOSE_DELAY=-1",
        "wikikt.database.h2.username" to "sa",
        "wikikt.database.h2.password" to "",
    )

    @Test
    fun `a saved timezone changes how timestamps render, and invalid zones are rejected`() = testApplication {
        environment { config = config("tz-e2e") }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val csrf = admin.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        // A key with an expiry gives us a zone-dependent timestamp (the "Expires" cell).
        admin.submitForm(
            url = "/p/api-keys",
            formParameters = Parameters.build {
                append("_csrf", csrf); append("name", "tz-probe"); append("expiresIn", "30")
            },
        )
        val dateRegex = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""")

        suspend fun setZone(tz: String) {
            admin.submitForm(
                url = "/p/settings",
                formParameters = Parameters.build { append("_csrf", csrf); append("timezone", tz) },
            )
        }

        setZone("UTC")
        val utcDate = dateRegex.find(admin.get("/p/api-keys").bodyAsText())?.value
        setZone("Asia/Kolkata")
        val kolkataDate = dateRegex.find(admin.get("/p/api-keys").bodyAsText())?.value

        assertNotNull(utcDate, "a formatted expiry date is shown")
        assertNotNull(kolkataDate)
        assertNotEquals(utcDate, kolkataDate, "the same instant renders differently once the zone changes")

        // The settings page reflects the saved choice.
        val settings = admin.get("/p/settings").bodyAsText()
        assertTrue(
            settings.contains(Regex("""<option value="Asia/Kolkata"[^>]*selected""")),
            "the saved zone is preselected",
        )

        // An unknown zone is rejected and not saved.
        val bad = admin.submitForm(
            url = "/p/settings",
            formParameters = Parameters.build { append("_csrf", csrf); append("timezone", "Not/AZone") },
        )
        assertEquals(HttpStatusCode.OK, bad.status)
        assertTrue(bad.bodyAsText().contains("Unknown time zone"), "invalid zone shows an error")
        assertTrue(
            admin.get("/p/settings").bodyAsText().contains(Regex("""<option value="Asia/Kolkata"[^>]*selected""")),
            "the previous valid zone is retained after a rejected change",
        )

        // Clearing falls back to the server default.
        setZone("")
        val cleared = admin.get("/p/settings").bodyAsText()
        assertTrue(cleared.contains(Regex("""<option value=""[^>]*selected""")), "cleared → Server default selected")
    }

    @Test
    fun `saved date-time format preferences persist, preselect, and change rendering`() = testApplication {
        environment { config = config("fmt-e2e") }
        application { configure() }

        val admin = createClient { install(HttpCookies) }
        val csrf = admin.post("/u/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"test"}""")
        }.headers["X-CSRF-Token"]!!

        // A key with an expiry gives us a rendered timestamp on the API-keys page.
        admin.submitForm(
            url = "/p/api-keys",
            formParameters = Parameters.build {
                append("_csrf", csrf); append("name", "fmt-probe"); append("expiresIn", "30")
            },
        )

        // Pin the zone so the assertions don't depend on the host default, then choose US short + 12-hour.
        admin.submitForm(
            url = "/p/settings",
            formParameters = Parameters.build {
                append("_csrf", csrf)
                append("timezone", "UTC")
                append("dateFormatShort", "us")
                append("timeFormat", "12")
                append("dateFormatLong", "medium")
            },
        )

        // The choices are preselected on reload.
        val settings = admin.get("/p/settings").bodyAsText()
        assertTrue(settings.contains(Regex("""<option value="us"[^>]*selected""")), "short-date choice preselected")
        assertTrue(settings.contains(Regex("""<option value="12"[^>]*selected""")), "time-format choice preselected")
        assertTrue(settings.contains(Regex("""<option value="medium"[^>]*selected""")), "long-date choice preselected")

        // The API-keys page now renders MM/dd/yyyy with an AM/PM time rather than the ISO default.
        val keysPage = admin.get("/p/api-keys").bodyAsText()
        assertTrue(
            keysPage.contains(Regex("""\d{2}/\d{2}/\d{4} \d{1,2}:\d{2} [AP]M""")),
            "the expiry renders in the chosen US + 12-hour format",
        )

        // An unknown key is ignored (falls back to the default), not persisted.
        admin.submitForm(
            url = "/p/settings",
            formParameters = Parameters.build { append("_csrf", csrf); append("dateFormatShort", "bogus") },
        )
        assertTrue(
            admin.get("/p/settings").bodyAsText().contains(Regex("""<option value=""[^>]*selected""")),
            "an invalid short-date key clears to Site default",
        )
    }
}
