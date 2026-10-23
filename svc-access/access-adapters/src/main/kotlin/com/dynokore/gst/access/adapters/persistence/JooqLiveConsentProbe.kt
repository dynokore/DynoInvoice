package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.consent.LiveConsentProbe
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/** The capability function `access.live_consent_exists()` of the migrations, named by hand like the sequence. */
private val LIVE_CONSENT_EXISTS =
    DSL.function(DSL.name(ACCESS_SCHEMA, "live_consent_exists"), SQLDataType.BOOLEAN)

/**
 * Asks whether a consent is live in any tenant. Row-level security hides other tenants' rows from the runtime login,
 * so the answer comes from a function owned by `access_sys` that reads the existence of a live row and nothing else.
 */
class JooqLiveConsentProbe(
    private val dsl: DSLContext,
) : LiveConsentProbe {
    override fun anyLive(): Boolean = dsl.select(LIVE_CONSENT_EXISTS).fetchOne(0, Boolean::class.java) == true
}
