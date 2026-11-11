package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.application.operatorsession.AuthorityVersions
import org.jooq.DSLContext

/** The one access-svc authority sequence (TPL-05), drawn inside the transaction the caller holds. */
class JooqAuthorityVersions(
    private val dsl: DSLContext,
) : AuthorityVersions {
    override fun next(): Long = dsl.nextval(AUTHORITY_VERSION_SEQ)
}
