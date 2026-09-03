package com.wikikt

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.name
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `local` asset mode must be a byte-identical substitute for `cdn` mode. The templates already say
 * what the CDN copy is (the URL) and what its bytes must be (the SRI hash); this test derives the same
 * hash from the bundled file under `static/vendor/` and requires equality, so bumping a CDN tag
 * without refreshing the vendored file, or the reverse, fails here instead of quietly serving two
 * different versions. It also checks that the URL's version appears inside the vendored file and that
 * the same template offers a `/static/vendor/...` link for its `local` branch.
 *
 * Because the hashes are derived here, the page-level tests (MermaidSourceTest, IconFontSourceTest,
 * EditorAssetSourceTest) only assert that *an* SRI attribute is present (see [sriOn]). A vendor
 * refresh therefore edits templates and vendored files, never tests. Bootstrap, which had no test of
 * its own, is covered the same way.
 */
class VendoredAssetIntegrityTest {
    private val templates = Path.of("src/main/resources/templates/mustache")
    private val vendor = Path.of("src/main/resources/static/vendor")

    /**
     * Vendored files that are deliberately not byte-identical to the CDN copy their SRI names, with the
     * reason. They are still checked for version and a local link; only the hash comparison is skipped.
     */
    private val hashExempt = mapOf(
        // Rewritten to a single same-directory woff2 @font-face source so the eot/woff/ttf siblings
        // need not be vendored (IconFontSourceTest pins that rewrite; application.yaml says "woff2 only").
        "materialdesignicons.min.css" to "rewritten @font-face",
    )

    private data class SriTag(val template: Path, val line: Int, val url: String, val hash: String)

    private val cdnUrl = Regex("""https://[^\s"'{}]+?\.(?:js|css)(?=[\s"'{}?])""")
    private val sriHash = Regex("""sha384-[A-Za-z0-9+/=]+""")
    private val urlVersion = Regex("""@(\d+\.\d+\.\d+)""")

    private fun walk(root: Path): List<Path> = Files.walk(root).use { it.filter(Files::isRegularFile).toList() }

    /** Every SRI hash in the templates paired with the CDN URL on the same line. */
    private fun sriTags(): List<SriTag> =
        walk(templates).filter { it.name.endsWith(".hbs") }.sorted().flatMap { file ->
            Files.readAllLines(file).withIndex().mapNotNull { (i, text) ->
                val hash = sriHash.find(text)?.value ?: return@mapNotNull null
                val urls = cdnUrl.findAll(text).map { it.value }.toSet()
                assertEquals(1, urls.size, "$file:${i + 1} carries an SRI hash but ${urls.size} CDN URLs; expected exactly one: $text")
                SriTag(file, i + 1, urls.single(), hash)
            }
        }

    private fun sha384(path: Path): String =
        "sha384-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-384").digest(Files.readAllBytes(path)))

    @Test
    fun `every SRI-protected CDN asset has a byte-identical, same-version, locally linked vendored copy`() {
        val tags = sriTags()
        assertTrue(tags.size >= 5, "expected the Bootstrap, EasyMDE, Mermaid and MDI tags; the template scan found only ${tags.size}: $tags")
        val vendored = walk(vendor)

        for (tag in tags) {
            val where = "${tag.template}:${tag.line} (${tag.url})"
            val name = tag.url.substringAfterLast('/')
            val copies = vendored.filter { it.name == name }
            assertEquals(1, copies.size, "$where: expected exactly one vendored file named $name under $vendor, found $copies")
            val copy = copies.single()

            if (name !in hashExempt) {
                assertEquals(tag.hash, sha384(copy), "$where: the template's SRI hash does not match the vendored $copy — refresh whichever side is stale")
            }

            val version = assertNotNull(
                urlVersion.find(tag.url)?.groupValues?.get(1),
                "$where: the CDN URL carries no @x.y.z version to check the vendored copy against",
            )
            val text = String(Files.readAllBytes(copy), Charsets.ISO_8859_1)
            assertTrue(version in text, "$where: vendored $copy does not contain version $version from the CDN URL — it is a different release")

            val localHref = "/static/vendor/" + vendor.relativize(copy).toString().replace('\\', '/')
            assertTrue(localHref in Files.readString(tag.template), "$where: the template has no '$localHref' link for this asset's local branch")
        }
    }
}
