package com.wikikt.config

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionConfigTest {
    @Test
    fun `development without keys falls back to dev keys`() {
        val config = MapApplicationConfig("wikikt.environment" to "development")

        val session = config.loadSessionConfig()

        assertTrue(session.encryptionKey.isNotEmpty())
        assertTrue(session.signKey.isNotEmpty())
    }

    @Test
    fun `production without keys refuses to start`() {
        val config = MapApplicationConfig("wikikt.environment" to "production")

        assertFailsWith<IllegalStateException> {
            config.loadSessionConfig()
        }
    }

    @Test
    fun `production with keys loads successfully`() {
        val config = MapApplicationConfig(
            "wikikt.environment" to "production",
            "wikikt.session.encryptionKey" to "00112233445566778899aabbccddeeff",
            "wikikt.session.signKey" to "0123456789abcdef0123456789abcdef",
            "wikikt.session.secureCookie" to "true",
        )

        val session = config.loadSessionConfig()

        assertTrue(session.secureCookie)
    }

    @Test
    fun `production without secureCookie refuses to start`() {
        // Keys are present, so only the missing Secure flag should stop startup — an interceptable
        // session cookie in production is fatal, not a warning.
        val config = MapApplicationConfig(
            "wikikt.environment" to "production",
            "wikikt.session.encryptionKey" to "00112233445566778899aabbccddeeff",
            "wikikt.session.signKey" to "0123456789abcdef0123456789abcdef",
            "wikikt.session.secureCookie" to "false",
        )

        assertFailsWith<IllegalStateException> {
            config.loadSessionConfig()
        }
    }

    @Test
    fun `development without secureCookie is allowed`() {
        // Local HTTP dev must still boot with the default (insecure) cookie.
        val config = MapApplicationConfig("wikikt.environment" to "development")

        val session = config.loadSessionConfig()

        assertTrue(!session.secureCookie)
    }
}
