package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_IMPORT
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_REGISTRY
import com.dynokore.gst.rules.application.ConflictingPackVersionException
import com.dynokore.gst.rules.application.ImportFailure
import com.dynokore.gst.rules.application.ImportStatus
import com.dynokore.gst.rules.application.PackEvent
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.application.PackImportStore
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.rulepackkernel.VerifiedPack
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The import state in `rules.pack_import` and the one transaction that stores a verified pack (INV-Q-7438 A): the
 * artefact through the persisted holder copy, the registry row (STAGED) and the `RulePackStaged` outbox row. Every call
 * runs under the platform binding with the importing user ID as the actor and opens its own transaction.
 */
class JooqPackImportStore(
    private val dsl: DSLContext,
    private val platform: PlatformTenantBinding,
    private val transactions: TransactionRunner,
    private val registry: PersistedPackRegistry,
    private val events: PackEventEmitter,
    private val json: JsonMapper = JsonMapper(),
) : PackImportStore {
    private val owner = TenantId.PLATFORM.value

    override fun begin(
        packType: String,
        version: String,
        bundleHash: String,
        actor: String,
    ) = inTx(actor) {
        dsl
            .insertInto(PACK_IMPORT)
            .set(PACK_IMPORT.TENANT_ID, owner)
            .set(PACK_IMPORT.PACK_TYPE, packType)
            .set(PACK_IMPORT.PACK_VERSION, version)
            .set(PACK_IMPORT.STATUS, IMPORTING)
            .set(PACK_IMPORT.BUNDLE_HASH, bundleHash)
            .set(PACK_IMPORT.ACTOR, actor)
            .onConflictOnConstraint(DSL.name("pack_import_pkey"))
            .doUpdate()
            .set(PACK_IMPORT.STATUS, IMPORTING)
            .set(PACK_IMPORT.FAILURE_CODE, DSL.inline(null as String?))
            .set(PACK_IMPORT.FINISHED_AT, DSL.inline(null as OffsetDateTime?))
            .set(PACK_IMPORT.BUNDLE_HASH, bundleHash)
            .set(PACK_IMPORT.ACTOR, actor)
            .set(PACK_IMPORT.STARTED_AT, DSL.currentOffsetDateTime())
            .where(PACK_IMPORT.STATUS.eq(FAILED))
            .execute()
        Unit
    }

    override fun fail(
        packType: String,
        version: String,
        failure: ImportFailure,
    ) = inTx(ACTOR_OF_FAILURE) {
        dsl
            .update(PACK_IMPORT)
            .set(PACK_IMPORT.STATUS, FAILED)
            .set(PACK_IMPORT.FAILURE_CODE, failure.code)
            .set(PACK_IMPORT.FINISHED_AT, DSL.currentOffsetDateTime())
            .where(key(packType, version))
            .and(PACK_IMPORT.STATUS.eq(IMPORTING))
            .execute()
        Unit
    }

    override fun stage(
        pack: VerifiedPack,
        actor: String,
    ) = inTx(actor) {
        val m = pack.version.manifest
        val known = registryRow(m.packType.value, m.version.value)
        try {
            registry.register(pack)
        } catch (e: IllegalArgumentException) {
            throw ConflictingPackVersionException(e.message ?: "conflicting pack version", e)
        }
        if (!known) {
            registerMetadata(pack)
            events.emit(listOf(staged(pack)), context(m.packId.value, m.version.value))
        }
        // published only for a pack the store finds afterwards by (pack type, tenant, pack version)
        checkNotNull(registry.artefactOf(pack.version)) { "the stored pack is not found by its version" }
        dsl
            .update(PACK_IMPORT)
            .set(PACK_IMPORT.STATUS, STAGED)
            .set(PACK_IMPORT.FINISHED_AT, DSL.currentOffsetDateTime())
            .where(key(m.packType.value, m.version.value))
            .and(PACK_IMPORT.STATUS.eq(IMPORTING))
            .execute()
        Unit
    }

    override fun status(
        packType: String,
        version: String,
    ): ImportStatus? =
        inTx(READER) {
            val imported =
                dsl
                    .select(PACK_IMPORT.STATUS, PACK_IMPORT.FAILURE_CODE)
                    .from(PACK_IMPORT)
                    .where(key(packType, version))
                    .fetchOne()
            if (imported != null) {
                ImportStatus(checkNotNull(imported[PACK_IMPORT.STATUS]), imported[PACK_IMPORT.FAILURE_CODE])
            } else {
                dsl
                    .select(PACK_REGISTRY.STATUS)
                    .from(PACK_REGISTRY)
                    .where(PACK_REGISTRY.PACK_TYPE.eq(packType))
                    .and(PACK_REGISTRY.PACK_VERSION.eq(version))
                    .and(PACK_REGISTRY.TENANT_ID.eq(owner))
                    .fetchOne()
                    ?.let { ImportStatus(checkNotNull(it[PACK_REGISTRY.STATUS]), null) }
            }
        }

    private fun key(
        packType: String,
        version: String,
    ) = PACK_IMPORT.TENANT_ID
        .eq(owner)
        .and(PACK_IMPORT.PACK_TYPE.eq(packType))
        .and(PACK_IMPORT.PACK_VERSION.eq(version))

    private fun registryRow(
        packType: String,
        version: String,
    ) = dsl.fetchExists(
        DSL
            .selectOne()
            .from(PACK_REGISTRY)
            .where(PACK_REGISTRY.PACK_TYPE.eq(packType))
            .and(PACK_REGISTRY.PACK_VERSION.eq(version))
            .and(PACK_REGISTRY.TENANT_ID.eq(owner)),
    )

    /** The registry metadata row, STAGED, by the platform function that records the bound actor. */
    private fun registerMetadata(pack: VerifiedPack) {
        val m = pack.version.manifest
        dsl
            .select(
                DSL.function(
                    DSL.name("rules", "register_platform_pack"),
                    SQLDataType.UUID,
                    DSL.`val`(UUID.randomUUID(), SQLDataType.UUID),
                    DSL.`val`(m.packId.value, SQLDataType.CLOB),
                    DSL.`val`(m.packType.value, SQLDataType.CLOB),
                    DSL.`val`(m.version.value, SQLDataType.CLOB),
                    DSL.`val`(STAGED, SQLDataType.CLOB),
                    DSL.`val`(m.effectiveFrom, SQLDataType.LOCALDATE),
                    DSL.`val`(m.effectiveTo, SQLDataType.LOCALDATE),
                    DSL.`val`(m.minEngineVersion, SQLDataType.CLOB),
                    DSL.`val`(null as String?, SQLDataType.CLOB),
                ),
            ).fetchOne()
    }

    private fun staged(pack: VerifiedPack): PackEvent.RulePackStaged {
        val m = pack.version.manifest
        val strings = object : TypeReference<Map<String, String?>>() {}
        return PackEvent.RulePackStaged(
            packIdentifier = m.packId.value,
            packType = m.packType.value,
            version = m.version.value,
            effectiveFrom = m.effectiveFrom,
            minEngineVersion = m.minEngineVersion,
            manifest = present(json.readValue(pack.artefact.manifest, strings)),
            checksums = present(json.readValue(pack.artefact.checksums, strings)),
            signature =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(pack.artefact.signature),
        )
    }

    /** The members that carry a value: a manifest states `effectiveTo: null` for a pack with no end. */
    private fun present(members: Map<String, String?>): Map<String, String> =
        members.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    private fun context(
        packId: String,
        version: String,
    ): EventContext {
        val correlation =
            com.dynokore.platform.serviceruntime.logging.CorrelationContext
                .currentCorrelationId()
                ?.value ?: UUID.randomUUID().toString()
        return EventContext(
            tenant = TenantId.PLATFORM,
            entity = EntityRef.PLATFORM,
            docUuid = null,
            transactionDate = null,
            aggregateKey = "$packId@$version",
            aggregateVersion = null,
            correlation = CorrelationId(correlation),
            causation = CausationId(correlation),
        )
    }

    private fun <T> inTx(
        actor: String,
        block: () -> T,
    ): T = platform.bindPlatform(actor) { transactions.inTransaction { block() } }

    companion object {
        const val IMPORTING = "IMPORTING"
        const val STAGED = "STAGED"
        const val FAILED = "FAILED"
        private const val READER = "svc:rules-svc/op:get-regulatory-pack"
        private const val ACTOR_OF_FAILURE = "svc:rules-svc/op:import-regulatory-pack"
    }
}
