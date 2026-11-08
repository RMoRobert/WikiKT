package com.wikikt.model

import java.lang.Character.UnicodeScript
import java.text.Normalizer

/**
 * File extensions accepted for each image type ImageType detects; the first is what a missing or wrong
 * one becomes. Browsers trust a save-as name's extension, so it must agree with the bytes.
 */
private val IMAGE_EXTENSIONS = mapOf(
    "image/png" to listOf("png"),
    "image/jpeg" to listOf("jpg", "jpeg", "jpe", "jfif"),
    "image/gif" to listOf("gif"),
    "image/webp" to listOf("webp"),
)

/** Longest kept name in UTF-8 bytes: filesystems allow 255 per path segment; the rest is headroom for an extension. */
private const val MAX_FILENAME_BYTES = 240

// Can't be saved on Windows, or get re-decoded by browsers in a Content-Disposition filename (`%XX`, `=?…?=`).
private const val FILENAME_UNSAFE = "\"*:<>?\\|/%"

// Letters that render as nothing (Hangul fillers) and the blank braille pattern: invisible but not Cf.
private val BLANK_CHARS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800)

private const val ZWNJ = 0x200C
private const val ZWJ = 0x200D

/** Any whitespace or separator, Unicode ones included (NBSP, U+3000, U+2028…), which Kotlin's `\s` misses. */
internal fun isSpaceCodePoint(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

/**
 * A character that shows as nothing or reorders the text around it: controls, format characters such as
 * bidi overrides (U+202E), zero-width space and BOM, lone surrogates, and [BLANK_CHARS]. ZWNJ/ZWJ are
 * kept, since Persian and Indic words and emoji sequences need them; where they may sit is a separate
 * question ([hasStrayJoiner]), asked of paths but not of display names.
 */
internal fun isInvisibleCodePoint(cp: Int): Boolean {
    if (cp == ZWNJ || cp == ZWJ) return false
    return when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE -> true
        else -> cp in BLANK_CHARS
    }
}

// Scripts in which ZWNJ/ZWJ change what is drawn: the cursive scripts whose letters join up (Arabic and
// its relatives), and the Brahmic scripts whose virama forms conjuncts. Elsewhere the two render as nothing.
private val JOINER_SCRIPTS = setOf(
    UnicodeScript.ARABIC, UnicodeScript.SYRIAC, UnicodeScript.MONGOLIAN, UnicodeScript.NKO, UnicodeScript.MANDAIC,
    UnicodeScript.ADLAM, UnicodeScript.HANIFI_ROHINGYA,
    UnicodeScript.DEVANAGARI, UnicodeScript.BENGALI, UnicodeScript.GURMUKHI, UnicodeScript.GUJARATI, UnicodeScript.ORIYA,
    UnicodeScript.TAMIL, UnicodeScript.TELUGU, UnicodeScript.KANNADA, UnicodeScript.MALAYALAM, UnicodeScript.SINHALA,
    UnicodeScript.MYANMAR, UnicodeScript.KHMER, UnicodeScript.TIBETAN, UnicodeScript.BALINESE, UnicodeScript.JAVANESE,
    UnicodeScript.SUNDANESE, UnicodeScript.CHAM, UnicodeScript.TAI_THAM, UnicodeScript.MEETEI_MAYEK, UnicodeScript.LIMBU,
    UnicodeScript.LEPCHA, UnicodeScript.SAURASHTRA, UnicodeScript.SYLOTI_NAGRI, UnicodeScript.CHAKMA,
)

private val MARKS = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK)

/**
 * Whether [text] holds a ZWNJ or ZWJ somewhere it draws nothing. The two are allowed between letters of a
 * [JOINER_SCRIPTS] script (Persian می‌خواهم needs its ZWNJ; a Devanagari conjunct is shaped by a ZWJ or ZWNJ
 * after the virama), looking through combining marks to the letters on either side, and ZWJ is allowed
 * between two symbols, which is how emoji sequences are built. Between Latin, Cyrillic, Greek or CJK
 * characters, next to a digit, hyphen or another joiner, or at either end of a word, they are invisible, and
 * would only make two different paths look the same.
 */
internal fun hasStrayJoiner(text: String): Boolean {
    val cps = text.codePoints().toArray()
    return cps.indices.any { (cps[it] == ZWNJ || cps[it] == ZWJ) && !joinerDrawsSomething(cps, it) }
}

private fun joinerDrawsSomething(cps: IntArray, at: Int): Boolean {
    // The nearest code point in each direction that isn't a combining mark. A second joiner there makes this
    // one stray: a run of joiners draws the same as one.
    fun neighbour(step: Int): Int? {
        var i = at + step
        while (i in cps.indices && Character.getType(cps[i]).toByte() in MARKS) i += step
        return cps.getOrNull(i)
    }
    val before = neighbour(-1) ?: return false
    val after = neighbour(+1) ?: return false
    val joinsLetters = Character.isLetter(before) && Character.isLetter(after) &&
        UnicodeScript.of(before) in JOINER_SCRIPTS && UnicodeScript.of(after) in JOINER_SCRIPTS
    val joinsSymbols = cps[at] == ZWJ && isSymbol(before) && isSymbol(after)
    return joinsLetters || joinsSymbols
}

