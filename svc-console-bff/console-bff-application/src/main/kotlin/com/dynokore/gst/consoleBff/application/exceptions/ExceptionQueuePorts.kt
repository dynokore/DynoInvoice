package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionRow
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionSortKey
import java.time.LocalDate

/** A stored row with its due date joined at read time; null when no due date is known (never guessed). */
data class QueuedException(
    val row: ExceptionRow,
    val dueDate: LocalDate?,
)

/** Read port of the exception queue (stored rows only, no call to another service). */
interface ExceptionQueue {
    /**
     * Rows of the tenant in [entityRefs] strictly after [after], ordered by due date ascending (unknown last),
     * raisedAt ascending, exceptionId ascending, at most [limit]. [deferred] false lists the rows no deferral
     * covers, true only the rows one covers.
     */
    @Suppress("LongParameterList") // port shape fixed by INV-T-21104: one argument per filter and the keyset position
    fun page(
        tenantId: String,
        entityRefs: Set<String>,
        state: ItemState?,
        deferred: Boolean,
        after: ExceptionSortKey?,
        limit: Int,
    ): List<QueuedException>
}

/** Entities the principal may read, already decided per request; this port reads no role. */
interface EntityScope {
    fun entitiesOf(
        tenantId: String,
        principalId: String,
    ): Set<String>
}
