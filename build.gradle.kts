import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.google.javascript.jscomp.CompilationLevel
import com.google.javascript.jscomp.CompilerOptions
import com.google.javascript.jscomp.SourceFile
import com.google.javascript.jscomp.WarningLevel

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Build-only (never in the app's runtime classpath): real JS parser used to minify first-party
        // static/*.js for the production jar. A parser, not regex, is required for JS — `/` is ambiguous
        // (division vs regex literal) and newlines are semantic (automatic semicolon insertion).
        classpath("com.google.javascript:closure-compiler:v20260720")
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.wikikt"

// The version baked into wikikt.properties and shown in the admin console (and recorded in backups).
// Overridable so release builds can be stamped with the git tag rather than this literal: the publish
// workflow passes the tag through the Dockerfile's WIKIKT_VERSION build arg, which becomes
// -PwikiktVersion here. Untagged and local builds fall back to the value below, so `./gradlew run`
// keeps reporting -SNAPSHOT. Bump this when cutting a release so source builds match the tag too.
version = providers.gradleProperty("wikiktVersion")
    .orElse(providers.environmentVariable("WIKIKT_VERSION"))
    .getOrElse("0.9.6-SNAPSHOT")

// The commit the build was made from, kept separate from `version` (BuildInfo.assetVersion embeds the
// version in `?v=` URL query strings, so the version must stay a clean X.Y.Z[-suffix]). Same override
// chain as the version: the publish workflow passes the commit through the Dockerfile's
// WIKIKT_GIT_SHA build arg (the Docker build context has no .git/, so it can't be read there); local
// builds read it from git; anything else reports "unknown".
val wikiktGitSha: String = providers.gradleProperty("wikiktGitSha")
    .orElse(providers.environmentVariable("WIKIKT_GIT_SHA"))
    .orNull?.trim()?.ifBlank { null }
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short=12", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrNull()?.ifBlank { null }
    ?: "unknown"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.compression)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.forwardedHeader)
    implementation(ktorLibs.server.mustache)
    implementation(ktorLibs.server.netty)
    implementation(ktorLibs.server.sessions)
    implementation(ktorLibs.server.statusPages)
    implementation(libs.angus.mail)
    implementation(libs.bcrypt)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.footnotes)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.commonmark.gfm.strikethrough)
    implementation(libs.commonmark.gfm.tables)
    implementation(libs.exposed.core)
    implementation(libs.exposed.r2dbc)
    implementation(libs.exposed.migration.core)
    implementation(libs.exposed.migration.r2dbc)
    implementation(libs.h2database.h2)
    implementation(libs.h2database.r2dbc)
    implementation(libs.jsoup)
    implementation(libs.logback.classic)
    implementation(libs.postgres.r2dbc)
    implementation(libs.qrcode.kotlin)
    implementation(libs.r2dbc.pool)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)
}

// R2DBC drivers (H2 + Postgres) each register via META-INF/services/io.r2dbc.spi.ConnectionFactoryProvider.
// In the fat jar those same-path files collide; the runtime otherwise sees a single driver
// ("Available drivers: [ h2 ]") and Postgres connections fail with a ConnectionFactory error.
// mergeServiceFiles() concatenates them, but the Ktor plugin sets the shadowJar duplicatesStrategy to
// EXCLUDE, which drops the second file before the merge transformer sees it — so INCLUDE is required for
// the merge to actually receive both. Configured in afterEvaluate to run after the Ktor plugin's setup.
afterEvaluate {
    tasks.named<ShadowJar>("shadowJar") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()
    }
}

val postgresConfig = layout.projectDirectory.file("src/main/resources/application-postgres.yaml")

tasks.named<JavaExec>("run") {
    if (project.findProperty("wikikt.db") == "postgres") {
        args("-config=${postgresConfig.asFile.absolutePath}")
    }
}

tasks.register<JavaExec>("runPostgres") {
    group = "application"
    description = "Run the server using application-postgres.yaml"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    args("-config=${postgresConfig.asFile.absolutePath}")
}

tasks.register<Exec>("databaseInstance") {
    group = "database"
    description = "Start PostgreSQL in Docker (docker compose up -d)"
    commandLine("docker", "compose", "up", "-d")
    workingDir = layout.projectDirectory.asFile
    isIgnoreExitValue = true
}

// Basic CSS minification to avoid a non-Kotlin dependency and because those I found don't seem to handle modern CSS:
// Collapse whitespace runs and drop /* */ comments. Everything where css-syntax-3
// makes bytes significant is matched first and unchanged. Alternatives, in order:
//   \26⎵-style hex escapes WITH their optional whitespace terminator (space is part of the escape,
//     so the run after it must not be collapsed into it); then any other \-escape (so an escaped quote
//     in a selector can't falsely open a string); quoted strings (internal spaces and /* are literal);
//     unquoted url() tokens (treat /* inside a URL as content, not comment); then comments and whitespace,
//     each collapsing to a single space. A space (not deletion) is required: comments are token
//     separators, so `foo/**/bar` must stay two tokens.
// Collapsed but not fully delete whitespace -- e.g., calc() spacing and Selectors-L4 :is()/:has() are untouched.
val cssToken = Regex(
    """\\[0-9a-fA-F]{1,6}\s?|\\.|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[uU][rR][lL]\(\s*(?:\\.|[^"'()\\\s])*\s*\)|\s*/\*.*?\*/\s*|\s+""",
    RegexOption.DOT_MATCHES_ALL,
)
fun minifyCss(css: String): String =
    cssToken.replace(css) { m ->
        val c = m.value.first()
        if (c == '/' || c.isWhitespace()) " " else m.value
    }.trim()

