package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.application.source.RecordSourceEvent
import com.dynokore.gst.canonical.application.source.RecordedResult
import com.dynokore.gst.canonical.domain.source.SourceKey
import java.util.UUID

/**
 * The record pipeline, counterparty step: the reference is resolved at masterdata before the record transaction (the
 * call is bounded to the edge deadline and must not hold a transaction open), then persisted in the transaction that
 * binds the Doc UUID. NotFound and Unavailable write nothing: a reference is never invented. A directory exception
 * propagates and nothing is recorded. On a repeat the directory is called again (a repeat cannot be told earlier) and
 * its answer is unused, because the record step does not run the callback.
 */
class RecordDocumentPipeline(
    private val directory: CounterpartyDirectory,
    private val recordSourceEvent: RecordSourceEvent,
    private val recordPartyRef: RecordPartyRef,
) {
    fun record(
        key: SourceKey,
        lookup: CounterpartyLookup?,
        buildResult: (UUID) -> String,
    ): RecordedResult {
        val answer = lookup?.let { directory.partyRefOf(it) }
        return recordSourceEvent.record(key) { docUuid ->
            (answer as? PartyRefAnswer.Found)?.let { recordPartyRef.record(docUuid, it.partyRef) }
            buildResult(docUuid)
        }
    }
}
