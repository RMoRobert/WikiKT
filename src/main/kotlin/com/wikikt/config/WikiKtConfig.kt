package com.wikikt.config

import com.wikikt.auth.PasswordPolicy
import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.wikikt.config")

const val DEFAULT_ADMIN_PASSWORD = "changeme"

/**
 * Read a setting with env-var precedence: an explicitly set `WIKIKT_*` env var wins over the value
 * shipped in application.yaml, which in turn wins over the caller-supplied default. A blank string
 * from either source counts as unset. This is the container-config contract — the yaml carries
 * documented defaults and a deployment overrides them purely through the environment.
 *
 * Reading the yaml *first* (the historical bug) let a shipped literal like `false` or `604800`
 * silently shadow its env var, so `WIKIKT_*` overrides appeared to do nothing. Every env-backed
 * setting goes through here so that whole class of bug cannot recur.
 */
internal fun ApplicationConfig.envOrConfig(
    yamlKey: String,
    envVar: String,
    getEnv: (String) -> String? = System::getenv,
): String? =
    getEnv(envVar)?.ifBlank { null }
        ?: propertyOrNull(yamlKey)?.getString()?.ifBlank { null }

/**
 * Whether the app is running in a production deployment, controlled by `WIKIKT_ENV`
 * (or `wikikt.environment`). Defaults to development. In production, insecure defaults
 * (built-in session keys, the default admin password) are fatal rather than merely warned.
 */
fun ApplicationConfig.isProductionEnvironment(): Boolean {
    val env = envOrConfig("wikikt.environment", "WIKIKT_ENV") ?: "development"
    return env.trim().lowercase() in setOf("prod", "production")
}

data class WikiKtConfig(
    val defaultLocale: String,
    val defaultAdmin: DefaultAdminConfig,
    val database: DatabaseConfig,
    val session: SessionConfig,
    val assets: AssetConfig,
    val ui: UiConfig,
    val gitSync: GitSyncDirConfig,
    /**
     * Canonical public base URL (e.g. `https://wiki.example`), if configured via `wikikt.server.publicUrl`
     * / `WIKIKT_PUBLIC_URL`. Used to build links placed into outbound email (password reset, welcome) so
     * they never derive their host from the client-supplied `Host` header — which would otherwise allow
     * reset-link poisoning. Null = not set; link building then prefers a configured site hostname before
     * ever falling back to the request host.
     */
    val publicUrl: String?,
    /**
     * Minimum length for user-chosen passwords (self-registration, reset, self-service change, and
     * admin-set), configurable via `wikikt.security.minPasswordLength` / `WIKIKT_MIN_PASSWORD_LENGTH`.
     * Defaults to [PasswordPolicy.DEFAULT_MIN_LENGTH]; raise it (e.g. to 8+) on a public-facing deployment.
     * Coerced to 1..[PasswordPolicy.MAX_BYTES] so it can never exceed the bcrypt byte cap (which would
     * otherwise leave no password able to satisfy both bounds).
     */
    val minPasswordLength: Int,
    /**
     * AES key used to encrypt stored MFA (TOTP) secrets at rest, from `wikikt.security.mfaKey` /
     * `WIKIKT_MFA_KEY` (hex, decoding to 16/24/32 bytes). A dedicated key — separate from the session
     * keys — so the two concerns don't share key material. Required (fatal) in production, like the
     * session keys; development falls back to a built-in key with a warning.
     */
    val mfaEncryptionKey: ByteArray,
)

/** Where the git-sync working clone lives. The sync settings themselves (repo URL, mode, auth)
 *  are runtime-editable app settings (`gitSync.*` in SettingsService), not static config. */
data class GitSyncDirConfig(
    /** Absolute, normalized directory holding the local clone (created on first sync). */
    val dir: java.nio.file.Path,
)

/** Front-end asset delivery. [useCdnAssets] picks the CDN copy of Bootstrap et al. over the bundled
 *  local files under `static/vendor/`. Defaults to local so a fresh install has no external dependency. */
data class UiConfig(
    val useCdnAssets: Boolean,
)

/** Supported (decodable + validatable) asset MIME types. The effective allowlist is this ∩ config. */
val SUPPORTED_ASSET_MIME_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

data class AssetConfig(
    /** Absolute, normalized directory where uploaded bytes live (plus a `tmp/` subdir). */
    val storageDir: java.nio.file.Path,
    val maxUploadSizeBytes: Long,
    val maxFilesPerUpload: Int,
    /** Effective allowlist: configured types intersected with SUPPORTED_ASSET_MIME_TYPES. */
    val allowedMimeTypes: Set<String>,
    /** When true, a request for (locale, path) falls back to the default locale if absent. */
    val localeFallback: Boolean,
    /** How many prior versions to keep when an asset's file is replaced (older ones are pruned). */
    val maxAssetVersions: Int,
)

data class DefaultAdminConfig(
    val username: String,
    val password: String,
)

