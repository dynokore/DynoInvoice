package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.networknt.schema.Error
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion

/** The published contract schemas (staged into tenancy-contract) and a writer that records what is appended. */
internal object SchemaFixture {
    fun schema(name: String): Schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                checkNotNull(javaClass.classLoader.getResourceAsStream("gst/contracts/tenancy/schemas/$name")) {
                    "$name is not on the classpath"
                }.use { it.readBytes().toString(Charsets.UTF_8) },
            )

    fun errors(
        schema: Schema,
        payload: String,
    ): List<Error> = schema.validate(payload, InputFormat.JSON)
}

internal class RecordingOutbox : OutboxWriter {
    val events = ArrayList<OutboundEvent>()

    override fun append(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<Envelope> {
        this.events += events
        return emptyList()
    }
}
