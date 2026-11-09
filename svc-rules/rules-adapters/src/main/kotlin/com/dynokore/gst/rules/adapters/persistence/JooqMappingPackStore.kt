package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.packs.EngineVersion
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.MAPPING_ENTRY_ACCEPTANCE
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.MAPPING_PACK_APPROVAL
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_ENTRY_SOURCE
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_REGISTRY
import com.dynokore.gst.rules.application.MappingActivation
import com.dynokore.gst.rules.application.MappingApproval
import com.dynokore.gst.rules.application.MappingPackDraft
import com.dynokore.gst.rules.application.MappingPackStore
import com.dynokore.gst.rules.domain.pack.MappingAcceptanceRecord
import com.dynokore.gst.rules.domain.pack.MappingDraftEntry
import com.dynokore.gst.rules.domain.pack.PackLayer
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.SourceClass
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** One entry of a draft as the read operations answer it (the contract's `MappingEntry`). */
data class MappingEntryView(
    val entryId: String,
    val internalTaxCode: String,
    val irasCode: String,
    val sourceClass: SourceClass,
    val accepted: Boolean,
    val schemeTag: String?,
)

/** A pack header with the counts the approver sees before signing (`getMappingPack`). */
data class MappingPackView(
    val packId: String,
    val packVersion: String,
    val status: PackStatus,
    val effectiveFrom: LocalDate?,
    val entryCount: Int,
    val nonPrimaryCount: Int,
    val unacceptedNonPrimaryCount: Int,
)

/**
 * The mapping pack draft over `rules.pack_registry` and `rules.pack_entry_source` and the acceptance and approval
 * tables (INV-Q-14648 A), jOOQ only, on the open transaction of the command. The tenant comes from the signed binding
 * (row-level security does the rest) and the registering principal is the bound actor, so the stored author is the
 * user ID of the verified token (INV-Q-10647). Nothing draft is written to `rules.pack_artefact`.
 */
@Suppress("TooManyFunctions") // the store operations of the port plus the read model of the two queries
class JooqMappingPackStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val engineVersion: EngineVersion,
    private val clock: Clock,
) : MappingPackStore {
    private fun mine(packId: String): Condition =
        PACK_REGISTRY.PACK_ID
            .eq(packId)
            .and(PACK_REGISTRY.PACK_TYPE.eq(PACK_TYPE))
            .and(PACK_REGISTRY.LAYER.eq(PackLayer.TENANT_MAPPING.name))
            .and(PACK_REGISTRY.TENANT_ID.eq(tenants.current().value))

    private fun versionOf(packId: String): String =
        checkNotNull(
            dsl
                .select(PACK_REGISTRY.PACK_VERSION)
                .from(PACK_REGISTRY)
                .where(mine(packId))
                .fetchOne(PACK_REGISTRY.PACK_VERSION),
        ) { "no mapping pack $packId for the bound tenant" }

    private fun entryOf(version: String): Condition =
        PACK_ENTRY_SOURCE.PACK_TYPE
            .eq(PACK_TYPE)
            .and(PACK_ENTRY_SOURCE.TENANT_ID.eq(tenants.current().value))
            .and(PACK_ENTRY_SOURCE.PACK_VERSION.eq(version))

    /** True when the tenant already holds a mapping version with this label (the label is the version label). */
    fun labelTaken(label: String): Boolean =
        dsl.fetchExists(
            PACK_REGISTRY,
            PACK_REGISTRY.PACK_TYPE
                .eq(PACK_TYPE)
                .and(PACK_REGISTRY.TENANT_ID.eq(tenants.current().value))
                .and(PACK_REGISTRY.PACK_VERSION.eq(label)),
        )

    override fun draft(pack: MappingPackDraft) {
        dsl
            .insertInto(PACK_REGISTRY)
            .set(PACK_REGISTRY.ID, UUID.randomUUID())
            .set(PACK_REGISTRY.TENANT_ID, pack.tenant.value)
            .set(PACK_REGISTRY.PACK_ID, pack.packId)
            .set(PACK_REGISTRY.PACK_TYPE, PACK_TYPE)
            .set(PACK_REGISTRY.PACK_VERSION, pack.label)
            .set(PACK_REGISTRY.LABEL, pack.label)
            .set(PACK_REGISTRY.LAYER, PackLayer.TENANT_MAPPING.name)
            .set(PACK_REGISTRY.STATUS, PackStatus.DRAFT.name)
            .set(PACK_REGISTRY.MIN_ENGINE_VERSION, DRAFT_MIN_ENGINE_VERSION)
            .set(PACK_REGISTRY.ENTITY_REF, pack.entityRef)
            .set(PACK_REGISTRY.REGISTERED_BY, checkNotNull(tenants.currentActor()) { "no actor is bound" })
            .execute()
    }

    override fun find(packId: String): MappingPackDraft? =
        dsl.selectFrom(PACK_REGISTRY).where(mine(packId)).fetchOne()?.let { row ->
            MappingPackDraft(
                packId = packId,
                tenant = TenantId(checkNotNull(row.tenantId)),
                entityRef = checkNotNull(row.entityRef) { "a mapping pack names its entity" },
                label = checkNotNull(row.label) { "a mapping pack carries its label" },
                authorUserId = checkNotNull(row.registeredBy),
                status = PackStatus.valueOf(checkNotNull(row.status)),
            )
        }

    override fun replaceEntry(
        packId: String,
        entry: MappingDraftEntry,
    ) {
        val version = versionOf(packId)
        dsl
            .insertInto(PACK_ENTRY_SOURCE)
            .set(PACK_ENTRY_SOURCE.ID, UUID.randomUUID())
            .set(PACK_ENTRY_SOURCE.TENANT_ID, tenants.current().value)
            .set(PACK_ENTRY_SOURCE.PACK_TYPE, PACK_TYPE)
            .set(PACK_ENTRY_SOURCE.PACK_VERSION, version)
            .set(PACK_ENTRY_SOURCE.ENTRY_ID, entry.entryId)
            .set(PACK_ENTRY_SOURCE.INTERNAL_TAX_CODE, entry.entryId)
            .set(PACK_ENTRY_SOURCE.IRAS_CODE, entry.irasCode)
            .set(PACK_ENTRY_SOURCE.SOURCE_CLASS, entry.sourceClass.name)
            .set(PACK_ENTRY_SOURCE.ACCEPTED, false)
            .onConflict(
                PACK_ENTRY_SOURCE.PACK_TYPE,
                PACK_ENTRY_SOURCE.TENANT_ID,
                PACK_ENTRY_SOURCE.PACK_VERSION,
                PACK_ENTRY_SOURCE.ENTRY_ID,
            ).doUpdate()
            .set(PACK_ENTRY_SOURCE.IRAS_CODE, entry.irasCode)
            .set(PACK_ENTRY_SOURCE.SOURCE_CLASS, entry.sourceClass.name)
            .set(PACK_ENTRY_SOURCE.ACCEPTED, false)
            .execute()
        // a delta computed before this change no longer qualifies (INV-Q-14646 A)
        dsl
            .update(PACK_REGISTRY)
            .set(PACK_REGISTRY.CONTENT_CHANGED_AT, DSL.currentOffsetDateTime())
            .where(mine(packId))
            .execute()
    }

    override fun listEntries(
        packId: String,
        after: String?,
        limit: Int,
    ): List<MappingDraftEntry> =
        entryViews(packId, after, limit, null, null).map { MappingDraftEntry(it.entryId, it.irasCode, it.sourceClass) }

    /** The entries ordered by entry id after the cursor (exclusive), the optional filters combined with AND. */
    fun entryViews(
        packId: String,
        after: String?,
        limit: Int,
        sourceClass: SourceClass?,
        accepted: Boolean?,
    ): List<MappingEntryView> {
        val version = versionOf(packId)
        var where = entryOf(version)
        after?.let { where = where.and(PACK_ENTRY_SOURCE.ENTRY_ID.gt(it)) }
        sourceClass?.let { where = where.and(PACK_ENTRY_SOURCE.SOURCE_CLASS.eq(it.name)) }
        accepted?.let { where = where.and(PACK_ENTRY_SOURCE.ACCEPTED.eq(it)) }
        return entryViewsWhere(where, limit)
    }

    private fun entryViewsWhere(
        where: Condition,
        limit: Int,
    ): List<MappingEntryView> =
        dsl
            .selectFrom(PACK_ENTRY_SOURCE)
            .where(where)
            .orderBy(PACK_ENTRY_SOURCE.ENTRY_ID)
            .limit(limit)
            .fetch()
            .map {
                MappingEntryView(
                    checkNotNull(it.entryId),
                    checkNotNull(it.internalTaxCode),
                    checkNotNull(it.irasCode),
                    SourceClass.valueOf(checkNotNull(it.sourceClass)),
                    checkNotNull(it.accepted),
                    it.schemeTag,
                )
            }

    /** One entry of the pack by its entry id; null when the pack has none. */
    fun entry(
        packId: String,
        entryId: String,
    ): MappingEntryView? {
        val version = versionOf(packId)
        return entryViewsWhere(entryOf(version).and(PACK_ENTRY_SOURCE.ENTRY_ID.eq(entryId)), 1).firstOrNull()
    }

    /** The header and the counts of `getMappingPack`; null when the tenant holds no such pack. */
    fun view(packId: String): MappingPackView? {
        val row = dsl.selectFrom(PACK_REGISTRY).where(mine(packId)).fetchOne() ?: return null
        val version = checkNotNull(row.packVersion)
        val entries = entryOf(version)
        val nonPrimary = entries.and(PACK_ENTRY_SOURCE.SOURCE_CLASS.ne(SourceClass.PRIMARY.name))

        fun count(condition: Condition): Int = dsl.fetchCount(PACK_ENTRY_SOURCE, condition)
        return MappingPackView(
            packId = packId,
            packVersion = version,
            status = PackStatus.valueOf(checkNotNull(row.status)),
            effectiveFrom = row.effectiveFrom,
            entryCount = count(entries),
            nonPrimaryCount = count(nonPrimary),
            unacceptedNonPrimaryCount = count(nonPrimary.and(PACK_ENTRY_SOURCE.ACCEPTED.eq(false))),
        )
    }

    private fun unexpiredAcceptance(
        version: String,
        entryId: String,
    ): Boolean =
        dsl.fetchExists(
            MAPPING_ENTRY_ACCEPTANCE,
            MAPPING_ENTRY_ACCEPTANCE.PACK_TYPE
                .eq(PACK_TYPE)
                .and(MAPPING_ENTRY_ACCEPTANCE.TENANT_ID.eq(tenants.current().value))
                .and(MAPPING_ENTRY_ACCEPTANCE.PACK_VERSION.eq(version))
                .and(MAPPING_ENTRY_ACCEPTANCE.ENTRY_ID.eq(entryId))
                .and(MAPPING_ENTRY_ACCEPTANCE.EXPIRES_ON.gt(LocalDate.now(clock.withZone(ZoneOffset.UTC)))),
        )

    override fun recordAcceptance(
        packId: String,
        acceptance: MappingAcceptanceRecord,
    ) {
        val version = versionOf(packId)
        // a repeat while the acceptance of the entry has not expired stores nothing (contract repeatKey, INV-REQ-380)
        if (unexpiredAcceptance(version, acceptance.entryId)) return
        dsl
            .insertInto(MAPPING_ENTRY_ACCEPTANCE)
            .set(MAPPING_ENTRY_ACCEPTANCE.TENANT_ID, tenants.current().value)
            .set(MAPPING_ENTRY_ACCEPTANCE.PACK_TYPE, PACK_TYPE)
            .set(MAPPING_ENTRY_ACCEPTANCE.PACK_VERSION, version)
            .set(MAPPING_ENTRY_ACCEPTANCE.ENTRY_ID, acceptance.entryId)
            .set(MAPPING_ENTRY_ACCEPTANCE.RATIONALE, acceptance.rationale)
            .set(MAPPING_ENTRY_ACCEPTANCE.EXPIRES_ON, acceptance.expiresOn)
            .set(MAPPING_ENTRY_ACCEPTANCE.ACCEPTED_BY, acceptance.acceptedBy)
            .set(MAPPING_ENTRY_ACCEPTANCE.ACCEPTED_IRAS_CODE, acceptance.acceptedIrasCode)
            .set(MAPPING_ENTRY_ACCEPTANCE.ACCEPTED_SOURCE_CLASS, acceptance.acceptedSourceClass.name)
            .onConflict(
                MAPPING_ENTRY_ACCEPTANCE.PACK_TYPE,
                MAPPING_ENTRY_ACCEPTANCE.TENANT_ID,
                MAPPING_ENTRY_ACCEPTANCE.PACK_VERSION,
                MAPPING_ENTRY_ACCEPTANCE.ENTRY_ID,
            ).doUpdate()
            .set(MAPPING_ENTRY_ACCEPTANCE.RATIONALE, acceptance.rationale)
            .set(MAPPING_ENTRY_ACCEPTANCE.EXPIRES_ON, acceptance.expiresOn)
            .set(MAPPING_ENTRY_ACCEPTANCE.ACCEPTED_BY, acceptance.acceptedBy)
            .set(MAPPING_ENTRY_ACCEPTANCE.ACCEPTED_AT, DSL.currentOffsetDateTime())
            .set(MAPPING_ENTRY_ACCEPTANCE.ACCEPTED_IRAS_CODE, acceptance.acceptedIrasCode)
            .set(MAPPING_ENTRY_ACCEPTANCE.ACCEPTED_SOURCE_CLASS, acceptance.acceptedSourceClass.name)
            .execute()
        dsl
            .update(PACK_ENTRY_SOURCE)
            .set(PACK_ENTRY_SOURCE.ACCEPTED, true)
            .where(entryOf(version).and(PACK_ENTRY_SOURCE.ENTRY_ID.eq(acceptance.entryId)))
            .execute()
    }

    override fun acceptances(packId: String): List<MappingAcceptanceRecord> {
        val version = versionOf(packId)
        return dsl
            .selectFrom(MAPPING_ENTRY_ACCEPTANCE)
            .where(
                MAPPING_ENTRY_ACCEPTANCE.PACK_TYPE
                    .eq(PACK_TYPE)
                    .and(MAPPING_ENTRY_ACCEPTANCE.TENANT_ID.eq(tenants.current().value))
                    .and(MAPPING_ENTRY_ACCEPTANCE.PACK_VERSION.eq(version)),
            ).orderBy(MAPPING_ENTRY_ACCEPTANCE.ENTRY_ID)
            .fetch()
            .map {
                MappingAcceptanceRecord(
                    checkNotNull(it.entryId),
                    checkNotNull(it.rationale),
                    checkNotNull(it.expiresOn),
                    checkNotNull(it.acceptedIrasCode),
                    SourceClass.valueOf(checkNotNull(it.acceptedSourceClass)),
                    checkNotNull(it.acceptedBy),
                )
            }
    }

    override fun approvals(packId: String): List<MappingApproval> {
        val version = versionOf(packId)
        return dsl
            .selectFrom(MAPPING_PACK_APPROVAL)
            .where(
                MAPPING_PACK_APPROVAL.PACK_TYPE
                    .eq(PACK_TYPE)
                    .and(MAPPING_PACK_APPROVAL.TENANT_ID.eq(tenants.current().value))
                    .and(MAPPING_PACK_APPROVAL.PACK_VERSION.eq(version)),
            ).orderBy(MAPPING_PACK_APPROVAL.APPROVED_AT, MAPPING_PACK_APPROVAL.APPROVED_BY)
            .fetch()
            .map {
                MappingApproval(
                    approverUserId = checkNotNull(it.approvedBy),
                    at = checkNotNull(it.approvedAt).toInstant(),
                    deltaId = checkNotNull(it.dryRunDeltaId).toString(),
                    soleOperator = checkNotNull(it.soleOperator),
                    soleOperatorAckRef = it.soleOperatorAckRef,
                    option = checkNotNull(it.optionKey),
                    outstanding =
                        buildSet {
                            if (checkNotNull(it.outstandingClientAcknowledgement)) add(CLIENT_ACKNOWLEDGEMENT)
                            if (checkNotNull(it.outstandingClientApproval)) add(CLIENT_APPROVAL)
                        },
                )
            }
    }

    override fun recordApproval(
        packId: String,
        approval: MappingApproval,
        resultingStatus: PackStatus,
    ) {
        val version = versionOf(packId)
        dsl
            .insertInto(MAPPING_PACK_APPROVAL)
            .set(MAPPING_PACK_APPROVAL.TENANT_ID, tenants.current().value)
            .set(MAPPING_PACK_APPROVAL.PACK_TYPE, PACK_TYPE)
            .set(MAPPING_PACK_APPROVAL.PACK_VERSION, version)
            .set(MAPPING_PACK_APPROVAL.APPROVED_BY, approval.approverUserId)
            .set(MAPPING_PACK_APPROVAL.APPROVED_AT, approval.at.atOffset(ZoneOffset.UTC))
            .set(MAPPING_PACK_APPROVAL.DRY_RUN_DELTA_ID, UUID.fromString(approval.deltaId))
            .set(MAPPING_PACK_APPROVAL.SOLE_OPERATOR, approval.soleOperator)
            .set(MAPPING_PACK_APPROVAL.SOLE_OPERATOR_ACK_REF, approval.soleOperatorAckRef)
            .set(MAPPING_PACK_APPROVAL.OPTION_KEY, approval.option)
            .set(
                MAPPING_PACK_APPROVAL.OUTSTANDING_CLIENT_ACKNOWLEDGEMENT,
                CLIENT_ACKNOWLEDGEMENT in approval.outstanding,
            ).set(MAPPING_PACK_APPROVAL.OUTSTANDING_CLIENT_APPROVAL, CLIENT_APPROVAL in approval.outstanding)
            .execute()
        setStatus(packId, resultingStatus)
    }

    override fun setStatus(
        packId: String,
        status: PackStatus,
    ) {
        dsl
            .update(PACK_REGISTRY)
            .set(PACK_REGISTRY.STATUS, status.name)
            .where(mine(packId))
            .execute()
    }

    override fun recordActivation(
        packId: String,
        activation: MappingActivation,
    ) {
        // the version this one supersedes: the latest armed version of the tenant that starts before it (INV-REQ-1089);
        // that row is not modified, so it stays resolvable for the dates before this one's effective-from
        val predecessor =
            dsl
                .select(PACK_REGISTRY.PACK_VERSION)
                .from(PACK_REGISTRY)
                .where(
                    PACK_REGISTRY.PACK_TYPE
                        .eq(PACK_TYPE)
                        .and(PACK_REGISTRY.TENANT_ID.eq(tenants.current().value))
                        .and(PACK_REGISTRY.STATUS.eq(PackStatus.ARMED.name))
                        .and(PACK_REGISTRY.EFFECTIVE_FROM.lt(activation.effectiveFrom)),
                ).orderBy(PACK_REGISTRY.EFFECTIVE_FROM.desc())
                .limit(1)
                .fetchOne(PACK_REGISTRY.PACK_VERSION)
        dsl
            .update(PACK_REGISTRY)
            .set(PACK_REGISTRY.STATUS, PackStatus.ARMED.name)
            .set(
                PACK_REGISTRY.MIN_ENGINE_VERSION,
                checkNotNull(engineVersion.current()) { "no engine version at activation" },
            ).set(PACK_REGISTRY.EFFECTIVE_FROM, activation.effectiveFrom)
            .set(PACK_REGISTRY.EFFECTIVE_TO, activation.effectiveTo)
            .set(PACK_REGISTRY.SUPERSEDES, predecessor)
            .set(PACK_REGISTRY.ACTIVATED_BY, activation.activatedBy)
            .set(PACK_REGISTRY.ACTIVATED_AT, activation.at.atOffset(ZoneOffset.UTC))
            .where(mine(packId))
            .execute()
    }

    private companion object {
        const val PACK_TYPE = "MAPPING"
        const val CLIENT_ACKNOWLEDGEMENT = "CLIENT_ACKNOWLEDGEMENT"
        const val CLIENT_APPROVAL = "CLIENT_APPROVAL"

        /** The registry requires a value; the draft has none yet and the holder copy carries the real one. */
        const val DRAFT_MIN_ENGINE_VERSION = "0.0.0"
    }
}
