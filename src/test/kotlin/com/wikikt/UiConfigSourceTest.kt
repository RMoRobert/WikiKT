package com.wikikt

import com.wikikt.config.loadUiConfig
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How the three asset-source settings resolve, at the [loadUiConfig] level rather than through a
 * booted app. The contract is deliberately forgiving: none of them is mandatory, and only an explicit
 * `local` opts out of the CDN. Anything unrecognized lands on the CDN rather than pointing the page
 * at bundled files, because a wrong guess there is a visibly broken install (missing icons), while a
 * wrong guess toward the CDN is merely an outbound request the operator may not have wanted.
 *
 * Note these tests pass a bare [MapApplicationConfig], i.e. no application.yaml — which is exactly the
 * "nothing is configured anywhere" case.
 */
class UiConfigSourceTest {
    /** No yaml key and no env var: the caller default in loadUiConfig is the only thing left. */
    private fun unset() = MapApplicationConfig().loadUiConfig { null }

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test
    fun `completely unset means CDN, not local and not a startup failure`() {
        val ui = unset()
        assertTrue(ui.useCdnAssets, "assetSource")
        assertTrue(ui.useCdnIconFont, "iconFontSource")
        assertTrue(ui.useCdnEmojiFont, "emojiFontSource")
    }

    @Test
    fun `an explicit local opts each one out, independently`() {
        val ui = MapApplicationConfig().loadUiConfig(env("WIKIKT_UI_ICON_FONT_SOURCE" to "local"))
        assertFalse(ui.useCdnIconFont, "the one that was set")
        assertTrue(ui.useCdnAssets, "the others are untouched")
        assertTrue(ui.useCdnEmojiFont)
    }

    @Test
    fun `local is matched case-insensitively and ignores surrounding whitespace`() {
        // Values pasted into a .env or a Portainer field pick up stray spacing and capitalization.
        for (value in listOf("local", "LOCAL", "Local", "  local  ", "\tlocal\n")) {
            val ui = MapApplicationConfig().loadUiConfig(env("WIKIKT_UI_ASSET_SOURCE" to value))
            assertFalse(ui.useCdnAssets, "'$value' should mean local")
        }
    }

    @Test
    fun `blank and unrecognized values fall back to the CDN rather than failing or going local`() {
        for (value in listOf("", "   ", "cdn", "loacl", "true", "yes", "bundled", "self-hosted")) {
            val ui = MapApplicationConfig().loadUiConfig(env("WIKIKT_UI_ASSET_SOURCE" to value))
            assertTrue(ui.useCdnAssets, "'$value' is not the opt-out keyword, so it should mean cdn")
        }
    }

    @Test
    fun `a yaml value applies when the env var is unset, and the env var wins when both are set`() {
        val yaml = MapApplicationConfig("wikikt.ui.assetSource" to "local")
        assertFalse(yaml.loadUiConfig { null }.useCdnAssets, "yaml alone is honored")
        assertTrue(
            yaml.loadUiConfig(env("WIKIKT_UI_ASSET_SOURCE" to "cdn")).useCdnAssets,
            "env overrides the shipped yaml value, per the envOrConfig contract",
        )
    }
}
