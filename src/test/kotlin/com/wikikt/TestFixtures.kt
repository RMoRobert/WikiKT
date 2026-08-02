package com.wikikt

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Shared fixtures for integration tests that need a normal content pag, seeded here
 */
const val SAMPLE_PAGE_PATH = "dir1/file1"
const val SAMPLE_PAGE_TITLE = "File One"

/** Logs in as the default test admin over the JSON API; returns the CSRF token for subsequent writes. */
suspend fun HttpClient.loginAsAdmin(username: String = "admin", password: String = "test"): String =
    post("/u/v1/auth/login") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$username","password":"$password"}""")
    }.headers["X-CSRF-Token"] ?: error("login as $username did not return a CSRF token")

/**
 * Creates the generic sample content page via the JSON API. Requires an admin [csrf] (see [loginAsAdmin]).
 * Returns the raw response so a caller can assert on it if desired.
 */
suspend fun HttpClient.createSamplePage(
    csrf: String,
    path: String = SAMPLE_PAGE_PATH,
    title: String = SAMPLE_PAGE_TITLE,
): HttpResponse =
    post("/u/v1/pages") {
        contentType(ContentType.Application.Json)
        header("X-CSRF-Token", csrf)
        setBody("""{"locale":"en","path":"$path","title":"$title","content":"Sample content for the $title page, created by the test fixture.","published":true}""")
    }
