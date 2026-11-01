package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.AttemptOwner.Companion.ATTEMPT_OWNER
import com.dynokore.gst.transport.application.AttemptOwner
import com.dynokore.gst.transport.application.AttemptOwnerRef
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext

/**
 * The tenant and entity of the first attempt row of a Doc UUID (INV-Q-9674 A), through the sys-owned function
 * `transport.attempt_owner`: a callback arrives with no tenant bound, and the runtime login cannot read another
 * tenant's `submission_attempt` directly. The function is asked for the Doc UUID alone; the version is not part of the
 * lookup.
 */
class JooqAttemptOwner(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val platform: PlatformTenantBinding,
    private val workloadPrincipal: String,
) : AttemptOwner {
    override fun ownerOf(doc: DocRef): AttemptOwnerRef? =
        platform.bindPlatform(workloadPrincipal) {
            runner.inTransaction {
                val owner = ATTEMPT_OWNER.call(doc.docUuid)
                dsl
                    .select(owner.OWNER_TENANT, owner.OWNER_ENTITY)
                    .from(owner)
                    .fetchOne()
                    ?.let { AttemptOwnerRef(TenantRef(it.value1().toString()), EntityRef(checkNotNull(it.value2()))) }
            }
        }
}
