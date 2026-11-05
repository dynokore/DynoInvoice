package com.dynokore.gst.access.adapters.persistence

import com.dynokore.gst.access.adapters.http.InstallationJournal
import com.dynokore.gst.access.adapters.http.JournalOutcome
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.IDENTITY
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.INSTALLATION_APPROVER_FACT
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.INSTALLATION_FACT_REQUEST
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.MAPPING_ACKNOWLEDGEMENT
import com.dynokore.gst.access.application.approval.Identities
import com.dynokore.gst.access.application.approval.InstallationApproverFact
import com.dynokore.gst.access.application.approval.InstallationFacts
import com.dynokore.gst.access.application.approval.MappingAcknowledgement
import com.dynokore.gst.access.application.approval.MappingAcknowledgements
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

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

/**
 * TPL-11. The Idempotency-Key journal of `recordInstallationApproverFact` (API-15, INV-Q-15370), in the transaction of
 * the record and under the platform binding the caller opened. A key of one actor is serialised by a transaction
 * advisory lock (a second request while it runs is in flight). The key stores the body and the response: the same key
 * and body return that response only while the stored fact still equals the body's value, otherwise the body is applied
 * as an update and the response under the key is replaced; the same key with another body is refused.
 */
class JooqInstallationJournal(
    private val dsl: DSLContext,
    private val tx: TransactionRunner,
    private val facts: InstallationFacts,
) : InstallationJournal {
    override fun once(
        actor: String,
        key: UUID,
        distinctTaxApprover: Boolean,
        record: () -> InstallationApproverFact,
    ): JournalOutcome =
        tx.inTransaction {
            if (!lock(actor, key)) return@inTransaction JournalOutcome.InFlight
            val stored = find(actor, key)
            when {
                stored == null -> {
                    val fact = record()
                    insert(actor, key, distinctTaxApprover, fact)
                    JournalOutcome.Done(fact)
                }

                stored.distinctTaxApprover != distinctTaxApprover -> {
                    JournalOutcome.DifferentRequest
                }

                facts.find()?.distinctTaxApprover == distinctTaxApprover -> {
                    val at = stored.recordedAt.column("recordedAt").toInstant()
                    JournalOutcome.Done(InstallationApproverFact(distinctTaxApprover, at))
                }

                else -> {
                    val fact = record()
                    replaceResponse(actor, key, fact)
                    JournalOutcome.Done(fact)
                }
            }
        }

    private fun lock(
        actor: String,
        key: UUID,
    ): Boolean {
        val hash =
            DSL.function(
                DSL.name("hashtextextended"),
                SQLDataType.BIGINT,
                DSL.`val`("access.installation_fact_request/$actor/$key"),
                DSL.`val`(0L),
            )
        val lock = DSL.function(DSL.name("pg_try_advisory_xact_lock"), SQLDataType.BOOLEAN, hash)
        return dsl.select(lock).fetchOne()?.value1() == true
    }

    private fun find(
        actor: String,
        key: UUID,
    ) = dsl
        .selectFrom(INSTALLATION_FACT_REQUEST)
        .where(INSTALLATION_FACT_REQUEST.ACTOR.eq(actor))
        .and(INSTALLATION_FACT_REQUEST.IDEMPOTENCY_KEY.eq(key))
        .fetchOne()

    private fun insert(
        actor: String,
        key: UUID,
        distinctTaxApprover: Boolean,
        fact: InstallationApproverFact,
    ) {
        dsl
            .insertInto(INSTALLATION_FACT_REQUEST)
            .set(INSTALLATION_FACT_REQUEST.ACTOR, actor)
            .set(INSTALLATION_FACT_REQUEST.IDEMPOTENCY_KEY, key)
            .set(INSTALLATION_FACT_REQUEST.DISTINCT_TAX_APPROVER, distinctTaxApprover)
            .set(INSTALLATION_FACT_REQUEST.RECORDED_AT, fact.recordedAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    private fun replaceResponse(
        actor: String,
        key: UUID,
        fact: InstallationApproverFact,
    ) {
        dsl
            .update(INSTALLATION_FACT_REQUEST)
            .set(INSTALLATION_FACT_REQUEST.RECORDED_AT, fact.recordedAt.atOffset(ZoneOffset.UTC))
            .where(INSTALLATION_FACT_REQUEST.ACTOR.eq(actor))
            .and(INSTALLATION_FACT_REQUEST.IDEMPOTENCY_KEY.eq(key))
            .execute()
    }
}
