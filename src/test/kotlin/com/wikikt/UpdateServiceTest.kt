package com.wikikt

import com.wikikt.config.DatabaseConfig
import com.wikikt.config.DatabaseConnectionConfig
import com.wikikt.config.DatabaseType
import com.wikikt.db.DatabaseFactory
import com.wikikt.service.MigrationService
import com.wikikt.service.SettingsService
import com.wikikt.service.SiteService
import com.wikikt.service.UpdateCheck
import com.wikikt.service.UpdateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateServiceTest {

    /** Fresh in-memory DB + settings with one catch-all site, mirroring SettingsServiceTest. */
    private suspend fun settingsEnv(name: String): SettingsService {
        val database = DatabaseFactory.connect(
            DatabaseConfig(
                type = DatabaseType.H2,
                connection = DatabaseConnectionConfig(
                    r2dbcUrl = "r2dbc:h2:mem:///wikikt-update-$name;DB_CLOSE_DELAY=-1",
                    username = "sa",
                    password = "",
                ),
            ),
        )
        MigrationService(database).migrate()
        SiteService(database).create("Test site", null, isCatchAll = true)
        return SettingsService(database)
    }

    /** Counting fetch fake: returns [body] (or throws when it's null) and records every call. */
    private class FakeFetch(@Volatile var body: String?) {
        @Volatile var calls = 0
        val fn: suspend (String) -> String = {
            calls++
            body ?: throw IllegalStateException("simulated network failure")
        }
    }

    private fun releaseJson(tag: String) =
        """{"tag_name":"$tag","html_url":"https://github.com/RMoRobert/WikiKT/releases/tag/$tag","published_at":"2026-07-01T00:00:00Z"}"""

    private fun service(
        settings: SettingsService,
        fetch: FakeFetch,
        current: String = "1.2.3",
        releaseBuild: Boolean = true,
        refreshScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        clock: () -> Long,
    ) = UpdateService(settings, current, releaseBuild, fetch.fn, clock, refreshScope)

    /** Waits out any background refresh [service] started, so assertions aren't racing it. */
    private suspend fun CoroutineScope.awaitRefreshes() =
        coroutineContext[Job]!!.children.toList().joinAll()

    @Test
    fun `dev build and missing opt-in perform zero fetches`() = runBlocking<Unit> {
        val settings = settingsEnv("gates")
        val fetch = FakeFetch(releaseJson("v9.9.9"))

        // Dev build: NotApplicable without I/O, even with consent on record.
        val dev = service(settings, fetch, current = "0.9.0-SNAPSHOT", releaseBuild = false, clock = { 0 })
        dev.setOptIn(true)
        assertIs<UpdateCheck.NotApplicable>(dev.check())

        // Never asked: NotEnabled (the consent card), without I/O.
        settings.set(settings.instanceAnchorSiteId(), SettingsService.UPDATE_CHECK_ENABLED, "")
        assertIs<UpdateCheck.NotEnabled>(service(settings, fetch, clock = { 0 }).check())

        // Explicitly declined: NotEnabled, without I/O. Consent is the only gate now, so this is
        // what an airgapped/no-egress install relies on to stay silent.
        settings.setBool(settings.instanceAnchorSiteId(), SettingsService.UPDATE_CHECK_ENABLED, false)
        assertIs<UpdateCheck.NotEnabled>(service(settings, fetch, clock = { 0 }).check())

        assertEquals(0, fetch.calls, "no gate state may touch the network")
    }

    @Test
    fun `caches within the TTL and refetches after it`() = runBlocking<Unit> {
        val settings = settingsEnv("ttl")
        val fetch = FakeFetch(releaseJson("v2.0.0"))
        var now = 1_000L
        val svc = service(settings, fetch, clock = { now })
        svc.setOptIn(true)

        val first = svc.check()
        assertIs<UpdateCheck.Available>(first)
        assertEquals("2.0.0", first.release.version.toString())
        assertIs<UpdateCheck.Available>(svc.check())
        assertEquals(1, fetch.calls, "second call inside the TTL must come from cache")

        now += UpdateService.SUCCESS_TTL_MS + 1
        assertIs<UpdateCheck.Available>(svc.check())
        assertEquals(2, fetch.calls, "stale cache refetches")
    }

    @Test
    fun `failures are cached with the shorter TTL and never throw`() = runBlocking<Unit> {
        val settings = settingsEnv("fail")
        val fetch = FakeFetch(null) // every fetch throws
        var now = 1_000L
        val svc = service(settings, fetch, clock = { now })
        svc.setOptIn(true)

        val failed = svc.check()
        assertIs<UpdateCheck.Failed>(failed)
        assertTrue("simulated" !in failed.message, "internal error detail must not reach the page")
        svc.check()
        assertEquals(1, fetch.calls, "failure is cached — a dead network must not block every page view")

        now += UpdateService.FAILURE_TTL_MS + 1
        svc.check()
        assertEquals(2, fetch.calls, "failure cache expires on its own (shorter) TTL")
    }

    @Test
    fun `malformed and unparseable responses degrade to UpToDate or Failed, never Available`() = runBlocking<Unit> {
        val settings = settingsEnv("garbage")
        var now = 0L
        val fetch = FakeFetch("this is not json")
        val svc = service(settings, fetch, clock = { now })
        svc.setOptIn(true)
        assertIs<UpdateCheck.Failed>(svc.check(), "garbage body -> Failed, no throw")

        // Valid JSON, unparseable tag: must be UpToDate — an unorderable version is never an update.
        now += UpdateService.FAILURE_TTL_MS + 1
        fetch.body = releaseJson("latest-and-greatest")
        assertIs<UpdateCheck.UpToDate>(svc.check())

        // Older release than the running build: also UpToDate.
        now += UpdateService.SUCCESS_TTL_MS + 1
        fetch.body = releaseJson("v1.0.0")
        assertIs<UpdateCheck.UpToDate>(svc.check())
    }

    @Test
    fun `force bypasses the TTL but is itself rate limited`() = runBlocking<Unit> {
        val settings = settingsEnv("force")
        val fetch = FakeFetch(releaseJson("v2.0.0"))
        var now = 1_000L
        val svc = service(settings, fetch, clock = { now })
        svc.setOptIn(true)

        svc.check()
        assertEquals(1, fetch.calls)

        now += 60_000 // well inside the success TTL
        svc.check(force = true)
        assertEquals(2, fetch.calls, "Check now bypasses the TTL")

        now += 60_000 // inside the force rate-limit window
        svc.check(force = true)
        assertEquals(2, fetch.calls, "a second Check now within the window is served from cache")

        now += UpdateService.FORCE_MIN_INTERVAL_MS
        svc.check(force = true)
        assertEquals(3, fetch.calls, "force works again after the rate-limit window")
    }

    @Test
    fun `opt-in is tri-state and instance-anchored`() = runBlocking<Unit> {
        val settings = settingsEnv("optin")
        val fetch = FakeFetch(releaseJson("v2.0.0"))
        val svc = service(settings, fetch, clock = { 0 })

        assertEquals(null, svc.optIn(), "fresh install: never asked")
        svc.setOptIn(true)
        assertEquals(true, svc.optIn())
        svc.setOptIn(false)
        assertEquals(false, svc.optIn())
        assertIs<UpdateCheck.NotEnabled>(svc.check())
        assertEquals(0, fetch.calls)
    }

    @Test
    fun `dashboard badge never fetches on the caller and refreshes for the next load`() = runBlocking<Unit> {
        val settings = settingsEnv("passive")
        val fetch = FakeFetch(releaseJson("v2.0.0"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var now = 1_000L
        val svc = service(settings, fetch, refreshScope = scope, clock = { now })

        // Not opted in: no badge, and crucially no refresh either — the consent promise covers the
        // dashboard as much as the Updates page.
        assertNull(svc.availableIfKnown())
        scope.awaitRefreshes()
        assertEquals(0, fetch.calls, "an un-consented instance must not check from the dashboard")

        svc.setOptIn(true)
        // First view with a cold cache: still no badge (the caller is never made to wait), but it
        // kicks off the refresh whose result the next view will show.
        assertNull(svc.availableIfKnown(), "cold cache renders nothing rather than blocking")
        scope.awaitRefreshes()
        assertEquals(1, fetch.calls)

        val badge = svc.availableIfKnown()
        assertIs<UpdateCheck.Available>(badge)
        assertEquals("2.0.0", badge.release.version.toString())
        scope.awaitRefreshes()
        assertEquals(1, fetch.calls, "a cache inside the passive window is reused, not refreshed")

        // Inside the weekly window but past the page's own 24 h TTL: still no new fetch from here.
        now += UpdateService.SUCCESS_TTL_MS + 1
        assertIs<UpdateCheck.Available>(svc.availableIfKnown())
        scope.awaitRefreshes()
        assertEquals(1, fetch.calls, "the dashboard refreshes weekly, not daily")

        // Past the weekly window: refreshed in the background, badge still served from the old cache.
        now += UpdateService.PASSIVE_REFRESH_MS
        fetch.body = releaseJson("v3.0.0")
        assertEquals("2.0.0", svc.availableIfKnown()?.release?.version?.toString())
        scope.awaitRefreshes()
        assertEquals(2, fetch.calls)
        assertEquals("3.0.0", svc.availableIfKnown()?.release?.version?.toString())
    }

    @Test
    fun `dashboard badge stays silent for dev builds and when up to date`() = runBlocking<Unit> {
        val settings = settingsEnv("passive-quiet")
        val fetch = FakeFetch(releaseJson("v9.9.9"))
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val dev = service(settings, fetch, current = "0.9.0-SNAPSHOT", releaseBuild = false, refreshScope = scope, clock = { 0 })
        dev.setOptIn(true)
        assertNull(dev.availableIfKnown())
        scope.awaitRefreshes()
        assertEquals(0, fetch.calls, "a dev build has no release to compare against")

        // Up to date: the cache holds UpToDate, which must not be mistaken for a badge.
        fetch.body = releaseJson("v1.0.0")
        val svc = service(settings, fetch, refreshScope = scope, clock = { 0 })
        assertIs<UpdateCheck.UpToDate>(svc.check())
        assertNull(svc.availableIfKnown(), "only Available renders a badge")
    }
}
