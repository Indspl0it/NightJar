package dev.nightjar.app.feature.classify

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.domain.notify.TimeFormatter
import dev.nightjar.app.domain.text.DateFormatter
import dev.nightjar.app.domain.text.PackageLabels
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App labels and icons for consumer and calling packages (QUERY_ALL_PACKAGES).
 *
 * A missing or uninstalled package falls back to the package name itself: showing `com.example.app` is honest, while
 * hiding the line would make an outbound message look anonymous.
 */
@Singleton
class PackageLabelAdapter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PackageLabels {
        // The ingest worker and the UI read the cache at the same time. ConcurrentHashMap takes no null value, so a
        // package without a label is stored as NO_LABEL.
        private val cache = ConcurrentHashMap<String, String>()

        override fun labelOf(packageName: String?): String? {
            if (packageName == null) return null
            val label = cache.getOrPut(packageName) { resolve(packageName) ?: NO_LABEL }
            return label.takeIf { it != NO_LABEL }
        }

        private fun resolve(packageName: String): String? =
            try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(info).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }

        /** Drops the cache after a package install/remove, so a renamed app is not shown by its old label. */
        fun invalidate() = cache.clear()

        private companion object {
            /** An app label never holds a NUL character, so this value cannot be a real label. */
            const val NO_LABEL = "\u0000"
        }
    }

/**
 * The default SMS app: its label for FATE-TXT-SHOWN ("Shown in Google Messages") and its package name for the
 * "Block sending" warning. This is the only place that reads the default SMS app. The values are
 * kept for [CACHE_MS], because the explainer asks for the label once per rendered row and the user changes the default
 * app rarely.
 */
@Singleton
class DefaultSmsApp
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val labels: PackageLabelAdapter,
    ) {
        @Volatile private var cached: Snapshot? = null

        /** The label of the default SMS app, or null when there is none or it cannot be read. */
        fun label(): String? = snapshot().label

        /** The package name of the default SMS app, or null when there is none or it cannot be read. */
        fun packageName(): String? = snapshot().packageName

        private fun snapshot(): Snapshot {
            val now = SystemClock.elapsedRealtime()
            cached?.let { if (now - it.atMs < CACHE_MS) return it }
            val packageName = runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
            return Snapshot(now, packageName, packageName?.let(labels::labelOf)).also { cached = it }
        }

        private class Snapshot(
            val atMs: Long,
            val packageName: String?,
            val label: String?,
        )

        private companion object {
            const val CACHE_MS = 60_000L
        }
    }

/** Locale-aware times and dates for list rows, detail facts and notifications. */
@Singleton
class AndroidDates
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : DateFormatter,
        TimeFormatter {
        private val timeFormat: DateFormat =
            android.text.format.DateFormat
                .getTimeFormat(context)
        private val dateFormat: DateFormat =
            android.text.format.DateFormat
                .getDateFormat(context)

        // A DateFormat keeps the zone it was made with. Each call sets the current zone, so a time-zone change while
        // the process runs shows the new zone.
        @Synchronized
        override fun time(wallMs: Long): String = timeFormat.apply { timeZone = TimeZone.getDefault() }.format(Date(wallMs))

        @Synchronized
        override fun date(wallMs: Long): String = dateFormat.apply { timeZone = TimeZone.getDefault() }.format(Date(wallMs))
    }
