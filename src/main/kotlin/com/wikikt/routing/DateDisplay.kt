package com.wikikt.routing

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Renders/parses stored epoch-millis timestamps for a specific viewer. Every human-facing timestamp
 * goes through here so it honors the viewer's chosen timezone AND their date/time format preferences
 * (see [ApplicationCall.displayFormats]). Stored times are always epoch millis (UTC) — this only
 * affects how they're rendered.
 */
object DateDisplay {
    // --- Preference catalogs. Each option is a stable key (persisted) + a human name. The illustrative
    // example shown next to the name is generated from [SAMPLE] so it always matches the real pattern. ---

    data class Option(val key: String, val name: String)

    /** Short date — the date portion of the compact "date + time" shown across history/admin lists. */
    val SHORT_DATE_OPTIONS = listOf(
        Option("iso", "ISO 8601"),
        Option("us", "Month/Day/Year"),
        Option("eu", "Day/Month/Year"),
        Option("dot", "Day.Month.Year"),
        Option("abbrev", "Abbreviated month"),
    )

    /** Long date — the locale-styled date-only line (e.g. a page's "last modified"). */
    val LONG_DATE_OPTIONS = listOf(
        Option("full", "Full (with weekday)"),
        Option("long", "Long"),
        Option("medium", "Medium"),
    )

    /** Clock style for the time portion. */
    val TIME_OPTIONS = listOf(
        Option("24", "24-hour"),
        Option("12", "12-hour"),
    )

    const val DEFAULT_SHORT = "iso"
    const val DEFAULT_LONG = "long"
    const val DEFAULT_TIME = "24"

    private val SHORT_PATTERNS = mapOf(
        "iso" to "yyyy-MM-dd",
        "us" to "MM/dd/yyyy",
        "eu" to "dd/MM/yyyy",
        "dot" to "dd.MM.yyyy",
        "abbrev" to "MMM d, yyyy",
    )
    private val TIME_PATTERNS = mapOf(
        "24" to "HH:mm",
        "12" to "h:mm a",
    )
    private val LONG_STYLES = mapOf(
        "full" to FormatStyle.FULL,
        "long" to FormatStyle.LONG,
        "medium" to FormatStyle.MEDIUM,
    )

    private fun shortPattern(key: String?) = SHORT_PATTERNS[key] ?: SHORT_PATTERNS.getValue(DEFAULT_SHORT)
    private fun timePattern(key: String?) = TIME_PATTERNS[key] ?: TIME_PATTERNS.getValue(DEFAULT_TIME)
    private fun longStyle(key: String?) = LONG_STYLES[key] ?: LONG_STYLES.getValue(DEFAULT_LONG)

    // A fixed reference moment used only to render the human-readable examples in the preferences UI.
    // The 13th of July at 13:45 disambiguates day-vs-month order and forces a PM time (→ "1:45 PM").
    private val SAMPLE = LocalDateTime.of(2026, 7, 13, 13, 45)

    fun shortDateExample(key: String, locale: Locale): String =
        SAMPLE.format(DateTimeFormatter.ofPattern(shortPattern(key)).withLocale(locale))

    fun longDateExample(key: String, locale: Locale): String =
        SAMPLE.toLocalDate().format(DateTimeFormatter.ofLocalizedDate(longStyle(key)).withLocale(locale))

    fun timeExample(key: String, locale: Locale): String =
        SAMPLE.format(DateTimeFormatter.ofPattern(timePattern(key)).withLocale(locale))

    /**
     * A viewer's fully-resolved rendering context: their timezone plus formatters pre-built for their
     * short-date + time and long-date preferences (locale baked in). Built once per request by
     * [ApplicationCall.displayFormats]; pass it to [format] / [formatDate].
     */
    class DisplayFormats(
        val zone: ZoneId,
        private val dateTime: DateTimeFormatter,
        private val longDate: DateTimeFormatter,
    ) {
        internal fun formatDateTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(dateTime)
        internal fun formatLongDate(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(longDate)
    }

    /** Builds a [DisplayFormats] from a viewer's preference keys (null/unknown → the code defaults). */
    fun resolve(zone: ZoneId, shortKey: String?, longKey: String?, timeKey: String?, locale: Locale): DisplayFormats {
        val dateTime = DateTimeFormatter.ofPattern("${shortPattern(shortKey)} ${timePattern(timeKey)}").withLocale(locale)
        val longDate = DateTimeFormatter.ofLocalizedDate(longStyle(longKey)).withLocale(locale)
        return DisplayFormats(zone, dateTime, longDate)
    }

    /** Human-readable date + time in the viewer's zone and format preferences. */
    fun format(millis: Long, formats: DisplayFormats): String = formats.formatDateTime(millis)

    /** Date only (no time), in the viewer's zone and long-date preference. */
    fun formatDate(millis: Long, formats: DisplayFormats): String = formats.formatLongDate(millis)

    /** The value for an `<input type="datetime-local">` — wall-clock time in [zone] (or "" for null). */
    fun toInput(millis: Long?, zone: ZoneId): String =
        millis?.let { Instant.ofEpochMilli(it).atZone(zone).format(DATETIME_LOCAL) } ?: ""

    /** Parses an `<input type="datetime-local">` value as wall-clock time in [zone] → epoch millis. */
    fun parseInput(value: String, zone: ZoneId): Long? = runCatching {
        LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    private val DATETIME_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
}
