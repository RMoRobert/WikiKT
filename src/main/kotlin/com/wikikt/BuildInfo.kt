package com.wikikt

import java.lang.management.ManagementFactory
import java.util.Properties

/** Build-time metadata baked into a classpath resource by Gradle's processResources. */
object BuildInfo {
    private val props: Properties by lazy {
        Properties().apply {
            BuildInfo::class.java.getResourceAsStream("/wikikt.properties")?.use { load(it) }
        }
    }

    /** The project version (e.g. "1.0.0-SNAPSHOT"), or "dev" if the resource is absent. */
    val version: String by lazy { props.getProperty("version") ?: "dev" }

    /** Abbreviated git commit the build was made from, or "unknown" (e.g. a source build without git). */
    val gitSha: String by lazy {
        props.getProperty("gitSha")?.trim()?.take(12)?.ifEmpty { null } ?: "unknown"
    }

    /** Epoch seconds the jar was built, or null for dev builds (only prod builds stamp `builtAt`). */
    val builtAt: Long? by lazy { props.getProperty("builtAt")?.toLongOrNull() }

    /**
     * True only for a stamped build of a final release version (`X.Y.Z`, no prerelease suffix).
     * Gates the update-check UI: a -SNAPSHOT or dev build has no release to compare against and
     * must never be offered an "upgrade" to a version that may be older than what it's running.
     */
    val isRelease: Boolean by lazy {
        val v = com.wikikt.service.SemVer.parse(version)
        v != null && v.prerelease.isEmpty() && builtAt != null
    }

    /**
     * Cache-busting token for `/static` asset URLs (`?v=` — appended by templates via the merged
     * Mustache model in Application.kt). Version plus the `builtAt` epoch that prod builds stamp
     * into wikikt.properties, so every prod build — even of the same -SNAPSHOT version — gets a
     * fresh token. Dev builds carry no stamp; falling back to JVM start time busts on every
     * restart, which is harmless because dev serves `/static` without cache headers.
     */
    val assetVersion: String by lazy {
        val stamp = props.getProperty("builtAt")
            ?: (ManagementFactory.getRuntimeMXBean().startTime / 1000).toString()
        "$version.$stamp"
    }
}
