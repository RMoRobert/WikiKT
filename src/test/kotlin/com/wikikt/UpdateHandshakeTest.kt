package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.config.SelfUpdateDirsConfig
import com.wikikt.db.DatabaseFactory
import com.wikikt.service.InstallRequestOutcome
import com.wikikt.service.MigrationService
import com.wikikt.service.SelfUpdateService
import com.wikikt.service.SettingsService
import com.wikikt.service.SiteService
import com.wikikt.service.UpdaterPresence
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app side of the self-update file handshake (SelfUpdateService <-> docker/updater/updater.sh).
 * Everything read from the shared volumes must be treated as hostile: size-capped, parse-tolerant,
 * and degrading to "unknown" — an admin page render must survive any file contents.
 */
class UpdateHandshakeTest {

    private class Env(name: String) {
        val requestDir: Path = Files.createTempDirectory("wikikt-req-$name")
        val stateDir: Path = Files.createTempDirectory("wikikt-state-$name")
        var now: Long = 10_000_000_000_000 // fixed "current time" (millis), advanced by tests
        val service: SelfUpdateService

        init {
            val database = runBlocking {
                DatabaseFactory.connect(
                    DatabaseConfig(
                        type = DatabaseType.H2,
                        connection = DatabaseConnectionConfig(
                            r2dbcUrl = "r2dbc:h2:mem:///wikikt-handshake-$name;DB_CLOSE_DELAY=-1",
                            username = "sa",
                            password = "",
                        ),
                    ),
                )
            }
            runBlocking {
                MigrationService(database).migrate()
                SiteService(database).create("Test site", null, isCatchAll = true)
            }
            service = SelfUpdateService(
                SelfUpdateDirsConfig(requestDir, stateDir),
                SettingsService(database),
                clock = { now },
            )
        }

        fun writeHeartbeat(beatAt: Long = now, protocol: Int = SelfUpdateService.PROTOCOL) {
            Files.writeString(
                stateDir.resolve("updater.json"),
                """{"schema":1,"protocol":$protocol,"beatAt":$beatAt,"composeProject":"wikikt",
                   "targetService":"wikikt","capabilities":["update"],"runningComposeRevision":1}""",
            )
        }

        fun writeStatus(phase: String, terminal: Boolean, updatedAt: Long = now, requestId: String = "r1") {
            Files.writeString(
                stateDir.resolve("status.json"),
                """{"schema":1,"requestId":"$requestId","phase":"$phase","terminal":$terminal,
                   "startedAt":${updatedAt - 60_000},"updatedAt":$updatedAt,
                   "fromVersion":"1.2.3","toVersion":"1.3.0","message":"msg","logTail":["a","b"]}""",
            )
        }

        /** The requestId of the request the app last wrote — the real updater echoes it into status.json. */
        fun writtenRequestId(): String =
            Regex("\"requestId\":\"([0-9a-f-]{36})\"").find(Files.readString(requestDir.resolve("request.json")))!!.groupValues[1]
    }

    @Test
    fun `presence is absent, then stale, then available, by heartbeat freshness`() = runBlocking<Unit> {
        val env = Env("presence")
        assertIs<UpdaterPresence.NotInstalled>(env.service.presence(), "no heartbeat file yet")

        env.writeHeartbeat()
        val available = env.service.presence()
        assertIs<UpdaterPresence.Available>(available)
        assertEquals(1, available.heartbeat.runningComposeRevision)

        env.now += SelfUpdateService.HEARTBEAT_FRESH_MS + 1
        assertIs<UpdaterPresence.Stale>(env.service.presence(), "old heartbeat = updater stopped")

        // A future protocol we don't speak is stale (page says "check the updater"), never Available.
        env.writeHeartbeat(beatAt = env.now, protocol = SelfUpdateService.PROTOCOL + 1)
        assertIs<UpdaterPresence.Stale>(env.service.presence())
    }

    @Test
    fun `status reads are defensive - garbage and oversized files degrade to null`() = runBlocking<Unit> {
        val env = Env("defense")
        assertNull(env.service.status(), "no file")

        Files.writeString(env.stateDir.resolve("status.json"), "not json at all {{{")
        assertNull(env.service.status(), "garbage degrades, never throws")

        Files.writeString(env.stateDir.resolve("status.json"), "x".repeat((SelfUpdateService.MAX_FILE_BYTES + 1).toInt()))
        assertNull(env.service.status(), "oversized file is refused, not buffered")

        env.writeStatus("verifying", terminal = false)
        val status = env.service.status()
        assertNotNull(status)
        assertTrue(env.service.isRunning(status), "fresh non-terminal = running")
        assertFalse(env.service.isAbandoned(status))

        env.now += SelfUpdateService.STATUS_STALE_MS + 1
        assertFalse(env.service.isRunning(env.service.status()), "stale non-terminal is not running")
        assertTrue(env.service.isAbandoned(env.service.status()), "it is abandoned - page shows unknown")
    }

