package com.dynokore.gst.controlPlane.adapters

import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.serviceruntime.http.IdempotencySchema
import com.dynokore.platform.serviceruntime.jobs.JobSchema
import io.kotest.matchers.collections.shouldContainAll
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test

/**
 * After the migrations the schema holds the tables the job runtime, the outbox and the idempotency store write
 * (TPL-12).
 */
class JobRuntimeTablesIT {
    @Test
    fun `the control-plane schema holds the outbox, job and idempotency tables`() {
        val names =
            ControlPlaneHarness
                .ownerDsl()
                .select(DSL.field("table_name", String::class.java))
                .from(DSL.table("information_schema.tables"))
                .where(DSL.field("table_schema").eq("controlplane"))
                .fetch(DSL.field("table_name", String::class.java))
        names shouldContainAll
            listOf(
                OutboxSchema.OUTBOX,
                JobSchema.JOB_RUN,
                JobSchema.JOB_PAUSE,
                JobSchema.SHEDLOCK,
                JobSchema.JOB_RETENTION,
                IdempotencySchema.TABLE,
            )
    }
}