fun Application.loadWikiKtConfig(): WikiKtConfig {
    val adminPassword = environment.config.envOrConfig("wikikt.defaultAdmin.password", "WIKIKT_ADMIN_PASSWORD")
        ?: DEFAULT_ADMIN_PASSWORD

    if (adminPassword == DEFAULT_ADMIN_PASSWORD) {
        val message = "The default admin password ('changeme') is in use. Set " +
            "wikikt.defaultAdmin.password or WIKIKT_ADMIN_PASSWORD."
        if (environment.config.isProductionEnvironment()) {
            throw IllegalStateException("Refusing to start in production: $message")
        }
        logger.warn(
            "\n" +
                "************************************************************************\n" +
                "* SECURITY WARNING: the default admin password ('changeme') is in use. *\n" +
                "* Set wikikt.defaultAdmin.password or WIKIKT_ADMIN_PASSWORD before  *\n" +
                "* exposing this instance. Anyone can log in as the administrator.       *\n" +
                "************************************************************************",
        )
    }

    return WikiKtConfig(
        defaultLocale = environment.config.property("wikikt.defaultLocale").getString(),
        defaultAdmin = DefaultAdminConfig(
            username = environment.config.property("wikikt.defaultAdmin.username").getString(),
            password = adminPassword,
        ),
        database = environment.config.loadDatabaseConfig(),
        session = environment.config.loadSessionConfig(),
        assets = environment.config.loadAssetConfig(),
        ui = environment.config.loadUiConfig(),
        gitSync = environment.config.loadGitSyncDirConfig(),
        publicUrl = environment.config.envOrConfig("wikikt.server.publicUrl", "WIKIKT_PUBLIC_URL")?.trim()?.ifBlank { null },
        minPasswordLength = environment.config.envOrConfig("wikikt.security.minPasswordLength", "WIKIKT_MIN_PASSWORD_LENGTH")
            ?.toIntOrNull()?.coerceIn(1, PasswordPolicy.MAX_BYTES) ?: PasswordPolicy.DEFAULT_MIN_LENGTH,
        mfaEncryptionKey = environment.config.loadMfaKey(),
    )
}

// Dev-only fallback so MFA works locally without configuring a key; never used in production (fatal there).
private val DEV_MFA_KEY = "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef".decodeHex()

/**
 * The dedicated AES key for encrypting stored MFA (TOTP) secrets. Required in production; falls back to a
 * built-in development key (with a warning) otherwise, mirroring the session-key handling.
 */
internal fun ApplicationConfig.loadMfaKey(): ByteArray {
    val configured = envOrConfig("wikikt.security.mfaKey", "WIKIKT_MFA_KEY")
    if (configured == null) {
        val message = "MFA encryption key is not configured. Set wikikt.security.mfaKey " +
            "(or WIKIKT_MFA_KEY) to a random 32-byte hex value; it encrypts stored TOTP secrets."
        if (isProductionEnvironment()) {
            throw IllegalStateException("Refusing to start in production: $message")
        }
        logger.warn("$message Using a built-in DEVELOPMENT key for now.")
        return DEV_MFA_KEY
    }
    val key = try {
        configured.trim().decodeHex()
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("wikikt.security.mfaKey must be a hex string", e)
    }
    require(key.size == 16 || key.size == 24 || key.size == 32) {
        "wikikt.security.mfaKey must decode to 16, 24, or 32 bytes (AES key); got ${key.size}"
    }
    return key
}

internal fun ApplicationConfig.loadGitSyncDirConfig(): GitSyncDirConfig {
    val dir = envOrConfig("wikikt.gitSync.dir", "WIKIKT_GIT_SYNC_DIR") ?: "./data/git-sync"
    return GitSyncDirConfig(dir = java.nio.file.Path.of(dir).toAbsolutePath().normalize())
}

internal fun ApplicationConfig.loadUiConfig(): UiConfig {
    // "local" (default) serves bundled assets from static/vendor/; "cdn" uses jsdelivr.
    val source = (envOrConfig("wikikt.ui.assetSource", "WIKIKT_UI_ASSET_SOURCE") ?: "local").trim().lowercase()
    return UiConfig(useCdnAssets = source == "cdn")
}

