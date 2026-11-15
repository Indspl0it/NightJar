package dev.nightjar.app.domain.notify

import dev.nightjar.contract.ClassId

/**
 * At most one **alerting** notification per class per `notify.rateLimitMin`; further ones update silently.
 * One alert per burst; later updates of the same burst are silent.
 *
 * State is per process and that is the intent: the limit exists to keep a burst of ten silent pings from ringing ten
 * times, not to enforce a persistent quota. The time is passed in so the behaviour is testable with a fake clock. One
 * instance is shared by every router (a singleton), and the calls are synchronized.
 */
class RateLimiter {
    private val lastAlertAt = HashMap<ClassId, Long>()

    /** The newest burst keys that already alerted, at most [MAX_BURSTS], so the set cannot grow for the process life. */
    private val alertedBursts =
        object : LinkedHashMap<String, Unit>(MAX_BURSTS, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > MAX_BURSTS
        }

    @Synchronized
    fun allowAlert(
        classId: ClassId,
        nowWallMs: Long,
        windowMin: Int,
    ): Boolean {
        val window = windowMin.coerceAtLeast(1) * 60_000L
        val last = lastAlertAt[classId]
        if (last != null && nowWallMs - last < window) return false
        lastAlertAt[classId] = nowWallMs
        return true
    }

    /** True only the first time [burstKey] is seen (among the newest [MAX_BURSTS] keys). */
    @Synchronized
    fun firstAlertOfBurst(burstKey: String): Boolean = alertedBursts.put(burstKey, Unit) == null

    private companion object {
        /** Far more bursts than can be open at the same time; an older burst has ended long ago. */
        const val MAX_BURSTS = 64
        const val LOAD_FACTOR = 0.75f
    }
}
