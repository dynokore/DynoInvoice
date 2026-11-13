package com.dynokore.gst.consoleBff.application.exceptions

import com.dynokore.gst.consoleBff.domain.badge.ItemState
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionCursor
import com.dynokore.gst.consoleBff.domain.exceptions.ExceptionSortKey
import com.dynokore.gst.consoleBff.domain.queue.ScopeDecision
import com.dynokore.gst.consoleBff.domain.queue.decideScope

data class ListExceptionsQuery(
    val tenantId: String,
    val principalId: String,
    val entityRef: String?,
    val state: ItemState?,
    val deferred: Boolean,
    val limit: Int,
    val cursor: String?,
)

sealed interface ListExceptionsResult {
    data class Listed(
        val items: List<QueuedException>,
        val nextCursor: String?,
    ) : ListExceptionsResult

    data class OutOfScope(
        val entityRef: String,
    ) : ListExceptionsResult

    data object BadCursor : ListExceptionsResult

    data object BadLimit : ListExceptionsResult
}

/** The getExceptions read: scope decided per request, keyset paging over [ExceptionQueue]. */
class ListExceptions(
    private val scope: EntityScope,
    private val queue: ExceptionQueue,
    private val maxLimit: Int,
) {
    fun list(query: ListExceptionsQuery): ListExceptionsResult =
        when (val checked = checked(query)) {
            is Checked.Bad -> checked.result
            is Checked.Ok -> listChecked(query, checked.cursor)
        }

    private sealed interface Checked {
        data class Ok(
            val cursor: ExceptionSortKey?,
        ) : Checked

        data class Bad(
            val result: ListExceptionsResult,
        ) : Checked
    }

    private fun checked(query: ListExceptionsQuery): Checked {
        val decoded = query.cursor?.let { ExceptionCursor.decode(it) }
        return when {
            query.limit < 1 || query.limit > maxLimit -> Checked.Bad(ListExceptionsResult.BadLimit)
            query.cursor != null && decoded == null -> Checked.Bad(ListExceptionsResult.BadCursor)
            else -> Checked.Ok(decoded)
        }
    }

    private fun listChecked(
        query: ListExceptionsQuery,
        after: ExceptionSortKey?,
    ): ListExceptionsResult =
        when (val decision = decideScope(query.entityRef, scope.entitiesOf(query.tenantId, query.principalId))) {
            is ScopeDecision.Refused -> {
                ListExceptionsResult.OutOfScope(decision.entityRef)
            }

            is ScopeDecision.Entities -> {
                if (decision.entityRefs.isEmpty()) {
                    ListExceptionsResult.Listed(emptyList(), null)
                } else {
                    page(query, decision.entityRefs.toSet(), after)
                }
            }
        }

    private fun page(
        query: ListExceptionsQuery,
        entities: Set<String>,
        after: ExceptionSortKey?,
    ): ListExceptionsResult.Listed {
        val rows = queue.page(query.tenantId, entities, query.state, query.deferred, after, query.limit + 1)
        val items = rows.take(query.limit)
        val last = items.lastOrNull()
        val next =
            if (rows.size > query.limit && last != null) {
                ExceptionCursor.encode(keyOf(last))
            } else {
                null
            }
        return ListExceptionsResult.Listed(items, next)
    }

    private fun keyOf(item: QueuedException) =
        ExceptionSortKey(item.dueDate, item.row.raisedAt, item.row.exceptionId.toString())
}