internal fun ApplicationConfig.loadAssetConfig(): AssetConfig {
    val dir = envOrConfig("wikikt.assets.storageDir", "WIKIKT_ASSET_STORAGE_DIR") ?: "./data/uploads"
    val maxSize = envOrConfig("wikikt.assets.maxUploadSizeBytes", "WIKIKT_MAX_UPLOAD_SIZE_BYTES")
        ?.toLongOrNull()?.takeIf { it > 0 } ?: (5L * 1024 * 1024)
    val maxFiles = envOrConfig("wikikt.assets.maxFilesPerUpload", "WIKIKT_MAX_FILES_PER_UPLOAD")
        ?.toIntOrNull()?.takeIf { it in 1..1000 } ?: 10

    val configuredTypes = envOrConfig("wikikt.assets.allowedMimeTypes", "WIKIKT_ALLOWED_MIME_TYPES")
        ?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }?.toSet()
        ?: SUPPORTED_ASSET_MIME_TYPES
    val unsupported = configuredTypes - SUPPORTED_ASSET_MIME_TYPES
    if (unsupported.isNotEmpty()) {
        logger.warn("Ignoring unsupported asset MIME types (no safe validator): ${unsupported.joinToString(", ")}")
    }
    val effective = configuredTypes.intersect(SUPPORTED_ASSET_MIME_TYPES).ifEmpty { SUPPORTED_ASSET_MIME_TYPES }

    val fallback = envOrConfig("wikikt.assets.localeFallback", "WIKIKT_ASSET_LOCALE_FALLBACK")
        ?.toBooleanStrictOrNull() ?: true

    // Fallback default for the per-site "asset history" setting when it hasn't been set in the UI.
    // Low by default because asset revisions store binary bytes; page history defaults higher.
    val maxVersions = envOrConfig("wikikt.assets.maxAssetVersions", "WIKIKT_MAX_ASSET_VERSIONS")
        ?.toIntOrNull()?.coerceIn(1, 50) ?: 3

    val storageDir = java.nio.file.Path.of(dir).toAbsolutePath().normalize()
    logger.info("Asset storage directory: $storageDir (max ${maxSize / 1024 / 1024}MB, $maxFiles files/upload)")

    return AssetConfig(
        storageDir = storageDir,
        maxUploadSizeBytes = maxSize,
        maxFilesPerUpload = maxFiles,
        allowedMimeTypes = effective,
        localeFallback = fallback,
        maxAssetVersions = maxVersions,
    )
}

internal fun ApplicationConfig.loadDatabaseConfig(): DatabaseConfig {
    val type = envOrConfig("wikikt.database.type", "WIKIKT_DATABASE_TYPE") ?: "h2"

    val databaseType = DatabaseType.fromConfig(type)
    val prefix = when (databaseType) {
        DatabaseType.H2 -> "wikikt.database.h2"
        DatabaseType.POSTGRES -> "wikikt.database.postgres"
    }

    // Env vars win over the yaml (as everywhere — see envOrConfig): the yaml ships localhost
    // defaults, so a container deployment points at its database purely through the environment
    // (e.g. WIKIKT_DATABASE_R2DBC_URL=r2dbc:postgresql://postgres:5432/wikikt).
    val defaults = DatabasePoolConfig()
    return DatabaseConfig(
        type = databaseType,
        connection = DatabaseConnectionConfig(
            r2dbcUrl = System.getenv("WIKIKT_DATABASE_R2DBC_URL")?.ifBlank { null }
                ?: property("$prefix.r2dbcUrl").getString(),
            username = System.getenv("WIKIKT_DATABASE_USERNAME")?.ifBlank { null }
                ?: property("$prefix.username").getString(),
            password = System.getenv("WIKIKT_DATABASE_PASSWORD")?.ifBlank { null }
                ?: propertyOrNull("$prefix.password")?.getString()
                ?: "",
        ),
        // Pool sizing is optional everywhere: an absent/garbage value falls back to the safe default,
        // so an existing deployment picks up sensible pooling without touching its config.
        pool = DatabasePoolConfig(
            maxSize = poolInt("wikikt.database.pool.maxSize", "WIKIKT_DATABASE_POOL_MAX_SIZE", defaults.maxSize),
            initialSize = poolInt("wikikt.database.pool.initialSize", "WIKIKT_DATABASE_POOL_INITIAL_SIZE", defaults.initialSize),
            maxIdleTimeSeconds = poolInt(
                "wikikt.database.pool.maxIdleTimeSeconds",
                "WIKIKT_DATABASE_POOL_MAX_IDLE_TIME",
                defaults.maxIdleTimeSeconds.toInt(),
            ).toLong(),
            maxLifeTimeSeconds = poolInt(
                "wikikt.database.pool.maxLifeTimeSeconds",
                "WIKIKT_DATABASE_POOL_MAX_LIFE_TIME",
                defaults.maxLifeTimeSeconds.toInt(),
            ).toLong(),
            maxAcquireTimeSeconds = poolInt(
                "wikikt.database.pool.maxAcquireTimeSeconds",
                "WIKIKT_DATABASE_POOL_MAX_ACQUIRE_TIME",
                defaults.maxAcquireTimeSeconds.toInt(),
            ).toLong(),
        ),
    )
}

/** A positive pool integer from env-or-yaml, falling back to [default] when unset or not a valid number. */
private fun ApplicationConfig.poolInt(yamlKey: String, envVar: String, default: Int): Int =
    envOrConfig(yamlKey, envVar)?.toIntOrNull()?.takeIf { it > 0 } ?: default
