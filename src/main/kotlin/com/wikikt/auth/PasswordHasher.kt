package com.wikikt.auth

import at.favre.lib.crypto.bcrypt.BCrypt

object PasswordHasher {
    // Hash of an unguessable throwaway value, verified against when a username doesn't exist —
    // see [verifyDummy].
    private val dummyHash: String by lazy { hash(java.util.UUID.randomUUID().toString()) }

    fun hash(password: String): String =
        BCrypt.withDefaults().hashToString(12, password.toCharArray())

    fun verify(password: String, hash: String): Boolean =
        BCrypt.verifyer().verify(password.toCharArray(), hash).verified

    /**
     * Burns the same bcrypt cost as a real verification but always fails. Called on login for
     * unknown usernames so response timing doesn't reveal which usernames exist.
     */
    fun verifyDummy(password: String) {
        verify(password, dummyHash)
    }
}
