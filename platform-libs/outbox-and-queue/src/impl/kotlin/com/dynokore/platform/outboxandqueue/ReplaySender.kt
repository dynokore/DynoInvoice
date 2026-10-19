package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId

/**
 * Writes the states a replay request asks for ([JdbcReplayResponder]): for each [ReplayStateSource] whose type the
 * replay topic carries, page by page, each page in its own transaction bound to its tenant.
 */
internal class ReplaySender(
    private val sourcesByType: Map<String, ReplayStateSource>,
    private val writer: JdbcOutboxWriter,
    private val scope: TenantScope,
    private val pageSize: Int,
) {
    /** The number of events written. */
    fun send(
        replay: ReplayTopic,
        request: ReplayRequest,
        correlation: CorrelationId,
        causation: CausationId,
    ): Long {
        val run = Run(replay, request, correlation, causation)
        return replay.eventTypes
            .sorted()
            .mapNotNull { sourcesByType[it] }
            .sumOf { source ->
                val tenants = request.scope?.onlyTenant?.let(::listOf) ?: source.tenants()
                tenants.sumOf { sendTenant(source, run, it) }
            }
    }

    private class Run(
        val replay: ReplayTopic,
        val request: ReplayRequest,
        val correlation: CorrelationId,
        val causation: CausationId,
    )

    private fun sendTenant(
        source: ReplayStateSource,
        run: Run,
        tenant: TenantId,
    ): Long {
        var count = 0L
        var cursor: String? = null
        do {
            val page =
                scope.inTenant(tenant) {
                    val page = source.page(run.request.scope, cursor, pageSize)
                    require(
                        page.items.all { it.type == source.eventType },
                    ) { "a state source returns its own type" }
                    writer.appendReplayed(run.replay.topic, page.items, tenant, run.correlation, run.causation)
                    page
                }
            count += page.items.size
            cursor = page.next
        } while (cursor != null)
        return count
    }
}
