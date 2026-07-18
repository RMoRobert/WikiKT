import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.wikikt"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}
dependencies {
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.auth)
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

// Bake the project version into a classpath resource so it's readable at runtime (dev + jar).
tasks.processResources {
    filesMatching("wikikt.properties") {
        expand("version" to project.version)
    }
}
