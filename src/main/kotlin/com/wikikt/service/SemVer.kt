package com.wikikt.service

/**
 * Minimal semantic-version value type for release comparison (update checks, `BuildInfo.isRelease`).
 * Hand-rolled rather than a dependency: the app needs exactly one comparison plus prerelease
 * awareness. Follows semver.org ordering: numeric fields, then §11 prerelease rules; `+build`
 * metadata is parsed off and ignored for ordering.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Dot-separated prerelease identifiers (`rc.1` -> ["rc","1"]); empty means a final release. */
    val prerelease: List<String> = emptyList(),
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).let { if (it != 0) return it }
        // §11: a prerelease sorts below its release (1.0.0-rc.1 < 1.0.0).
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return other.prerelease.size.compareTo(prerelease.size)
        }
        for ((a, b) in prerelease.zip(other.prerelease)) {
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val cmp = when {
                an != null && bn != null -> an.compareTo(bn) // numeric identifiers compare numerically
                an != null -> -1 // numeric sorts below alphanumeric
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (cmp != 0) return cmp
        }
        return prerelease.size.compareTo(other.prerelease.size) // longer prerelease sorts higher
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (prerelease.isEmpty()) "" else "-" + prerelease.joinToString(".")

    companion object {
        // [v]X.Y.Z[-pre][+build] — prerelease/build restricted to semver's identifier alphabet.
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        /**
         * Parses `[v]X.Y.Z[-pre][+build]`; returns null on anything else (never throws). Callers
         * must treat a null on either side of a comparison as "no update available", never as
         * "update available".
         */
        fun parse(raw: String): SemVer? {
            val m = PATTERN.matchEntire(raw.trim()) ?: return null
            val (major, minor, patch) = m.destructured
            val pre = m.groupValues[4]
            if (pre.isNotEmpty() && pre.split('.').any { it.isEmpty() }) return null // "1.0.0-a..b"
            return SemVer(
                major = major.toIntOrNull() ?: return null, // overflow-length digits -> null
                minor = minor.toIntOrNull() ?: return null,
                patch = patch.toIntOrNull() ?: return null,
                prerelease = if (pre.isEmpty()) emptyList() else pre.split('.'),
            )
        }
    }
}
