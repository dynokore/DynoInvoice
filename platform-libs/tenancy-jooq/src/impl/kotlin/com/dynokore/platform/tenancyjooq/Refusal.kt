package com.dynokore.platform.tenancyjooq

/** The refusal every tenancy-jooq check raises, as a call that never returns. */
internal fun refuse(message: String): Nothing = throw TenantPredicateViolation(message)
