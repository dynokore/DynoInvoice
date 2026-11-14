package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.application.gate.LatestChecks
import com.dynokore.gst.submission.application.readiness.MAX_READINESS_PAGE
import com.dynokore.gst.submission.application.readiness.ReadinessCheckReader
import com.dynokore.gst.submission.domain.gate.LatestCheck

/**
 * [LatestChecks] over a [ReadinessCheckReader] (INV-AC-22488). [latest] maps the newest stored record; [knownCheckIds]
 * reads every record of the period page by page (newest first, `before` the last `seq`) until a short page. A failing
 * read propagates: it is never answered as an empty set, which would let the override flow treat a check as unknown.
 */
class ReadinessLatestChecks(
    private val reader: ReadinessCheckReader,
) : LatestChecks {
    override fun latest(
        entityRef: String,
        periodRef: String,
    ): LatestCheck? = reader.latest(entityRef, periodRef)?.let { LatestCheck(it.checkId, it.verdict) }

    override fun knownCheckIds(
        entityRef: String,
        periodRef: String,
    ): Set<String> {
        val ids = LinkedHashSet<String>()
        var before: Long? = null
        while (true) {
            val page = reader.page(entityRef, periodRef, MAX_READINESS_PAGE, before)
            page.forEach { ids += it.checkId }
            if (page.size < MAX_READINESS_PAGE) return ids
            before = page.last().seq
        }
    }
}
