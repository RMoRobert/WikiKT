package com.wikikt

import java.util.Properties

/** Build-time metadata baked into a classpath resource by Gradle's processResources. */
object BuildInfo {
    /** The project version (e.g. "1.0.0-SNAPSHOT"), or "dev" if the resource is absent. */
    val version: String by lazy {
        BuildInfo::class.java.getResourceAsStream("/wikikt.properties")?.use { stream ->
            Properties().apply { load(stream) }.getProperty("version")
        } ?: "dev"
    }
}
