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
    fun `dev builds never claim to be a release`() {
        // The test classpath has no builtAt stamp (that's appended only for prod jars), so whatever
        // the version string says, this build must not identify as a release — isRelease gates the
        // update-check UI, and a dev build must never be offered an "upgrade".
        if (BuildInfo.builtAt == null) {
            assertFalse(BuildInfo.isRelease)
        }
    }
}
