package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.application.chain.LocatorRecorder
import com.dynokore.gst.evidence.application.chain.RecordAppender
import com.dynokore.gst.evidence.domain.chain.RecordKind
import com.dynokore.platform.evidencestore.JdbcEvidenceLedger
import org.jooq.DSLContext
import java.time.Clock

/** The appender over one [JdbcEvidenceLedger] per chain table and the three stores, on a tenant-bound [DSLContext]. */
object ChainWiring {
    const val SCHEMA = "evidence"

    fun recordAppender(
        dsl: DSLContext,
        clock: Clock = Clock.systemUTC(),
    ): RecordAppender =
        RecordAppender(
            RecordKind.entries.associateWith { JdbcEvidenceLedger(dsl, SCHEMA, it.table) },
            JooqLocatorStore(dsl),
            JooqPendingStore(dsl),
            JooqAppliedStore(dsl),
            clock,
        )

    fun locatorRecorder(
        dsl: DSLContext,
        clock: Clock = Clock.systemUTC(),
    ): LocatorRecorder = LocatorRecorder(JooqLocatorStore(dsl), recordAppender(dsl, clock), clock)
}
