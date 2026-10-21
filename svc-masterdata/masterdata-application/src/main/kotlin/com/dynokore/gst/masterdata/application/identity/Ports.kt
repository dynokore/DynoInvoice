package com.dynokore.gst.masterdata.application.identity

import com.dynokore.gst.masterdata.domain.identity.ConfirmerSet
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.identity.TripleVersion

/** The stored triples of the bound tenant (TPL-11); every call runs inside the caller's transaction. */
interface TripleRepository {
    /** Every version of the entity under the constitution, oldest first. */
    fun versions(
        entity: EntityRef,
        constitution: Constitution,
    ): List<TripleVersion>

    /** Stores [version]; a repeat of (entity, constitution, effectiveFrom) replaces the stored one (repeatKey). */
    fun save(
        version: TripleVersion,
        proposedBy: PersonRef,
        confirmedBy: PersonRef,
        confirmedRole: String,
    )
}

/** The tenant's confirmer set (VP-09). Until a tenant has set one, the default applies. */
fun interface ConfirmerPolicy {
    fun confirmers(): ConfirmerSet
}
