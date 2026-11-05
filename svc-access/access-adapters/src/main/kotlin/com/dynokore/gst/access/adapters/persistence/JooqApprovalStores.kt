package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.IDENTITY
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.INSTALLATION_APPROVER_FACT
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.MAPPING_ACKNOWLEDGEMENT
import com.dynokore.gst.access.application.approval.Identities
import com.dynokore.gst.access.application.approval.InstallationApproverFact
import com.dynokore.gst.access.application.approval.InstallationFacts
import com.dynokore.gst.access.application.approval.MappingAcknowledgement
import com.dynokore.gst.access.application.approval.MappingAcknowledgements
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** TPL-11. The kind each identity of the bound tenant was created with; the row policy restricts the rows. */
class JooqIdentities(
    private val dsl: DSLContext,
) : Identities {
    override fun kindOf(identity: IdentityRef): GranteeKind? =
        dsl
            .select(IDENTITY.GRANTEE_KIND)
            .from(IDENTITY)
            .where(IDENTITY.IDENTITY_REF.eq(identity.value))
            .fetchOne(IDENTITY.GRANTEE_KIND)
            ?.let { kind -> GranteeKind.entries.singleOrNull { it.name == kind } }
}

/** TPL-11. Exact match on the pack version and the client: a look-alike of either finds no row. */
class JooqMappingAcknowledgements(
    private val dsl: DSLContext,
) : MappingAcknowledgements {
    override fun find(
        packVersion: String,
        clientRef: String,
    ): MappingAcknowledgement? =
        dsl
            .selectFrom(MAPPING_ACKNOWLEDGEMENT)
            .where(MAPPING_ACKNOWLEDGEMENT.PACK_VERSION.eq(packVersion))
            .and(MAPPING_ACKNOWLEDGEMENT.CLIENT_REF.eq(clientRef))
            .fetchOne()
            ?.let {
                MappingAcknowledgement(
                    it.packVersion.column("packVersion"),
                    it.clientRef.column("clientRef"),
                    it.acknowledgedAt.column("acknowledgedAt").toInstant(),
                )
            }
}

/**
 * TPL-11. The one installation row, under the reserved platform tenant: the caller binds it (like the verification
 * keys), and the DSLContext listener adds it to the statements.
 */
class JooqInstallationFacts(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : InstallationFacts {
    override fun find(): InstallationApproverFact? =
        dsl.selectFrom(INSTALLATION_APPROVER_FACT).fetchOne()?.let { toFact(it.distinctTaxApprover, it.recordedAt) }

    override fun put(
        distinctTaxApprover: Boolean,
        at: Instant,
    ): InstallationApproverFact {
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound: a change runs in a bound transaction" }
        val recordedAt = at.atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(INSTALLATION_APPROVER_FACT)
            .set(INSTALLATION_APPROVER_FACT.DISTINCT_TAX_APPROVER, distinctTaxApprover)
            .set(INSTALLATION_APPROVER_FACT.RECORDED_AT, recordedAt)
            .set(INSTALLATION_APPROVER_FACT.RECORDED_BY, actor)
            .onConflict(INSTALLATION_APPROVER_FACT.TENANT_ID)
            .doUpdate()
            .set(INSTALLATION_APPROVER_FACT.DISTINCT_TAX_APPROVER, distinctTaxApprover)
            .set(INSTALLATION_APPROVER_FACT.RECORDED_AT, recordedAt)
            .set(INSTALLATION_APPROVER_FACT.RECORDED_BY, actor)
            .execute()
        return checkNotNull(find()) { "the installation fact just written is not readable" }
    }

    private fun toFact(
        distinct: Boolean?,
        recordedAt: OffsetDateTime?,
    ) = InstallationApproverFact(distinct.column("distinctTaxApprover"), recordedAt.column("recordedAt").toInstant())
}
