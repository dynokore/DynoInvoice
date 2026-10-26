package com.dynokore.gst.transport.adapters

import com.dynokore.platform.serviceruntime.http.IdempotencySchema
import com.dynokore.platform.serviceruntime.jobs.JobSchema
import io.kotest.matchers.collections.shouldContainAll
import org.junit.jupiter.api.Test

/** After the migrations the schema holds the job runtime tables and the idempotency table (INV-Q-9684 option A). */
class JobTablesTest {
    @Test
    fun `the job and idempotency tables exist in the transport schema`() {
        tables() shouldContainAll
            listOf(
                JobSchema.JOB_RUN,
                JobSchema.JOB_PAUSE,
                JobSchema.SHEDLOCK,
                JobSchema.JOB_RETENTION,
                IdempotencySchema.TABLE,
            )
    }

    private fun tables(): Set<String> =
        TransportDb.superuser().use { connection ->
            connection.prepareStatement("select table_name from information_schema.tables where table_schema = ?").use {
                it.setString(1, TransportDb.SCHEMA)
                it.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toSet()
                }
            }
        }
}
