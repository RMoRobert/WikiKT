package com.wikikt

import com.wikikt.config.AssetSource
import com.wikikt.config.DEFAULT_SESSION_MAX_AGE_SECONDS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the hand-maintained deployment files to the code they describe, in the spirit of
 * [BuildInfoTest]'s Dockerfile check. Both facts below have drifted before: the Mermaid knob was
 * missing from `.env.home.example` for a month after it shipped, and `docs/install.md` documented a
 * 7-day session default against a 15-day constant for a month. Nothing else reads these files, so a
 * test is the only thing that can notice.
 */
class DeploymentFilesDriftTest {
    private fun read(path: String): String = Files.readString(Path.of(path))

    /** What an operator copies or edits to set the asset knobs; a knob absent from any of these is a silent gap. */
    private val deploymentFiles = listOf(".env.example", ".env.home.example", "docker-compose.prod.yml", "docker-compose.home.yml")

    @Test
    fun `every asset-source knob is offered in every deployment file`() {
        val texts = deploymentFiles.associateWith(::read)
        for (source in AssetSource.entries) {
            for ((file, text) in texts) {
                assertTrue(source.envVar in text, "$file does not mention ${source.envVar} — add the knob there (see AssetSource)")
            }
        }
    }

    @Test
    fun `docs and the shipped yaml describe every asset-source knob the way the admin console does`() {
        val docsLines = read("docs/install.md").lines()
        val yaml = read("src/main/resources/application.yaml")
        for (source in AssetSource.entries) {
            assertTrue(
                docsLines.any { source.envVar in it && source.label in it && source.size in it },
                "docs/install.md needs an Asset delivery table row for ${source.envVar} carrying the label '${source.label}' " +
                    "and size '${source.size}' from AssetSource (the same text the admin console shows)",
            )
            assertTrue(
                docsLines.any { source.envVar in it && "`cdn`" in it && "`local`" in it },
                "docs/install.md's environment-variable reference must list ${source.envVar} with its cdn/local values",
            )
            val key = source.yamlKey.substringAfterLast('.')
            assertTrue(
                Regex("""^\s*$key:\s*cdn\s*$""", RegexOption.MULTILINE).containsMatchIn(yaml),
                "application.yaml must ship '$key: cdn', the documented default",
            )
            assertTrue(source.envVar in yaml, "application.yaml's comment for '$key' must name ${source.envVar} as the override")
        }
    }

    @Test
    fun `session lifetime default agrees across code, shipped yaml and docs`() {
        val default = DEFAULT_SESSION_MAX_AGE_SECONDS
        for (file in listOf("src/main/resources/application.yaml", "src/main/resources/application-postgres.yaml")) {
            assertTrue(
                Regex("""^\s*maxAgeSeconds:\s*$default\s*$""", RegexOption.MULTILINE).containsMatchIn(read(file)),
                "$file must ship maxAgeSeconds: $default — the literal there shadows DEFAULT_SESSION_MAX_AGE_SECONDS on every " +
                    "real run, so the two must agree",
            )
        }
        val row = assertNotNull(
            read("docs/install.md").lines().firstOrNull { "WIKIKT_SESSION_MAX_AGE_SECONDS" in it },
            "docs/install.md must document WIKIKT_SESSION_MAX_AGE_SECONDS",
        )
        val days = default / 86_400
        assertTrue(
            "default $default" in row && "$days days" in row,
            "docs/install.md must state the real default ($default = $days days); the row reads: $row",
        )
    }
}
