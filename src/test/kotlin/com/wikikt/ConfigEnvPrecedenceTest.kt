package com.wikikt

import com.wikikt.config.envOrConfig
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards against the config-shadowing bug: a value shipped in application.yaml must never prevent a
 * WIKIKT_* environment variable from taking effect. Every env-backed setting reads through
 * [envOrConfig], so exercising it here covers the whole class of settings (secureCookie, trustProxy,
 * database.type, environment, …). If someone reintroduces yaml-first precedence, these fail.
 */
class ConfigEnvPrecedenceTest {
    private fun config(vararg pairs: Pair<String, String>) = MapApplicationConfig(*pairs)

    @Test
    fun `env var wins over a non-empty yaml literal`() {
        // The historical bug: yaml `secureCookie: false` shadowed WIKIKT_SESSION_SECURE_COOKIE=true.
        val cfg = config("wikikt.session.secureCookie" to "false")
        val result = cfg.envOrConfig("wikikt.session.secureCookie", "WIKIKT_SESSION_SECURE_COOKIE") { "true" }
        assertEquals("true", result)
    }

    @Test
    fun `yaml value is used when the env var is unset`() {
        val cfg = config("wikikt.database.type" to "postgres")
        val result = cfg.envOrConfig("wikikt.database.type", "WIKIKT_DATABASE_TYPE") { null }
        assertEquals("postgres", result)
    }

    @Test
    fun `a blank env var is treated as unset and falls through to yaml`() {
        val cfg = config("wikikt.ui.assetSource" to "local")
        val result = cfg.envOrConfig("wikikt.ui.assetSource", "WIKIKT_UI_ASSET_SOURCE") { "   " }
        assertEquals("local", result)
    }

    @Test
    fun `a blank yaml value does not shadow, returns null so the caller default applies`() {
        val cfg = config("wikikt.environment" to "")
        val result = cfg.envOrConfig("wikikt.environment", "WIKIKT_ENV") { null }
        assertNull(result)
    }

    @Test
    fun `absent from both yaml and env returns null`() {
        val cfg = config()
        val result = cfg.envOrConfig("wikikt.gitSync.dir", "WIKIKT_GIT_SYNC_DIR") { null }
        assertNull(result)
    }
}
