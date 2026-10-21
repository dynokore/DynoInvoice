package com.dynokore.gst.payload.adapters.persistence

import com.dynokore.gst.payload.adapters.persistence.jooq.tables.Capability
import com.dynokore.gst.payload.application.CapabilityRegistry
import com.dynokore.gst.payload.domain.CapabilityKey
import com.dynokore.gst.payload.domain.CapabilityState
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/** The capability registry over the generated `payload.capability` table. */
class JooqCapabilityRegistry(
    private val dsl: DSLContext,
) : CapabilityRegistry {
    override fun state(key: CapabilityKey): CapabilityState =
        dsl
            .select(Capability.CAPABILITY.STATE)
            .from(Capability.CAPABILITY)
            .where(Capability.CAPABILITY.KEY.eq(key.name))
            .fetchOne(Capability.CAPABILITY.STATE)
            ?.let(CapabilityState::valueOf)
            ?: error("capability ${key.name} is missing from payload.capability")

    /**
     * Calls the definer function of `payload_sys` (static SQL); it refuses a transaction that is not bound to the
     * platform tenant, and `payload_runtime` holds no UPDATE on the table (INV-REQ-854 behavior 2).
     */
    override fun markSpecified(key: CapabilityKey) {
        dsl
            .select(
                DSL.function(
                    DSL.name("payload", "mark_capability_specified"),
                    SQLDataType.BOOLEAN,
                    DSL.`val`(key.name),
                ),
            ).fetchOne()
    }
}
