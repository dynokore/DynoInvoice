package com.dynokore.gst.rules.adapters.persistence

import com.dynokore.gst.rules.adapters.persistence.jooq.tables.records.PackArtefactRecord
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_ARTEFACT
import com.dynokore.platform.rulepackkernel.Checksum
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackRegistry
import com.dynokore.platform.rulepackkernel.PackRegistryMigration
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.VerifiedPack
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * rules-svc's own holder copy of the verified packs, the [PackRegistry] over `rules.pack_artefact` (INV-REQ-1195,
 * INV-REQ-1196, INV-Q-7438): the only code that reads or writes that table, jOOQ only (INV-T-4201). It is never served:
 * no operation returns what [artefactOf] returns to another service (INV-AC-2182).
 *
 * It runs inside the open transaction of the command, under the signed tenant binding that tenancy-jooq and row-level
 * security read: a platform pack is written by the kernel's `rules.register_platform_pack` under the platform binding,
 * a tenant pack by an insert whose `registered_by` is the actor bound in the transaction (the kernel trigger refuses
 * any other value). The date of [resolve] is always the caller's, never the clock (ARC-13).
 */
class PersistedPackRegistry(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
    private val json: JsonMapper = JsonMapper(),
) : PackRegistry {
    /** A [PackVersion] alone carries no signed artefact and no anchor, so nothing is written (INV-Q-8206). */
    override fun register(version: PackVersion): Unit =
        throw UnsupportedOperationException("a persisted pack needs its signed artefact: use register(VerifiedPack)")

    override fun register(pack: VerifiedPack) {
        val version = pack.version
        val stored = rowOf(version.packType, version.tenant, version.version)
        if (stored != null) {
            // at-least-once redelivery of RulePackStaged: the same manifest is a no-op, another one is refused
            require(manifestOf(stored) == version.manifest) {
                "conflicting re-registration of an immutable pack version: ${version.packType} ${version.version} " +
                    "for tenant ${version.tenant} already carries a different manifest (INV-ADR-263)"
            }
            return
        }
        val tenant = version.tenant
        if (tenant == null) registerPlatform(pack) else registerTenant(pack, tenant)
    }

    override fun resolve(
        packType: PackType,
        tenant: TenantId?,
        resolutionDate: LocalDate,
    ): PackVersion? {
        val owners = listOfNotNull(tenant?.value, TenantId.PLATFORM.value)
        val row =
            dsl
                .selectFrom(PACK_ARTEFACT)
                .where(PACK_ARTEFACT.PACK_TYPE.eq(packType.value))
                .and(PACK_ARTEFACT.TENANT_ID.`in`(owners))
                .and(PACK_ARTEFACT.EFFECTIVE_FROM.le(resolutionDate))
                .and(PACK_ARTEFACT.EFFECTIVE_TO.isNull.or(PACK_ARTEFACT.EFFECTIVE_TO.gt(resolutionDate)))
                .orderBy(
                    DSL.`when`(PACK_ARTEFACT.TENANT_ID.eq(TenantId.PLATFORM.value), 1).otherwise(0),
                    PACK_ARTEFACT.EFFECTIVE_FROM.desc(),
                ).limit(1)
                .fetchOne() ?: return null
        return versionOf(row)
    }

    /** The stored signed artefact of a resolved [version], or null when none is held. Read by rules-svc only. */
    fun artefactOf(version: PackVersion): PackArtefact? {
        val row = rowOf(version.packType, version.tenant, version.version) ?: return null
        return PackArtefact(
            manifest = column(row.manifest, "manifest").data().toByteArray(Charsets.UTF_8),
            checksums = column(row.checksums, "checksums").data().toByteArray(Charsets.UTF_8),
            signature = column(row.signature, "signature"),
            contents = column(row.contents, "contents").data().toByteArray(Charsets.UTF_8),
        )
    }

    private fun registerPlatform(pack: VerifiedPack) {
        val m = pack.version.manifest
        val function =
            DSL.function(
                DSL.name("rules", PackRegistryMigration.REGISTER_PLATFORM_PACK),
                SQLDataType.UUID,
                DSL.`val`(UUID.randomUUID(), SQLDataType.UUID),
                DSL.`val`(m.packId.value, SQLDataType.CLOB),
                DSL.`val`(m.packType.value, SQLDataType.CLOB),
                DSL.`val`(m.version.value, SQLDataType.CLOB),
                DSL.`val`(m.effectiveFrom, SQLDataType.LOCALDATE),
                DSL.`val`(m.effectiveTo, SQLDataType.LOCALDATE),
                DSL.`val`(m.minEngineVersion, SQLDataType.CLOB),
                jsonb(pack.artefact.manifest),
                jsonb(pack.artefact.checksums),
                DSL.`val`(pack.artefact.signature, SQLDataType.BLOB),
                jsonb(pack.artefact.contents),
                DSL.`val`(pack.trustedAnchorLabel, SQLDataType.CLOB),
            )
        dsl.select(function).fetchOne()
    }

    private fun registerTenant(
        pack: VerifiedPack,
        tenant: TenantId,
    ) {
        val m = pack.version.manifest
        dsl
            .insertInto(PACK_ARTEFACT)
            .set(PACK_ARTEFACT.ID, UUID.randomUUID())
            .set(PACK_ARTEFACT.PACK_ID, m.packId.value)
            .set(PACK_ARTEFACT.PACK_TYPE, m.packType.value)
            .set(PACK_ARTEFACT.PACK_VERSION, m.version.value)
            .set(PACK_ARTEFACT.TENANT_ID, tenant.value)
            .set(PACK_ARTEFACT.EFFECTIVE_FROM, m.effectiveFrom)
            .set(PACK_ARTEFACT.EFFECTIVE_TO, m.effectiveTo)
            .set(PACK_ARTEFACT.MIN_ENGINE_VERSION, m.minEngineVersion)
            .set(PACK_ARTEFACT.MANIFEST, jsonb(pack.artefact.manifest))
            .set(PACK_ARTEFACT.CHECKSUMS, jsonb(pack.artefact.checksums))
            .set(PACK_ARTEFACT.SIGNATURE, pack.artefact.signature)
            .set(PACK_ARTEFACT.CONTENTS, jsonb(pack.artefact.contents))
            .set(PACK_ARTEFACT.TRUSTED_ANCHOR, pack.trustedAnchorLabel)
            .set(PACK_ARTEFACT.REGISTERED_BY, checkNotNull(tenants.currentActor()) { "no actor is bound" })
            .execute()
    }

    private fun rowOf(
        packType: PackType,
        tenant: TenantId?,
        label: PackVersionLabel,
    ) = dsl
        .selectFrom(PACK_ARTEFACT)
        .where(PACK_ARTEFACT.PACK_TYPE.eq(packType.value))
        .and(PACK_ARTEFACT.TENANT_ID.eq((tenant ?: TenantId.PLATFORM).value))
        .and(PACK_ARTEFACT.PACK_VERSION.eq(label.value))
        .fetchOne()

    private fun versionOf(row: PackArtefactRecord): PackVersion {
        val owner = column(row.tenantId, "tenant_id")
        return PackVersion(manifestOf(row), if (owner == TenantId.PLATFORM.value) null else TenantId(owner))
    }

    /** Rebuilt from the identity and date columns and the `checksums` member: no manifest parser (INV-T-7525). */
    private fun manifestOf(row: PackArtefactRecord) =
        PackManifest(
            packId = PackId(column(row.packId, "pack_id")),
            packType = PackType(column(row.packType, "pack_type")),
            version = PackVersionLabel(column(row.packVersion, "pack_version")),
            effectiveFrom = column(row.effectiveFrom, "effective_from"),
            effectiveTo = row.effectiveTo,
            minEngineVersion = column(row.minEngineVersion, "min_engine_version"),
            contentChecksums =
                json
                    .readValue(column(row.checksums, "checksums").data(), CHECKSUMS)
                    .mapValues { Checksum(it.value) },
        )

    /** The member's UTF-8 JSON text bound as a value and cast to `jsonb` by the database (no text-SQL type). */
    private fun jsonb(bytes: ByteArray): Field<JSONB> =
        DSL.cast(DSL.`val`(String(bytes, Charsets.UTF_8)), SQLDataType.JSONB)

    private fun <T : Any> column(
        value: T?,
        name: String,
    ): T = checkNotNull(value) { "pack_artefact.$name is NOT NULL in the schema but came back null" }

    private companion object {
        val CHECKSUMS = object : TypeReference<Map<String, String>>() {}
    }
}
