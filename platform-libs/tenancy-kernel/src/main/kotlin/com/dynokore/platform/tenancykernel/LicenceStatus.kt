package com.dynokore.platform.tenancykernel

/**
 * A tenant's licence/entitlement standing, as far as it can be known right now.
 *
 * [UNKNOWN] is a first-class outcome, not an error: the licence file, the
 * entitlement source or the check itself may be unavailable (externals.yaml:
 * "Licence status unknown; DEP-005 at most — never blocks submission",
 * FR-H.4), and a caller sees that the same way it sees any other status —
 * never an exception.
 */
enum class LicenceStatus { OK, GRACE, EXPIRED, UNKNOWN }
