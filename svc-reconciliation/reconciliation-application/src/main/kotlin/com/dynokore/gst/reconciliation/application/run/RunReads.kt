package com.dynokore.gst.reconciliation.application.run

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

data class RunPageView(
    val items: List<RecordedRun>,
    val nextCursor: String?,
)

/** Pages the runs of a period newest first with a bounded keyset cursor (API-18). */
class ListRuns(
    private val reader: RunReader,
) {
    fun execute(
        tenant: String,
        entityRef: String,
        periodRef: String,
        limit: Int?,
        cursor: String?,
    ): RunPageView {
        val size = limit ?: DEFAULT_LIMIT
        require(size in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
        val after = cursor?.let(::decode)
        val rows = reader.page(tenant, entityRef, periodRef, size + 1, after)
        val items = rows.take(size)
        val next = if (rows.size > size) encode(items.last()) else null
        return RunPageView(items, next)
    }

    private fun encode(run: RecordedRun): String {
        val text = "${run.completedAt}|${run.runId}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
    }

    private fun decode(cursor: String): RunCursor =
        try {
            val text = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
            val at = text.indexOf('|')
            require(at > 0 && at < text.length - 1)
            RunCursor(Instant.parse(text.substring(0, at)), text.substring(at + 1))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("unreadable cursor", e)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("unreadable cursor", e)
        }

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 500
    }
}

/** Reads one run of the tenant; null when it does not exist or belongs to another tenant. */
class GetRun(
    private val reader: RunReader,
) {
    fun execute(
        tenant: String,
        runId: String,
    ): RunSnapshot? = reader.find(tenant, runId)
}