// JS minify via Closure Compiler (actual parser, unlike my CSS). SIMPLE renames local
// variables and drops comments/whitespace but doesn't touch globals or property names, so scripts
// keep working with inline handlers and each other. No transpilation: syntax passes through as-is.
// A parse error fails the build (better to catch now than failing in deployment!).
fun minifyJs(name: String, code: String): String {
    val compiler = com.google.javascript.jscomp.Compiler()
    val options = CompilerOptions().apply {
        setLanguageIn(CompilerOptions.LanguageMode.ECMASCRIPT_NEXT)
        setLanguageOut(CompilerOptions.LanguageMode.NO_TRANSPILE)
        // Don't prepend 'use strict' -- sources haven't been verified to not use Sloppy Mode
        setEmitUseStrict(false)
    }
    CompilationLevel.SIMPLE_OPTIMIZATIONS.setOptionsForCompilationLevel(options)
    WarningLevel.QUIET.setOptionsForWarningLevel(options)
    val result = compiler.compile(emptyList<SourceFile>(), listOf(SourceFile.fromCode(name, code)), options)
    if (!result.success) {
        throw GradleException("Closure Compiler failed on $name:\n${result.errors.joinToString("\n")}")
    }
    return compiler.toSource()
}

// True only when the self-contained production jar is being built (shadowJar, which the Dockerfile
// runs, or ktor's buildFatJar that also depends on it). Resolved once the task graph is known, so it never
// trips for `./gradlew run`.
// Dev opt-in: `./gradlew run -Pwikikt.minify` serves the minified CSS/JS from the normal dev server —
// same H2 database, default admin, and dev session keys — to rule minification in or out when
// debugging. The flag is part of the task's inputs, so a following plain `run` re-copies the readable
// sources automatically.
val devMinify = providers.gradleProperty("wikikt.minify").isPresent
val minifyProdAssets = objects.property(Boolean::class.java).convention(false)
gradle.taskGraph.whenReady {
    minifyProdAssets.set(hasTask(":shadowJar") || devMinify)
}

tasks.processResources {
    // Bake the project version into a classpath resource so it's readable at runtime (dev + jar).
    // Recorded as a task input because expand() values are invisible to up-to-date checks: without it,
    // changing the version alone leaves this task UP-TO-DATE and bakes the *previous* version into the
    // jar (and so into the admin console and backup metadata).
    inputs.property("wikiktVersion", project.version.toString())
    inputs.property("wikiktGitSha", wikiktGitSha)
    filesMatching("wikikt.properties") {
        expand("version" to project.version, "gitSha" to wikiktGitSha)
    }

    // Minifiy static/site.css and first-party static JS if building prod JAR; keep as-is for dev for
    // ease. vendor/ and *.min.js are already minified upstream and are skipped.
    // Recorded as a task input so switching between a prod build and `run` re-copies the right variant
    // instead of leaving a stale minified file in build/resources/main.
    inputs.property("minifyProdAssets", minifyProdAssets)
    doLast {
        if (!minifyProdAssets.get()) return@doLast
        val staticDir = sourceSets.main.get().output.resourcesDir!!.resolve("static")

        val css = staticDir.resolve("site.css")
        val cssOriginal = css.readText()
        css.writeText(minifyCss(cssOriginal))
        logger.lifecycle("Minified static/site.css: ${cssOriginal.length} -> ${css.length()} bytes")

        var jsBefore = 0L
        var jsAfter = 0L
        staticDir.walkTopDown()
            .filter { it.isFile && it.extension == "js" }
            .filterNot { it.name.endsWith(".min.js") || it.relativeTo(staticDir).path.startsWith("vendor") }
            .forEach { f ->
                val original = f.readText()
                f.writeText(minifyJs(f.name, original))
                jsBefore += original.length
                jsAfter += f.length()
            }
        logger.lifecycle("Minified first-party static JS: $jsBefore -> $jsAfter bytes")

        // Stamp the build time into wikikt.properties (prod JARs only). BuildInfo.assetVersion appends
        // it to the version for the ?v= cache-busting token on /static URLs, so two prod builds of the
        // same -SNAPSHOT version still bust caches. Living in this doLast keeps dev/test inputs stable
        // (no per-build churn), and an UP-TO-DATE shadowJar keeps its previous stamp. Stamp only
        // changes when the outputs actually rebuild.
        val props = sourceSets.main.get().output.resourcesDir!!.resolve("wikikt.properties")
        props.appendText("\nbuiltAt=${System.currentTimeMillis() / 1000}\n")
    }
}
