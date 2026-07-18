package com.wikikt

import com.wikikt.routing.sanitizeSameSiteRedirect
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The post-login "return to" target must resolve to a same-site path or fall back to "/". Guards against
 * off-site redirects, protocol-relative escapes, and the tab/newline normalization trick where a browser
 * strips a control char so "/<TAB>/evil.com" becomes the protocol-relative "//evil.com".
 */
class RedirectSanitizerTest {
    @Test
    fun `accepts a plain same-site path`() {
        assertEquals("/p/settings", sanitizeSameSiteRedirect("/p/settings"))
        assertEquals("/", sanitizeSameSiteRedirect("/"))
    }

    @Test
    fun `rejects off-site and control-character escapes, falling back to root`() {
        assertEquals("/", sanitizeSameSiteRedirect(null))
        assertEquals("/", sanitizeSameSiteRedirect(""))
        assertEquals("/", sanitizeSameSiteRedirect("evil.com"))
        assertEquals("/", sanitizeSameSiteRedirect("https://evil.com"))
        assertEquals("/", sanitizeSameSiteRedirect("//evil.com"))
        assertEquals("/", sanitizeSameSiteRedirect("/\\evil.com"))
        // The tab/newline/CR normalization trick — all must be rejected.
        assertEquals("/", sanitizeSameSiteRedirect("/\t/evil.com"))
        assertEquals("/", sanitizeSameSiteRedirect("/\n/evil.com"))
        assertEquals("/", sanitizeSameSiteRedirect("/\r/evil.com"))
    }
}
