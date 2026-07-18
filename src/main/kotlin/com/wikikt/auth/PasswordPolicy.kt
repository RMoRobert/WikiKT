package com.wikikt.auth

/**
 * Rules for user-chosen passwords. Length-only by design: a password is hashed immediately and never
 * rendered as HTML or interpolated into a query, so "dangerous" characters are harmless — forbidding
 * them would only shrink the search space. NIST 800-63B recommends accepting all printable Unicode
 * (including spaces and emoji), so we do.
 *
 * The upper bound is bcrypt's, not an arbitrary choice: bcrypt hashes at most 72 BYTES of input and
 * silently ignores anything past that, so we cap the UTF-8 length there rather than let extra
 * characters be quietly dropped (which would make a password verify against a shorter prefix of
 * itself). Supporting longer passwords would require pre-hashing (e.g. SHA-512→bcrypt) plus a
 * rehash-on-login migration for existing hashes.
 */
object PasswordPolicy {
    const val DEFAULT_MIN_LENGTH = 5
    const val MAX_BYTES = 72

    /**
     * A human-readable reason the password is unacceptable, or null if it satisfies the policy.
     * [minLength] is the effective minimum length — operator-configurable via
     * `wikikt.security.minPasswordLength` / `WIKIKT_MIN_PASSWORD_LENGTH` (see WikiKtConfig) and threaded in
     * by the callers; it defaults to [DEFAULT_MIN_LENGTH] for call sites (e.g. tests) that don't override it.
     */
    fun validate(password: String, minLength: Int = DEFAULT_MIN_LENGTH): String? = when {
        password.length < minLength -> "Password must be at least $minLength characters."
        password.toByteArray(Charsets.UTF_8).size > MAX_BYTES ->
            "Password is too long (max $MAX_BYTES characters, fewer if it uses accented or emoji characters)."
        else -> null
    }
}
