package com.wikikt.markdown

/**
 * Runtime-configurable rendering toggles, set from Administration > Settings > Rendering and read on
 * each page render (see [com.wikikt.service.SettingsService.renderOptions]). [DEFAULT] is the safe
 * baseline used when no settings are available (the footer, tests): embeds and inline styles off,
 * autolink on. Grouped by where they take effect — [MarkdownRenderer] (parse/render) vs
 * [HtmlSanitizer] (allowlist).
 */
data class RenderOptions(
    /** Allow `<iframe>` embeds (videos, maps). Off by default — they load third-party content. */
    val allowIframes: Boolean = false,
    /** Allow inline `style=""` attributes. Off by default — jsoup does not sanitize the CSS inside, so
     *  this trusts page authors (CSS is a clickjacking / overlay vector). */
    val allowStyleAttr: Boolean = false,
    /** Turn bare `http(s)://…` / `www.` text into clickable links (autolinking). */
    val autoLink: Boolean = true,
    /** Render single newlines as `<br>` (hard breaks) instead of collapsing them to a space. */
    val lineBreaks: Boolean = false,
    /** Whether (and how far out) a link gets an "opens elsewhere" icon appended (see [ExternalLinkMode]). */
    val externalLinkMode: ExternalLinkMode = ExternalLinkMode.OFF,
    /** Hostnames treated as internal for [ExternalLinkMode.INSTANCE] (all sites on this instance plus the
     *  configured public URL), lowercased. Ignored for the other modes; empty for [DEFAULT]. */
    val internalHosts: Set<String> = emptySet(),
) {
    companion object {
        val DEFAULT = RenderOptions()
    }
}

/**
 * Whether an external-link marker (the box-with-arrow icon) is appended to links that leave the wiki,
 * set from Administration > Settings > Rendering and read into [RenderOptions.externalLinkMode].
 *
 * "External" is decided from the link's URL alone: relative and root-absolute (`/…`) links are always
 * internal, and non-web schemes (`mailto:`, `tel:`, `#anchor`) are never marked. Only absolute
 * `http(s)://` (and protocol-relative `//host`) links are candidates.
 */
enum class ExternalLinkMode {
    /** No marker. (The code baseline [RenderOptions.DEFAULT] uses this; the product default for a live
     *  site is [SITE] — see [com.wikikt.service.SettingsService.DEFAULT_EXTERNAL_LINK_ICON].) */
    OFF,

    /** Mark every link that leaves THIS wiki site. A link to a sibling site on the same instance still
     *  counts as external (it has an absolute URL), so it is marked. */
    SITE,

    /** Mark only links that leave the whole INSTANCE. Links to a sibling site hosted here (its host is in
     *  [RenderOptions.internalHosts]) are treated as internal and not marked. */
    INSTANCE,

    ;

    companion object {
        /** Parses a stored setting value to a mode, falling back to [OFF] for null/blank/unknown. */
        fun from(value: String?): ExternalLinkMode =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: OFF
    }
}
