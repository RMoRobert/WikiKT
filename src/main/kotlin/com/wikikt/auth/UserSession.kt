package com.wikikt.auth

import kotlinx.serialization.Serializable

/**
 * Cookie payload. Holds a server-side session id (looked up in the sessions table to resolve the
 * user and enforce revocation) plus the CSRF token. The user id is deliberately NOT in the cookie,
 * so a session can be invalidated server-side by deleting its row.
 */
@Serializable
data class UserSession(val sessionId: String, val csrfToken: String = "")

/**
 * The site an admin is currently managing (the "site switcher" selection), kept in its own cookie so
 * it's independent of the hostname a request arrives on. Falls back to the request's site when unset.
 */
@Serializable
data class AdminSiteSession(val siteId: UInt)

/**
 * A CSRF token for anonymous (pre-login) forms — the forgot-/reset-password flow, where there is no
 * [UserSession] yet to carry one. Issued on the GET that renders the form and checked on its POST, so a
 * cross-site page still can't forge the submission. Kept in its own short cookie, independent of login.
 */
@Serializable
data class AnonCsrfSession(val token: String)

/**
 * A password-verified login that is awaiting its second factor. Issued (instead of a [UserSession]) when a
 * user with MFA enabled submits correct credentials, and exchanged for a real [UserSession] only once the
 * code is verified — so this cookie on its own never grants access. Short-lived (the WIKIKT_MFA_PENDING
 * cookie expires in minutes) and carries the post-login redirect target so it survives the second step.
 */
@Serializable
data class MfaPendingSession(val userId: UInt, val issuedAt: Long, val redirect: String = "/")
