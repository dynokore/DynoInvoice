package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.Converter
import org.jooq.DSLContext
import org.jooq.DataType
import org.jooq.Field
import org.jooq.Name
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The write path and the history through jOOQ's DSL on the DSLContext of tenancy-jooq's factory, in the caller's open
 * transaction. Tenant rows always carry an explicit `tenant_id` predicate as well as RLS (defence in depth, TPL-11).
 * Identifiers are `DSL.name` of the [SqlIdentifier]-validated schema and constant names; every value is a bind value;
 * the definer function [ConfigSchema.WRITE_INSTALLATION_SETTING] is a jOOQ function call.
 */
internal class JdbcSettingWrites(
    private val dsl: DSLContext,
    schema: String,
    private val columns: SelectedOptionsJooq = SelectedOptionsJooq(),
) {
    private val s = SqlIdentifier.require(schema, "schema")
    private val settings: Table<*> = DSL.table(DSL.name(s, ConfigSchema.SETTING))
    private val installation: Table<*> = DSL.table(DSL.name(s, ConfigSchema.INSTALLATION_SETTING))
    private val acknowledgements: Table<*> = DSL.table(DSL.name(s, ConfigSchema.RELAXATION_ACKNOWLEDGEMENT))
    private val writeInstallation: Name = DSL.name(s, ConfigSchema.WRITE_INSTALLATION_SETTING)

    private val id = column("id", SQLDataType.UUID)
    private val tenantId = column("tenant_id", SQLDataType.UUID)
    private val entityId = column("entity_id", SQLDataType.CLOB)
    private val vpKey = column("vp_key", SQLDataType.CLOB)
    private val scopeLevel = column("scope_level", SQLDataType.CLOB)
    private val scopeId = column("scope_id", SQLDataType.CLOB)
    private val source = column("source", SQLDataType.CLOB)
    private val profileId = column("profile_id", SQLDataType.CLOB)
    private val profileVersion = column("profile_version", SQLDataType.CLOB)
    private val effectiveFrom = column("effective_from", SQLDataType.LOCALDATE)
    private val setBy = column("set_by", SQLDataType.CLOB)
    private val reason = column("reason", SQLDataType.CLOB)
    private val recordedAt = column("recorded_at", SQLDataType.TIMESTAMPWITHTIMEZONE)
    private val relaxationRef = column("relaxation_ref", SQLDataType.UUID)
    private val relaxationId = column("relaxation_id", SQLDataType.UUID)
    private val role = column("role", SQLDataType.CLOB)
    private val principal = column("principal", SQLDataType.CLOB)
    private val acknowledgedAt = column("acknowledged_at", SQLDataType.TIMESTAMPWITHTIMEZONE)
    private val seq = column("seq", SQLDataType.BIGINT)

    /** Stores a tenant-and-below value; an installation value goes through [writeInstallation]. */
    fun insert(version: SettingVersion) {
        val target = version.target
        require(target.level != ScopeLevel.INSTALLATION) { "an installation value is written by $writeInstallation" }
        dsl
            .insertInto(settings)
            .set(id, version.id)
            .set(tenantId, checkNotNull(target.tenant).value)
            .set(entityId, target.entity)
            .set(vpKey, version.key.value)
            .set(scopeLevel, target.level.name)
            .set(scopeId, target.ref.id)
            .set(columns.keys, columns.keysOf(version.selected))
            .set(columns.values, columns.valuesOf(version.selected))
            .set(source, version.source.name)
            .set(profileId, version.profileId)
            .set(profileVersion, version.profileVersion)
            .set(effectiveFrom, version.effectiveFrom)
            .set(setBy, version.setBy)
            .set(reason, version.reason)
            .set(recordedAt, version.recordedAt.atOffset(ZoneOffset.UTC))
            .set(relaxationRef, version.relaxation?.value)
            .execute()
    }

    /**
     * Stores an installation value through [ConfigSchema.WRITE_INSTALLATION_SETTING] with its staged audit row and,
     * in a service with an outbox, its staged `PolicyChanged` row (JSON objects of their outbox columns,
     * [StagedRows]); the function checks them, sets the id, actor and time itself and writes all of them or none
     * (coordinator rulings INV-Q-4254 A, INV-Q-4316).
     */
    fun writeInstallation(
        version: SettingVersion,
        audit: String,
        policyChanged: String?,
    ): StoredInstallation {
        require(version.target.level == ScopeLevel.INSTALLATION) { "not an installation value" }
        // The function returns one row (setting_id, stored_at): called as a function, its row is one composite value.
        val stored =
            dsl
                .select(
                    DSL.function(
                        writeInstallation,
                        STORED,
                        DSL.`val`(version.key.value),
                        DSL.`val`(columns.keysOf(version.selected), columns.keys.dataType),
                        columns.valuesOf(version.selected),
                        DSL.`val`(version.effectiveFrom),
                        DSL.`val`(version.reason),
                        DSL.`val`(audit).cast(SQLDataType.JSONB),
                        DSL.`val`(policyChanged, SQLDataType.CLOB).cast(SQLDataType.JSONB),
                    ),
                ).fetchOne { it.value1() }
        return checkNotNull(stored) { "the installation write returned no row" }
    }

    /** Every version of [key] at [target], oldest recorded first. */
    fun versions(
        key: VariationPointKey,
        target: SettingTarget,
    ): List<SettingVersion> =
        if (target.level == ScopeLevel.INSTALLATION) {
            dsl
                .select(id, columns.keys, columns.values, effectiveFrom, setBy, reason, recordedAt)
                .from(installation)
                .where(vpKey.eq(key.value))
                .orderBy(seq)
                .fetch { row ->
                    // Installation values are overrides: no profile and no relaxation applies there.
                    version(row, key, target, SettingSource.OVERRIDE, null, null, null)
                }
        } else {
            dsl
                .select(
                    id,
                    columns.keys,
                    columns.values,
                    source,
                    profileId,
                    profileVersion,
                    effectiveFrom,
                    setBy,
                    reason,
                    recordedAt,
                    relaxationRef,
                ).from(settings)
                .where(tenantId.eq(checkNotNull(target.tenant).value))
                .and(vpKey.eq(key.value))
                .and(scopeLevel.eq(target.level.name))
                .and(scopeId.eq(target.ref.id))
                .orderBy(seq)
                .fetch { row ->
                    version(
                        row,
                        key,
                        target,
                        SettingSource.valueOf(row.get(source)),
                        row.get(profileId),
                        row.get(profileVersion),
                        row.get(relaxationRef),
                    )
                }
        }

    /** Whether any value of [key] has ever been stored at [target] (the first-value test, INV-Q-3140). */
    fun hasAny(
        key: VariationPointKey,
        target: SettingTarget,
    ): Boolean = versions(key, target).isNotEmpty()

    /** Whether a setting already consumed [relaxation] (INV-Q-3138). */
    fun consumed(
        tenant: TenantId,
        relaxation: RelaxationId,
    ): Boolean =
        dsl.fetchExists(
            dsl
                .selectOne()
                .from(settings)
                .where(tenantId.eq(tenant.value))
                .and(relaxationRef.eq(relaxation.value)),
        )

    fun insertAcknowledgement(row: AcknowledgementRow) {
        dsl
            .insertInto(acknowledgements)
            .set(id, row.acknowledgement.id)
            .set(relaxationId, row.acknowledgement.relaxation.value)
            .set(tenantId, checkNotNull(row.target.tenant).value)
            .set(entityId, row.target.entity)
            .set(vpKey, row.key.value)
            .set(scopeLevel, row.target.level.name)
            .set(scopeId, row.target.ref.id)
            .set(columns.keys, columns.keysOf(row.selected))
            .set(columns.values, columns.valuesOf(row.selected))
            .set(role, row.acknowledgement.role)
            .set(principal, row.acknowledgement.principal)
            .set(effectiveFrom, row.acknowledgement.effectiveFrom)
            .set(reason, row.reason)
            .set(acknowledgedAt, row.acknowledgement.acknowledgedAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    /** The acknowledgements of [relaxation], in the order given. */
    fun acknowledgements(
        tenant: TenantId,
        relaxation: RelaxationId,
    ): List<AcknowledgementRow> =
        dsl
            .select(
                id,
                relaxationId,
                entityId,
                vpKey,
                scopeLevel,
                scopeId,
                columns.keys,
                columns.values,
                role,
                principal,
                effectiveFrom,
                reason,
                acknowledgedAt,
            ).from(acknowledgements)
            .where(tenantId.eq(tenant.value))
            .and(relaxationId.eq(relaxation.value))
            .orderBy(seq)
            .fetch { row ->
                AcknowledgementRow(
                    key = VariationPointKey(row.get(vpKey)),
                    target =
                        SettingTarget(
                            tenant,
                            ScopeRef(ScopeLevel.valueOf(row.get(scopeLevel)), row.get(scopeId)),
                            row.get(entityId),
                        ),
                    selected = columns.read(row.get(columns.keys), row.get(columns.values)),
                    reason = row.get(reason),
                    acknowledgement =
                        RelaxationAcknowledgement(
                            id = row.get(id),
                            relaxation = RelaxationId(row.get(relaxationId)),
                            role = row.get(role),
                            principal = row.get(principal),
                            effectiveFrom = row.get(effectiveFrom),
                            acknowledgedAt = row.get(acknowledgedAt).toInstant(),
                        ),
                )
            }

    private fun version(
        row: Record,
        key: VariationPointKey,
        target: SettingTarget,
        origin: SettingSource,
        profile: String?,
        profileVer: String?,
        relaxation: UUID?,
    ) = SettingVersion(
        id = row.get(id),
        key = key,
        target = target,
        selected = columns.read(row.get(columns.keys), row.get(columns.values)),
        source = origin,
        profileId = profile,
        profileVersion = profileVer,
        effectiveFrom = row.get(effectiveFrom),
        setBy = row.get(setBy),
        reason = row.get(reason),
        recordedAt = row.get(recordedAt).toInstant(),
        relaxation = relaxation?.let { RelaxationId(it) },
    )

    private fun <T> column(
        name: String,
        type: DataType<T>,
    ): Field<T> = DSL.field(DSL.name(name), type)

    private companion object {
        /** `(setting_id, stored_at)` as the composite text PostgreSQL gives a row value: `(uuid,"2026-10-01 00:00:00+00")`. */
        val STORED: DataType<StoredInstallation> =
            SQLDataType.OTHER.asConvertedDataType(
                object : Converter<Any, StoredInstallation> {
                    override fun from(database: Any?): StoredInstallation? {
                        val text = database?.toString() ?: return null
                        val inner = text.removeSurrounding("(", ")")
                        val comma = inner.indexOf(',')
                        check(comma > 0) { "the installation write returned $text, not an id and a time" }
                        val time = inner.substring(comma + 1).trim().removeSurrounding("\"")
                        return StoredInstallation(UUID.fromString(inner.substring(0, comma)), instantOf(time))
                    }

                    override fun to(user: StoredInstallation?): Any? = user?.let { "(${it.id},\"${it.recordedAt}\")" }

                    override fun fromType(): Class<Any> = Any::class.java

                    override fun toType(): Class<StoredInstallation> = StoredInstallation::class.java
                },
            )

        /** A timestamptz as PostgreSQL prints it: `2026-10-01 00:00:00.123456+00` (the offset may be `+08` or `+05:30`). */
        fun instantOf(text: String): Instant {
            val match = checkNotNull(OFFSET.find(text)) { "no offset in $text" }
            val sign = match.groupValues[1]
            val hours = match.groupValues[2]
            val minutes = match.groupValues[3].ifEmpty { "00" }
            val local = text.substring(0, match.range.first).replace(' ', 'T')
            return OffsetDateTime.parse("$local$sign$hours:$minutes").toInstant()
        }

        val OFFSET = Regex("([+-])(\\d\\d)(?::?(\\d\\d))?$")
    }
}

/** The id and database time [JdbcSettingWrites.writeInstallation] gave an installation value. */
internal data class StoredInstallation(
    val id: UUID,
    val recordedAt: Instant,
)

/** One stored acknowledgement with what it acknowledged. */
internal data class AcknowledgementRow(
    val key: VariationPointKey,
    val target: SettingTarget,
    val selected: List<SelectedOption>,
    val reason: String?,
    val acknowledgement: RelaxationAcknowledgement,
)
