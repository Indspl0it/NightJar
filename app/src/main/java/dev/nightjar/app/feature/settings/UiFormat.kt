package dev.nightjar.app.feature.settings

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Date and time formatting shared by several screens.
 *
 * Times are stored as wall-clock milliseconds and rendered in the device zone, so a row cannot disagree with the
 * system clock. Each call uses the current locale, so a per-app language change shows at once. The formatters are
 * immutable and cheap to make; every method is safe to call from composition.
 */
object UiFormat {
    fun dateTime(atMillis: Long): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(instant(atMillis))

    fun date(atMillis: Long): String =
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .format(instant(atMillis))

    private fun instant(atMillis: Long): LocalDateTime = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
}