    @Test
    fun `requestInstall writes one valid request and respects the gates`() = runBlocking<Unit> {
        val env = Env("request")

        // No heartbeat: refused, nothing written.
        assertEquals(InstallRequestOutcome.UPDATER_NOT_AVAILABLE, env.service.requestInstall("rob", "1.2.3", "1.3.0"))
        assertFalse(Files.exists(env.requestDir.resolve("request.json")))

        env.writeHeartbeat()
        assertEquals(InstallRequestOutcome.REQUESTED, env.service.requestInstall("rob", "1.2.3", "1.3.0"))
        val file = env.requestDir.resolve("request.json")
        assertTrue(Files.exists(file))
        assertTrue(Files.size(file) <= 4096, "request must fit the updater's size gate")
        val text = Files.readString(file)
        // Shape the shell side validates: schema 1, uuid requestId, millis requestedAt.
        assertTrue(Regex("\"requestId\":\"[0-9a-f-]{36}\"").containsMatchIn(text), text)
        assertTrue("\"schema\":1" in text, text)
        assertTrue("\"requestedBy\":\"rob\"" in text, text)
        assertTrue("\"requestedAt\":${env.now}" in text, "timestamps are epoch millis: $text")

        // Freshly written and not yet picked up: a second click must not overwrite it.
        assertEquals(InstallRequestOutcome.ALREADY_REQUESTED, env.service.requestInstall("rob", "1.2.3", "1.3.0"))

        // While the updater reports a run in flight, a second request is refused (UX guard; the
        // updater's lock + replay protection are the real guarantee).
        val requestId = env.writtenRequestId()
        env.writeStatus("pulling", terminal = false, requestId = requestId)
        assertEquals(InstallRequestOutcome.ALREADY_RUNNING, env.service.requestInstall("rob", "1.2.3", "1.3.0"))

        // Terminal status: a new request may be written again (and atomically replaces the old file).
        env.writeStatus("success", terminal = true, requestId = requestId)
        assertEquals(InstallRequestOutcome.REQUESTED, env.service.requestInstall("rob", "1.3.0", "1.4.0"))
    }

    @Test
    fun `pending tracks the click through pickup, and goes unclaimed if the updater never comes`() = runBlocking<Unit> {
        val env = Env("pending")
        assertNull(env.service.pending(env.service.status()), "no request file, nothing pending")

        env.writeHeartbeat()
        assertEquals(InstallRequestOutcome.REQUESTED, env.service.requestInstall("rob", "1.2.3", "1.3.0"))

        // Immediately after the click (this is what the PRG redirect renders): Waiting, with the
        // audit line the page shows.
        val waiting = env.service.pending(env.service.status())
        assertIs<com.wikikt.service.PendingInstall.Waiting>(waiting)
        assertEquals("rob", waiting.requestedBy)

        // The updater picks it up (echoes the requestId): pending yields to the running status card.
        val requestId = env.writtenRequestId()
        env.writeStatus("backing-up", terminal = false, requestId = requestId)
        val status = env.service.status()
        assertNull(env.service.pending(status), "acknowledged request is no longer pending")
        assertTrue(env.service.isRunning(status))

        // Alternate ending: never picked up. Past the pickup window it turns into a warning...
        env.writeHeartbeat() // fresh heartbeat, so requestInstall's presence gate passes below
        Files.deleteIfExists(env.stateDir.resolve("status.json"))
        env.now += SelfUpdateService.PENDING_PICKUP_MS + 1
        assertIs<com.wikikt.service.PendingInstall.Unclaimed>(env.service.pending(env.service.status()))
        // ...which may be overwritten by a fresh request (that is the recovery path), unlike Waiting.
        assertEquals(InstallRequestOutcome.REQUESTED, env.service.requestInstall("rob", "1.2.3", "1.3.0"))

        // And a request old enough to predate the stale window is ignored entirely.
        env.now += SelfUpdateService.STATUS_STALE_MS + 1
        assertNull(env.service.pending(env.service.status()), "ancient leftover request is not alarming")
    }

    @Test
    fun `a finished outcome can be dismissed for good, and the next one still shows`() = runBlocking<Unit> {
        val env = Env("dismiss")
        assertFalse(env.service.isOutcomeDismissed(env.service.status()), "no outcome yet")

        // A run in flight is not an outcome: dismissing does nothing (and must not pre-hide its result).
        env.writeStatus("verifying", terminal = false)
        env.service.dismissOutcome()
        assertFalse(env.service.isOutcomeDismissed(env.service.status()))

        env.writeStatus("success", terminal = true, requestId = "r1")
        assertFalse(env.service.isOutcomeDismissed(env.service.status()), "shown until acknowledged")
        env.service.dismissOutcome()
        assertTrue(env.service.isOutcomeDismissed(env.service.status()), "and stays hidden across reloads")
        assertEquals("success", env.service.status()?.phase, "status.json is untouched; only the UI hides it")

        // The next update is a different run, so its result shows unread.
        env.writeStatus("failed", terminal = true, requestId = "r2")
        assertFalse(env.service.isOutcomeDismissed(env.service.status()))

        // Without a requestId (an updater-initiated run) the finish time identifies the outcome, so
        // dismissing one still can't silence a later one.
        env.writeStatus("success", terminal = true, requestId = "")
        env.service.dismissOutcome()
        assertTrue(env.service.isOutcomeDismissed(env.service.status()))
        env.now += 60_000
        env.writeStatus("success", terminal = true, requestId = "")
        assertFalse(env.service.isOutcomeDismissed(env.service.status()))
    }

    @Test
    fun `unconfigured service is inert`() = runBlocking<Unit> {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-handshake-off;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        SiteService(database).create("Test site", null, isCatchAll = true)
        val service = SelfUpdateService(null, SettingsService(database))
        assertFalse(service.configured)
        assertIs<UpdaterPresence.NotInstalled>(service.presence())
        assertNull(service.status())
        assertEquals(InstallRequestOutcome.UPDATER_NOT_AVAILABLE, service.requestInstall("rob", "1.2.3", "1.3.0"))
        service.dismissOutcome() // no status to dismiss: a no-op, not a stored ghost
        assertFalse(service.isOutcomeDismissed(service.status()))
    }
}
