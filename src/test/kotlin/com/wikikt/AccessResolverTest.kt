package com.wikikt

import com.wikikt.service.AccessResolver
import com.wikikt.service.AccessResolver.AccessRule
import com.wikikt.service.AccessResolver.Match
import com.wikikt.service.AccessResolver.Mode
import com.wikikt.service.AccessResolver.Perm
import com.wikikt.service.AccessResolver.Principal
import com.wikikt.service.AccessResolver.Resource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the permission-resolver semantics: content verbs are rules-only
 * and default-deny (scoped by site/path/locale, assets like pages); admin verbs are a global gate;
 * `manage:system` is root; no per-page ACLs.
 */
class AccessResolverTest {
    private val SITE_A: UInt = 1u
    private val SITE_B: UInt = 2u

    private fun principal(perms: Set<String> = emptySet(), rules: List<AccessRule> = emptyList()) =
        Principal(perms, rules)

    private fun allow(role: String, path: String = "", match: Match = Match.START, sites: Set<UInt> = emptySet(), locales: Set<String> = emptySet()) =
        AccessRule(Mode.ALLOW, setOf(role), sites, locales, match, path)

    private fun deny(role: String, path: String = "", match: Match = Match.START, sites: Set<UInt> = emptySet(), locales: Set<String> = emptySet()) =
        AccessRule(Mode.DENY, setOf(role), sites, locales, match, path)

    private val page = Resource(siteId = SITE_A, locale = "en", path = "docs/intro")

    // --- Root ---

    @Test fun `manage system grants everything`() {
        val p = principal(setOf(Perm.MANAGE_SYSTEM)) // no rules, no other perms
        assertTrue(AccessResolver.check(p, Perm.WRITE_PAGES, page))
        assertTrue(AccessResolver.check(p, Perm.DELETE_PAGES, page))
        assertTrue(AccessResolver.check(p, Perm.MANAGE_USERS))
    }

    // --- Admin verbs: global gate ---

    @Test fun `admin verb is a straight global check`() {
        val p = principal(setOf(Perm.MANAGE_USERS, Perm.ACCESS_ADMIN))
        assertTrue(AccessResolver.check(p, Perm.MANAGE_USERS))
        assertTrue(AccessResolver.check(p, Perm.ACCESS_ADMIN))
        assertFalse(AccessResolver.check(p, Perm.MANAGE_SITES)) // not held
    }

    @Test fun `a page rule never grants an admin verb`() {
        // Even if a rule mentions an admin verb, admin verbs come only from global permissions.
        val p = principal(perms = emptySet(), rules = listOf(allow(Perm.MANAGE_SITES, path = "")))
        assertFalse(AccessResolver.check(p, Perm.MANAGE_SITES))
    }

    // --- Content verbs: rules-only, default-deny ---

    @Test fun `content verb with no matching rule is denied`() {
        assertFalse(AccessResolver.check(principal(), Perm.READ_PAGES, page))
    }

    @Test fun `content verb needs no global permission - the rule is the grant`() {
        val p = principal(perms = emptySet(), rules = listOf(allow(Perm.READ_PAGES, path = "")))
        assertTrue(AccessResolver.check(p, Perm.READ_PAGES, page))
    }

    @Test fun `a content verb with a null resource is always denied`() {
        val p = principal(rules = listOf(allow(Perm.READ_PAGES, path = "")))
        assertFalse(AccessResolver.check(p, Perm.READ_PAGES, resource = null))
    }

    // --- Specificity / deny-wins ---

    @Test fun `a more specific deny overrides a broad allow`() {
        val p = principal(rules = listOf(allow(Perm.READ_PAGES, path = ""), deny(Perm.READ_PAGES, path = "docs/")))
        assertFalse(AccessResolver.check(p, Perm.READ_PAGES, page)) // docs/intro
        assertTrue(AccessResolver.check(p, Perm.READ_PAGES, Resource(SITE_A, "en", "blog/hi")))
    }

    @Test fun `deny wins an exact tie with allow`() {
        val p = principal(rules = listOf(allow(Perm.READ_PAGES, path = "docs/"), deny(Perm.READ_PAGES, path = "docs/")))
        assertFalse(AccessResolver.check(p, Perm.READ_PAGES, page))
    }

    @Test fun `exact match beats a same-length prefix`() {
        val p = principal(
            rules = listOf(deny(Perm.READ_PAGES, path = "secret", match = Match.START), allow(Perm.READ_PAGES, path = "secret", match = Match.EXACT)),
        )
        assertTrue(AccessResolver.check(p, Perm.READ_PAGES, Resource(SITE_A, "en", "secret")))
        assertFalse(AccessResolver.check(p, Perm.READ_PAGES, Resource(SITE_A, "en", "secret/child")))
    }

