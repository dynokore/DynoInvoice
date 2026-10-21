package com.dynokore.gst.payload.domain

/** A capability of payload-svc that is not considered specified until its precondition holds (INV-REQ-481). */
enum class CapabilityKey {
    SERIALISATION,
}

/** The state of a capability in the registry; the name of `markSpecified` fixes the second value. */
enum class CapabilityState {
    NOT_SPECIFIED,
    SPECIFIED,
}
