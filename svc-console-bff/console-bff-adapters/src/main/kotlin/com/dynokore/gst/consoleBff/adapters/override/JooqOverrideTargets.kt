package com.dynokore.gst.consoleBff.adapters.override

import com.dynokore.gst.consoleBff.adapters.persistence.jooq.tables.references.EXCEPTION
import com.dynokore.gst.consoleBff.application.override.OverridableRow
import com.dynokore.gst.consoleBff.application.override.OverrideTargets
import org.jooq.DSLContext
import java.util.UUID

/**
 * Reads one row of `console_bff.exception` by its id (INV-REQ-500). The tenant is the database binding: forced row
 * level security gives the caller's tenant only, so another tenant's id is no row. A single SELECT; this adapter
 * never writes (single writer per state: the exception row moves to OVERRIDDEN only from the owner's event).
 */
class JooqOverrideTargets(
    private val dsl: DSLContext,
) : OverrideTargets {
    override fun find(exceptionId: String): OverridableRow? {
        val id = runCatching { UUID.fromString(exceptionId) }.getOrNull() ?: return null
        return dsl
            .select(
                EXCEPTION.EXCEPTION_ID,
                EXCEPTION.ENTITY_REF,
                EXCEPTION.DOC_UUID,
                EXCEPTION.PERIOD_REF,
                EXCEPTION.CODE,
                EXCEPTION.STATE,
            ).from(EXCEPTION)
            .where(EXCEPTION.EXCEPTION_ID.eq(id))
            .fetchOne()
            ?.let {
                OverridableRow(
                    exceptionId = checkNotNull(it.get(EXCEPTION.EXCEPTION_ID)).toString(),
                    entityRef = checkNotNull(it.get(EXCEPTION.ENTITY_REF)),
                    docUuid = checkNotNull(it.get(EXCEPTION.DOC_UUID)),
                    periodRef = checkNotNull(it.get(EXCEPTION.PERIOD_REF)),
                    code = checkNotNull(it.get(EXCEPTION.CODE)),
                    state = checkNotNull(it.get(EXCEPTION.STATE)),
                )
            }
    }
}
