package com.dynokore.gst.aggregation.application

/** The aggregates of an entity that are due to be handed on for submission, and the mark that one was. */
interface HandOffRepository {
    /**
     * The aggregates of [entityRef] that carry lines, are not superseded, were not handed on and whose window is no
     * longer open (a batch aggregate placed in a window now closed, or the aggregate of a closed window part).
     */
    fun due(entityRef: String): List<StoredAggregate>

    /** Marks the aggregate handed on; a second call changes nothing. */
    fun markHandedOn(aggregateId: java.util.UUID)
}

/** One call per aggregate that is handed on for submission (channel `aggregationAggregateReady`). */
fun interface AggregateReadyPublisher {
    fun ready(aggregate: StoredAggregate)
}

/**
 * The hand-off of aggregates to submission (INV-REQ-718 item 2, INV-REQ-411 Type 2): exactly one AggregateReady for
 * each due aggregate, publish first and mark second, in the transaction the caller has open, so a failure leaves the
 * aggregate due and nothing published.
 */
class HandOnAggregates(
    private val repository: HandOffRepository,
    private val publisher: AggregateReadyPublisher,
) {
    fun execute(entityRef: String): List<StoredAggregate> =
        repository.due(entityRef).onEach {
            publisher.ready(it)
            repository.markHandedOn(it.aggregateId)
        }
}
