package com.dynokore.gst.canonical.application.document

import java.util.UUID

/** Port: the legal constitution of an entity, owned by tenancy and held locally by event-carried state transfer. */
interface EntityConstitutions {
    /** The constitution of the entity, or null when none is known (an absent fact stays absent). */
    fun constitutionOf(
        tenantId: UUID,
        entityRef: String,
    ): String?
}
