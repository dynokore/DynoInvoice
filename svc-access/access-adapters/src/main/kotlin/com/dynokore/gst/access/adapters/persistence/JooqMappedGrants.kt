package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.ROLE_GRANT
import com.dynokore.gst.access.application.idp.MappedGrants
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantStatus
import org.jooq.DSLContext

/** TPL-11. The live grants of the bound tenant that a directory-group mapping made. */
class JooqMappedGrants(
    private val dsl: DSLContext,
    private val grants: JooqGrantRepository,
) : MappedGrants {
    override fun madeFrom(mappingRef: String): List<Grant> =
        dsl
            .selectFrom(ROLE_GRANT)
            .where(ROLE_GRANT.MAPPING_REF.eq(mappingRef), ROLE_GRANT.STATUS.ne(GrantStatus.REVOKED.name))
            .orderBy(ROLE_GRANT.GRANT_REF)
            .fetch()
            .mapNotNull { grants.toGrant(it) }
}