// Emoji are OTHER_SYMBOL; skin-tone modifiers (which a ZWJ can follow) are MODIFIER_SYMBOL.
private fun isSymbol(cp: Int): Boolean =
    Character.getType(cp).toByte().let { it == Character.OTHER_SYMBOL || it == Character.MODIFIER_SYMBOL }

/**
 * Cleans an uploaded file's name for storage as its display name, which is also its save-as name. Keeps
 * what a person would recognise (case, spaces, most punctuation, any script); drops the client's
 * directory, invisible characters ([isInvisibleCodePoint]) and leading/trailing dots and spaces; turns
 * every kind of space into one plain space; and replaces [FILENAME_UNSAFE] with `_`. NFC, so the same
 * name always compares equal. Capped at [MAX_FILENAME_BYTES] UTF-8 bytes, keeping the extension.
 * Returns "" when nothing is left.
 */
fun cleanFilename(raw: String): String {
    // Browsers send `"`, CR and LF as %22/%0D/%0A in a multipart filename (WHATWG HTML); Ktor doesn't undo it.
    val decoded = raw.substringAfterLast('/').substringAfterLast('\\')
        .replace("%22", "\"", ignoreCase = true)
        .replace("%0D", "\r", ignoreCase = true)
        .replace("%0A", "\n", ignoreCase = true)
    val mapped = buildString {
        Normalizer.normalize(decoded, Normalizer.Form.NFC).codePoints().forEach { cp ->
            when {
                cp < 0x80 && cp.toChar() in FILENAME_UNSAFE -> append('_')
                isSpaceCodePoint(cp) -> append(' ')
                isInvisibleCodePoint(cp) -> {}
                else -> appendCodePoint(cp)
            }
        }
    }
    return capUtf8(mapped.replace(Regex(" {2,}"), " ").trim(' ', '.'), MAX_FILENAME_BYTES)
}

/**
 * The display name stored and served for an asset of type [mime]: [cleanFilename] (or `file` when
 * nothing is left) with [withImageExtension]. Run at upload and again at serve time, which covers names
 * that arrived through imports, restores or before this cleaning existed.
 */
fun assetFilename(raw: String, mime: String): String = withImageExtension(cleanFilename(raw).ifEmpty { "file" }, mime)

/**
 * [name] with an extension that matches [mime]: kept when it's one of the type's extensions (any case),
 * swapped when it's another image type's, and appended otherwise, so a PNG named `invoice.hta` becomes
 * `invoice.hta.png`. A [mime] with no known extensions leaves [name] unchanged.
 */
fun withImageExtension(name: String, mime: String): String {
    val exts = IMAGE_EXTENSIONS[mime] ?: return name
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0) name.substring(dot + 1).lowercase() else ""
    return when {
        ext in exts -> name
        IMAGE_EXTENSIONS.values.any { ext in it } -> name.substring(0, dot + 1) + exts[0]
        else -> "$name.${exts[0]}"
    }
}

// What a slug keeps: letters (any script), combining marks, digits and `_`; every other run becomes one `-`.
private val NON_SLUG_RUN = Regex("""[^\p{L}\p{M}\p{N}_]+""")

// A combining mark with nothing of its own to attach to: at the start, or straight after a hyphen.
private val STRAY_MARKS = Regex("""(^|-)\p{M}+""")

/**
 * The URL path segment an upload of type [mime] is stored under, the way Wiki.js 3 slugs it: the
 * [cleanFilename] lowercased, with every run of anything but letters, combining marks, digits and `_`
 * (spaces, dots, punctuation) turned into one `-`. Letters stay in their own script (`日本 x.png` →
 * `日本-x.png`). The extension is made to match [mime]: an image extension is kept or swapped, anything
 * else is folded into the name (`invoice.hta` → `invoice-hta.png`).
 */
fun slugFilename(name: String, mime: String): String {
    val clean = cleanFilename(name)
    val dot = clean.lastIndexOf('.')
    val ext = if (dot > 0) clean.substring(dot + 1).lowercase() else ""
    val imageExt = IMAGE_EXTENSIONS.values.any { ext in it }
    val stem = if (imageExt) clean.substring(0, dot) else clean
    val slug = Normalizer.normalize(stem.lowercase(), Normalizer.Form.NFC)
        .replace(NON_SLUG_RUN, "-").replace(STRAY_MARKS, "$1").trim('-').ifEmpty { "file" }
    val exts = IMAGE_EXTENSIONS[mime] ?: return if (imageExt) "$slug.$ext" else slug
    return "$slug.${if (ext in exts) ext else exts[0]}"
}

/** [name] cut to at most [maxBytes] UTF-8 bytes on a code point boundary, keeping a short extension. */
private fun capUtf8(name: String, maxBytes: Int): String {
    fun bytes(cp: Int) = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }
    if (name.codePoints().map(::bytes).sum() <= maxBytes) return name
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
    var budget = maxBytes - ext.codePoints().map(::bytes).sum()
    val stem = buildString {
        for (cp in name.substring(0, name.length - ext.length).codePoints()) {
            budget -= bytes(cp)
            if (budget < 0) break
            appendCodePoint(cp)
        }
    }
    return stem.trimEnd(' ', '.') + ext
}
