package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.LocalDate
import java.util.UUID

/**
 * Reads the setting tables through jOOQ's DSL on the DSLContext of tenancy-jooq's factory, in the caller's open
 * transaction: the row in force at a date is the latest effective date on or before it, then the latest recorded
 * (ruling INV-Q-2940). Tenant rows always carry an explicit `tenant_id` predicate as well as RLS (defence in depth,
 * TPL-11). Identifiers are `DSL.name` of the [SqlIdentifier]-validated schema and constant column names; every value is
 * a bind value. Nothing here writes.
 */
internal class JdbcSettingStore(
    private val dsl: DSLContext,
    schema: String,
    private val columns: SelectedOptionsJooq = SelectedOptionsJooq(),
) {
    private val settings: Table<*> = DSL.table(DSL.name(SqlIdentifier.require(schema, "schema"), ConfigSchema.SETTING))
    private val installation: Table<*> =
        DSL.table(DSL.name(SqlIdentifier.require(schema, "schema"), ConfigSchema.INSTALLATION_SETTING))

    private val id: Field<UUID> = DSL.field(DSL.name("id"), SQLDataType.UUID)
    private val tenantId: Field<UUID> = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
    private val vpKey: Field<String> = DSL.field(DSL.name("vp_key"), SQLDataType.CLOB)
    private val scopeLevel: Field<String> = DSL.field(DSL.name("scope_level"), SQLDataType.CLOB)
    private val scopeId: Field<String> = DSL.field(DSL.name("scope_id"), SQLDataType.CLOB)
    private val source: Field<String> = DSL.field(DSL.name("source"), SQLDataType.CLOB)
    private val profileId: Field<String> = DSL.field(DSL.name("profile_id"), SQLDataType.CLOB)
    private val profileVersion: Field<String> = DSL.field(DSL.name("profile_version"), SQLDataType.CLOB)
    private val effectiveFrom: Field<LocalDate> = DSL.field(DSL.name("effective_from"), SQLDataType.LOCALDATE)
    private val seq: Field<Long> = DSL.field(DSL.name("seq"), SQLDataType.BIGINT)

    fun find(
        key: VariationPointKey,
        ref: ScopeRef,
        tenant: TenantId?,
        source: SettingSource,
        at: LocalDate,
    ): StoredSetting? =
        if (ref.level == ScopeLevel.INSTALLATION) {
            // Installation values are overrides set by the operator; no operating profile applies there.
            if (source != SettingSource.OVERRIDE) {
                null
            } else {
                dsl
                    .select(id, columns.keys, columns.values, effectiveFrom)
                    .from(installation)
                    .where(vpKey.eq(key.value))
                    .and(effectiveFrom.le(at))
                    .orderBy(effectiveFrom.desc(), seq.desc())
                    .limit(1)
                    .fetchOne { stored(it, SettingSource.OVERRIDE, null, null) }
            }
        } else {
            val owner = requireNotNull(tenant) { "a ${ref.level} setting needs a tenant" }
            dsl
                .select(id, columns.keys, columns.values, this.source, profileId, profileVersion, effectiveFrom)
                .from(settings)
                .where(tenantId.eq(owner.value))
                .and(vpKey.eq(key.value))
                .and(scopeLevel.eq(ref.level.name))
                .and(scopeId.eq(ref.id))
                .and(this.source.eq(source.name))
                .and(effectiveFrom.le(at))
                .orderBy(effectiveFrom.desc(), seq.desc())
                .limit(1)
                .fetchOne {
                    stored(
                        it,
                        SettingSource.valueOf(it.get(this.source)),
                        it.get(profileId),
                        it.get(profileVersion),
                    )
                }
        }

    private fun stored(
        row: Record,
        origin: SettingSource,
        profile: String?,
        version: String?,
    ) = StoredSetting(
        id = row.get(id),
        selected = columns.read(row.get(columns.keys), row.get(columns.values)),
        source = origin,
        profileId = profile,
        profileVersion = version,
        effectiveFrom = row.get(effectiveFrom),
    )
}
