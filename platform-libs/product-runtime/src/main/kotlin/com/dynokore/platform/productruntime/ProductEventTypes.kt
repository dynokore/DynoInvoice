package com.dynokore.platform.productruntime

/** The CloudEvents types and topics of the domain events that feed service-runtime's replicas (INV-Q-3259). */
object ProductEventTypes {
    const val ORGANISATION_STRUCTURE_CHANGED = "com.dynokore.gst.tenancy.OrganisationStructureChanged.v1"
    const val ENTITY_PROFILE_CHANGED = "com.dynokore.gst.tenancy.EntityProfileChanged.v1"
    const val AUTHORITY_CHANGED = "com.dynokore.gst.access.AuthorityChanged.v1"

    const val ORGANISATION_STRUCTURE_CHANGED_TOPIC = "gst.tenancy.organisation-structure-changed.v1"
    const val ENTITY_PROFILE_CHANGED_TOPIC = "gst.tenancy.entity-profile-changed.v1"
    const val AUTHORITY_CHANGED_TOPIC = "gst.access.authority-changed.v1"
}
