package dev.nightjar.app.feature.export

import androidx.paging.PagingSource
import dev.nightjar.app.data.db.MessageDao
import dev.nightjar.app.data.db.PduDao
import dev.nightjar.app.data.db.TraceStepDao
import dev.nightjar.app.data.model.FilterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the messages of one export scope.
 *
 * The feed query is reused through [MessageDao.feed], so the mandatory exclusions (counters, self-test rows) and the
 * user's filter behave exactly as Messages does. Rows are loaded a page at a time and handed to the caller one message at a
 * time: only the ids already written are kept in memory.
 *
 * The feed is a Room offset paging source that runs here without a Pager. A write to the table (a new SMS) makes it
 * invalid. The export then starts the query again from the first page and skips the ids it already wrote, so no row is
 * lost or written twice. A query that is made invalid more than [MAX_RESTARTS] times fails the export instead of
 * reporting a short file as done.
 */
@Singleton
class ExportSource
    @Inject
    constructor(
        private val messages: MessageDao,
        private val pdus: PduDao,
        private val traces: TraceStepDao,
    ) {
        /**
         * Visits every message in [selection] when it is non-null, otherwise every message matching [spec], and stops
         * after [limit] messages. [includeTree] fills the decoded TPDU tree (JSON only).
         */
        suspend fun forEach(
            spec: FilterSpec?,
            selection: List<Long>?,
            includeTree: Boolean,
            limit: Int = Int.MAX_VALUE,
            onMessage: suspend (ExportMessage) -> Unit,
        ) = withContext(Dispatchers.IO) {
            if (selection != null) {
                selection.take(limit).chunked(CHUNK).forEach { ids ->
                    messages.findMany(ids).forEach { row ->
                        onMessage(
                            row.toExportMessage(
                                pdus = pdus.forMessage(row.id),
                                trace = traces.forMessage(row.id),
                                includeTree = includeTree,
                            ),
                        )
                    }
                }
                return@withContext
            }
            val filter = spec ?: FilterSpec(showDeliveryReports = true)
            val args = filter.toArgs()
            val newSource = {
                messages.feed(
                    includeStatus = args.includeStatus,
                    classCount = args.classCount,
                    classIds = args.classIds,
                    severityCount = args.severityCount,
                    severities = args.severities,
                    fateCount = args.fateCount,
                    fates = args.fates,
                    attentionOnly = args.attentionOnly,
                    attentionSeverity = args.attentionSeverity,
                    quarantinedOnly = args.quarantinedOnly,
                    unreviewedOnly = args.unreviewedOnly,
                    starredOnly = args.starredOnly,
                    direction = args.direction,
                    fromWall = args.fromWall,
                    toWall = args.toWall,
                    subId = args.subId,
                    slot = args.slot,
                    searchLike = args.searchLike,
                    searchNormLike = args.searchNormLike,
                    searchPort = args.searchPort,
                    originatorLike = args.originatorLike,
                    port = args.port,
                    contentTypeLike = args.contentTypeLike,
                    packageLike = args.packageLike,
                )
            }
            val written = HashSet<Long>()
            var sent = 0
            var restarts = 0
            var source = newSource()
            var params: PagingSource.LoadParams<Int> = PagingSource.LoadParams.Refresh(null, CHUNK, false)
            while (true) {
                when (val result = source.load(params)) {
                    is PagingSource.LoadResult.Page -> {
                        val ids =
                            result.data
                                .map { it.id }
                                .filter { id -> written.add(id) }
                                .take(limit - sent)
                        sent += ids.size
                        if (ids.isNotEmpty()) {
                            messages.findMany(ids).forEach { row ->
                                onMessage(
                                    row.toExportMessage(
                                        pdus = pdus.forMessage(row.id),
                                        trace = traces.forMessage(row.id),
                                        includeTree = includeTree,
                                    ),
                                )
                            }
                        }
                        if (sent >= limit) break
                        val next = result.nextKey ?: break
                        params = PagingSource.LoadParams.Append(next, CHUNK, false)
                    }

                    is PagingSource.LoadResult.Error -> {
                        throw result.throwable
                    }

                    is PagingSource.LoadResult.Invalid -> {
                        restarts++
                        check(restarts <= MAX_RESTARTS) { "export query invalidated $restarts times" }
                        source = newSource()
                        params = PagingSource.LoadParams.Refresh(null, CHUNK, false)
                    }
                }
            }
        }

        private companion object {
            /** Below SQLite's 999 bound-variable limit and the feed page size. */
            const val CHUNK = 200

            /** Restarts after a table write; more than this means the table changes too fast to export now. */
            const val MAX_RESTARTS = 20
        }
    }
