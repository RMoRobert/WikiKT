package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.service.MigrationService
import com.wikikt.service.SettingsService
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsServiceTest {
    @Test
    fun `get set round-trip and boolean default`() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-settings-test;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val settings = SettingsService(database)

        // Unset key: null, and the boolean falls back to the supplied default.
        assertNull(settings.get(siteId, SettingsService.REGISTRATION_ENABLED))
        assertFalse(settings.getBool(siteId, SettingsService.REGISTRATION_ENABLED))
        assertTrue(settings.getBool(siteId, "missing", default = true))

        // Set then read back (insert path).
        settings.setBool(siteId, SettingsService.REGISTRATION_ENABLED, true)
        assertTrue(settings.getBool(siteId, SettingsService.REGISTRATION_ENABLED))
        assertEquals("true", settings.get(siteId, SettingsService.REGISTRATION_ENABLED))

        // Set again (update path, no duplicate row / no crash).
        settings.setBool(siteId, SettingsService.REGISTRATION_ENABLED, false)
        assertFalse(settings.getBool(siteId, SettingsService.REGISTRATION_ENABLED))
        Unit
    }

    @Test
    fun `navMode defaults to static and rejects unknown values`() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-navmode-test;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val settings = SettingsService(database)

        // Unset → the default, which preserves the existing static-menu behavior on upgrade.
        assertEquals(SettingsService.DEFAULT_NAV_MODE, settings.navMode(siteId))
        assertEquals("static", settings.navMode(siteId))

        // A valid value round-trips.
        settings.set(siteId, SettingsService.NAV_MODE, "both")
        assertEquals("both", settings.navMode(siteId))

        // A junk value falls back to the default rather than being surfaced.
        settings.set(siteId, SettingsService.NAV_MODE, "sideways")
        assertEquals(SettingsService.DEFAULT_NAV_MODE, settings.navMode(siteId))
        Unit
    }

    @Test
    fun `external link mode defaults to site and honors explicit off plus instance hosts`() = runBlocking {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-extlink-test;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        val siteId = com.wikikt.service.SiteService(database).create("Test site", null, isCatchAll = true).id
        val settings = SettingsService(database)
        settings.instanceHostsProvider = { setOf("wiki.example.com") }

        // Unset → the product default is SITE (not OFF); no host lookup needed for SITE.
        assertEquals(com.wikikt.markdown.ExternalLinkMode.SITE, settings.renderOptions(siteId).externalLinkMode)

        // An explicit "off" is respected.
        settings.set(siteId, SettingsService.RENDER_EXTERNAL_LINK_ICON, "off")
        assertEquals(com.wikikt.markdown.ExternalLinkMode.OFF, settings.renderOptions(siteId).externalLinkMode)

        // "instance" pulls the internal-host set from the provider.
        settings.set(siteId, SettingsService.RENDER_EXTERNAL_LINK_ICON, "instance")
        val opts = settings.renderOptions(siteId)
        assertEquals(com.wikikt.markdown.ExternalLinkMode.INSTANCE, opts.externalLinkMode)
        assertEquals(setOf("wiki.example.com"), opts.internalHosts)
        Unit
    }
}
