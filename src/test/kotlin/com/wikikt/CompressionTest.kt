package com.wikikt

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompressionTest {
    @Test
    fun `HTML is gzipped when the client asks and left identity when it doesn't`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "wikikt.defaultLocale" to "en",
                "wikikt.defaultAdmin.username" to "admin",
                "wikikt.defaultAdmin.password" to "test",
                "wikikt.database.type" to "h2",
                "wikikt.database.h2.r2dbcUrl" to "r2dbc:h2:mem:///wikikt-compression-test;DB_CLOSE_DELAY=-1",
                "wikikt.database.h2.username" to "sa",
                "wikikt.database.h2.password" to "",
            )
        }
        application { configure() }

        // A gzip-capable client gets compressed HTML (the login page is comfortably over minimumSize).
        val compressed = client.get("/login") { header("Accept-Encoding", "gzip") }
        assertEquals(HttpStatusCode.OK, compressed.status)
        assertEquals("gzip", compressed.headers["Content-Encoding"], "HTML compresses when negotiated")

        // No Accept-Encoding -> identity. This is what the bundled Caddy stack relies on: it strips
        // the client's Accept-Encoding upstream (docker/Caddyfile) so the edge compresses exactly once.
        val identity = client.get("/login")
        assertEquals(HttpStatusCode.OK, identity.status)
        assertNull(identity.headers["Content-Encoding"], "no negotiation, no encoding")
    }
}
