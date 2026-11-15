package dev.nightjar.app.domain.cb

/**
 * Page reassembly and de-duplication of cell broadcasts (AC1).
 *
 * The payload pushes one event per received page (HK-17 sees each PDU separately). A broadcast is identified by
 * **(serial, message identifier, subId, update number)** — the update number is what makes a re-sent alert a new
 * message instead of a duplicate — and its pages are joined in page order once all of them arrived.
 *
 * The assembler is a bounded in-memory map: a group that never completes is evicted after [MAX_GROUPS] newer groups
 * (a broadcast is a handful of pages and the network repeats it, so eviction costs nothing but a lost partial page).
 * Nothing here touches the database; `CbIngest` decides what a caller persists.
 *
 * After a process restart or an eviction the map does not know the pages that are already stored. The caller gives
 * them to [accept] again before the new page: a page that the group already has changes nothing, so the
 * revision (`pagesSeen`) counts every page, stored or new.
 *
 * Thread: the ingest worker only.
 */
class CbAssembler(
    private val maxGroups: Int = MAX_GROUPS,
) {
    /** Identity of one logical broadcast on one subscription. */
    data class GroupKey(
        val subId: Int,
        val serial: Int,
        val messageId: Int,
        val updateNumber: Int,
    )

    /** One received page with the bytes it came from, so the stored PDU list keeps every part. */
    data class Part(
        val page: CbPage,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Part) return false
            return page == other.page && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * page.hashCode() + bytes.contentHashCode()
    }

    /** The state of a group after one page was accepted. */
    data class Assembly(
        val key: GroupKey,
        val pagesTotal: Int,
        val pagesSeen: Int,
        val complete: Boolean,
        val text: String,
        val parts: List<Part>,
    )

    private class Entry(
        val key: GroupKey,
        var pagesTotal: Int,
        var complete: Boolean = false,
    ) {
        val pages = sortedMapOf<Int, Part>()
    }

    private val groups = LinkedHashMap<GroupKey, Entry>()

    /** Accepts one page and returns the group's state after it. */
    fun accept(
        page: CbPage,
        bytes: ByteArray,
        subId: Int,
    ): Assembly {
        val key = GroupKey(subId, page.serial, page.messageId, page.updateNumber)
        val entry = groups[key] ?: newEntry(key, page.pages)
        if (!entry.complete) {
            entry.pages[page.page] = Part(page, bytes)
            entry.pagesTotal = maxOf(entry.pagesTotal, page.pages)
            entry.complete = isComplete(entry)
        }
        return Assembly(
            key = key,
            pagesTotal = entry.pagesTotal,
            pagesSeen = entry.pages.size,
            complete = entry.complete,
            text = entry.pages.values.joinToString(separator = "") { it.page.text },
            parts = entry.pages.values.toList(),
        )
    }

    /** True when this exact page was already accepted (a retransmission). */
    fun isDuplicate(
        page: CbPage,
        subId: Int,
    ): Boolean {
        val entry = groups[GroupKey(subId, page.serial, page.messageId, page.updateNumber)] ?: return false
        return entry.pages.containsKey(page.page)
    }

    fun forget(key: GroupKey) {
        groups.remove(key)
    }

    fun clear() {
        groups.clear()
    }

    fun size(): Int = groups.size

    private fun newEntry(
        key: GroupKey,
        pages: Int,
    ): Entry {
        if (groups.size >= maxGroups) {
            val oldest = groups.keys.firstOrNull()
            if (oldest != null) groups.remove(oldest)
        }
        val entry = Entry(key, pages.coerceAtLeast(1))
        groups[key] = entry
        return entry
    }

    private fun isComplete(entry: Entry): Boolean {
        if (entry.pages.size < entry.pagesTotal) return false
        for (index in 1..entry.pagesTotal) {
            if (!entry.pages.containsKey(index)) return false
        }
        return true
    }

    companion object {
        /** Groups kept before the oldest is evicted. A real broadcast has at most 15 pages (TS 23.041). */
        const val MAX_GROUPS = 128
    }
}