    // --- Site scoping (the headline feature) ---

    @Test fun `an allow rule scoped to site A does not grant on site B`() {
        val p = principal(rules = listOf(allow(Perm.WRITE_PAGES, path = "", sites = setOf(SITE_A))))
        assertTrue(AccessResolver.check(p, Perm.WRITE_PAGES, Resource(SITE_A, "en", "docs/intro")))
        assertFalse(AccessResolver.check(p, Perm.WRITE_PAGES, Resource(SITE_B, "en", "docs/intro")))
    }

    @Test fun `a site-scoped deny beats an all-sites allow of equal path`() {
        val p = principal(rules = listOf(allow(Perm.WRITE_PAGES, path = ""), deny(Perm.WRITE_PAGES, path = "", sites = setOf(SITE_B))))
        assertTrue(AccessResolver.check(p, Perm.WRITE_PAGES, Resource(SITE_A, "en", "x")))
        assertFalse(AccessResolver.check(p, Perm.WRITE_PAGES, Resource(SITE_B, "en", "x")))
    }

    // --- Locale scoping ---

    @Test fun `a locale-scoped rule only applies to that locale`() {
        val p = principal(rules = listOf(allow(Perm.WRITE_PAGES, path = "", locales = setOf("fr"))))
        assertTrue(AccessResolver.check(p, Perm.WRITE_PAGES, Resource(SITE_A, "fr", "x")))
        assertFalse(AccessResolver.check(p, Perm.WRITE_PAGES, Resource(SITE_A, "en", "x")))
    }

    // --- Assets are path-scoped exactly like pages ---

    @Test fun `asset verbs are path-scoped by the same rules`() {
        val p = principal(rules = listOf(allow(Perm.WRITE_ASSETS, path = "api/", sites = setOf(SITE_A))))
        assertTrue(AccessResolver.check(p, Perm.WRITE_ASSETS, Resource(SITE_A, "en", "api/logo.png")))
        assertFalse(AccessResolver.check(p, Perm.WRITE_ASSETS, Resource(SITE_A, "en", "marketing/logo.png")))
        assertFalse(AccessResolver.check(p, Perm.WRITE_ASSETS, Resource(SITE_B, "en", "api/logo.png")))
    }

    // --- read:assets is coupled to read:pages DENY (making a page private hides its images too) ---

    @Test fun `a read pages deny also denies read assets on the same path`() {
        // The Guest/User seed: broad ALLOW of both read verbs everywhere, then an admin marks a subtree
        // private with the natural "DENY read:pages". An asset under that subtree must NOT stay readable.
        val p = principal(
            rules = listOf(
                allow(Perm.READ_PAGES, path = ""),
                allow(Perm.READ_ASSETS, path = ""),
                deny(Perm.READ_PAGES, path = "secret/"),
            ),
        )
        assertFalse(AccessResolver.check(p, Perm.READ_ASSETS, Resource(SITE_A, "en", "secret/diagram.png")))
        assertFalse(AccessResolver.check(p, Perm.READ_PAGES, Resource(SITE_A, "en", "secret/page")))
        // Outside the denied subtree, assets remain readable.
        assertTrue(AccessResolver.check(p, Perm.READ_ASSETS, Resource(SITE_A, "en", "public/diagram.png")))
    }

    @Test fun `the coupling is one-way - a read pages allow does not grant read assets`() {
        // Only read:pages is allowed here; assets still need their own grant (default-deny holds).
        val p = principal(rules = listOf(allow(Perm.READ_PAGES, path = "")))
        assertTrue(AccessResolver.check(p, Perm.READ_PAGES, Resource(SITE_A, "en", "docs/x")))
        assertFalse(AccessResolver.check(p, Perm.READ_ASSETS, Resource(SITE_A, "en", "docs/x.png")))
    }

    // --- Match types ---

    @Test fun `TAG rule matches by page tag`() {
        val p = principal(rules = listOf(deny(Perm.READ_PAGES, path = "internal", match = Match.TAG), allow(Perm.READ_PAGES, path = "")))
        assertFalse(AccessResolver.check(p, Perm.READ_PAGES, Resource(SITE_A, "en", "docs/x", tags = setOf("internal"))))
        assertTrue(AccessResolver.check(p, Perm.READ_PAGES, Resource(SITE_A, "en", "docs/x")))
    }

    @Test fun `REGEX rule matches by pattern`() {
        val p = principal(rules = listOf(allow(Perm.READ_PAGES, path = "^docs/", match = Match.REGEX)))
        assertTrue(AccessResolver.check(p, Perm.READ_PAGES, Resource(SITE_A, "en", "docs/intro")))
        assertFalse(AccessResolver.check(p, Perm.READ_PAGES, Resource(SITE_A, "en", "blog/intro")))
    }
}
