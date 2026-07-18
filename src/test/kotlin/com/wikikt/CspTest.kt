package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.service.MigrationService
import com.wikikt.service.SettingsService
import com.wikikt.service.SiteService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CspTest {
    @Test
    fun `sanitizeCspSources keeps valid sources and drops junk`() {
        val out = SettingsService.sanitizeCspSources(
            "https://cdn.example.com  https://*.fonts.example.com data: 'unsafe-eval'\n bad;semi <ang> \"quo\"",
        )
        assertEquals(listOf("https://cdn.example.com", "https://*.fonts.example.com", "data:", "'unsafe-eval'"), out)
        // A token that tries to smuggle in a second directive is rejected wholesale (no ';' allowed).
        assertTrue(SettingsService.sanitizeCspSources("evil.com;object-src *").none { it.contains(";") })
        // Whitespace/quotes/angle-brackets can't survive, so header/tag injection is impossible.
        assertTrue(SettingsService.sanitizeCspSources("a\r\nb <x> \"y\"").all { it.matches(Regex("[A-Za-z0-9:/*._'+=-]+")) })
    }

    @Test
    fun `baseline always includes the core protective directives`() {
        val baseline = SettingsService.baselineCspValue()
        assertTrue(baseline.contains("default-src 'self'"))
        assertTrue(baseline.contains("object-src 'none'"))
        assertTrue(baseline.contains("frame-ancestors 'self'"))
        assertTrue(baseline.contains("script-src 'self' 'unsafe-inline'"))
    }

    private fun db(name: String) = DatabaseFactory.connect(
        DatabaseConfig(
            type = DatabaseType.H2,
            connection = DatabaseConnectionConfig("r2dbc:h2:mem:///wikikt-$name;DB_CLOSE_DELAY=-1", "sa", ""),
        ),
    )

    @Test
    fun `admin additions merge into the right directive and report-only switches the header`() = runBlocking<Unit> {
        val database = db("csp-merge")
        MigrationService(database).migrate()
        val siteId = SiteService(database).create("Test", null, true).id
        val settings = SettingsService(database)

        // Baseline only: enforced header, our added host absent.
        val before = settings.contentSecurityPolicy(siteId)
        assertEquals("Content-Security-Policy", before.name)
        assertFalse(before.value.contains("fonts.mycdn.example"))

        // Add a font host + a connect host; turn on report-only.
        settings.set(siteId, "security.csp.fontSrc", "https://fonts.mycdn.example")
        settings.set(siteId, "security.csp.connectSrc", "https://api.example.com")
        settings.setBool(siteId, SettingsService.SECURITY_CSP_REPORT_ONLY, true)

        val after = settings.contentSecurityPolicy(siteId)
        assertEquals("Content-Security-Policy-Report-Only", after.name)
        // Merged into the correct directives, baseline sources preserved.
        assertTrue(Regex("font-src [^;]*'self'[^;]*https://fonts\\.mycdn\\.example").containsMatchIn(after.value), after.value)
        assertTrue(Regex("connect-src 'self' https://api\\.example\\.com").containsMatchIn(after.value), after.value)
        // Fixed directives untouched.
        assertTrue(after.value.contains("object-src 'none'"))
        // A stored injection attempt never produces an extra directive.
        settings.set(siteId, "security.csp.imgSrc", "evil.com;script-src *")
        val hardened = settings.contentSecurityPolicy(siteId)
        assertFalse(hardened.value.contains("script-src *"))
    }
}
