package com.wikikt

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test
    fun `version and gitSha are always present`() {
        assertTrue(BuildInfo.version.isNotBlank())
        assertTrue(BuildInfo.gitSha.isNotBlank(), "falls back to 'unknown', never blank")
        assertTrue(BuildInfo.gitSha.length <= 12, "sha is abbreviated for display")
    }

    @Test
    fun `assetVersion needs no URL escaping`() {
        // assetVersion lands raw in `?v=` on every /static URL (Application.kt merges it into the
        // global Mustache model), so it must stay inside the query-safe unreserved alphabet. This is
        // the constraint that keeps release versions to X.Y.Z[-rc.N] — a `+build` suffix would break it.
        assertTrue(BuildInfo.assetVersion.matches(Regex("^[A-Za-z0-9._~-]+$")), "unsafe assetVersion: ${BuildInfo.assetVersion}")
    }

    @Test
    fun `Dockerfile schema-version label matches the migration list`() {
        // The updater sidecar decides whether a failed update may auto-roll-back by comparing the
        // com.wikikt.schema-version image labels — rollback is only safe when the schema did not
        // change. The label is a hand-maintained ARG in the Dockerfile; this pins it to the code so
        // forgetting the bump can't silently disable that guard.
        val dockerfile = java.nio.file.Path.of("Dockerfile")
        val text = java.nio.file.Files.readString(dockerfile)
        val labeled = Regex("""ARG WIKIKT_SCHEMA_VERSION=(\d+)""").find(text)?.groupValues?.get(1)?.toInt()
        val actual = com.wikikt.service.MIGRATIONS.maxOf { it.version }
        kotlin.test.assertEquals(
            actual,
            labeled,
            "Dockerfile's ARG WIKIKT_SCHEMA_VERSION must equal MIGRATIONS.maxOf { it.version } ($actual) — update the Dockerfile",
        )
    }

    @Test
    fun `dev builds never claim to be a release`() {
        // The test classpath has no builtAt stamp (that's appended only for prod jars), so whatever
        // the version string says, this build must not identify as a release — isRelease gates the
        // update-check UI, and a dev build must never be offered an "upgrade".
        if (BuildInfo.builtAt == null) {
            assertFalse(BuildInfo.isRelease)
        }
    }
}
