package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.jooq.DSLContext
import org.jooq.DataType
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [ResolutionRecorder] over `config_resolved_value`: insert-only, first write wins (ruling INV-Q-2938). A
 * second record for the same subject and variation point writes nothing and returns the stored one, so a
 * document keeps the value and source it was first resolved with (INV-AC-2093).
 *
 * Every statement is jOOQ DSL on the DSLContext of tenancy-jooq's factory, run in the caller's open transaction of the
 * tenant (ARC-21; forced RLS, CS-19); tenancy-jooq adds the tenant predicate and checks it again before the statement
 * runs. Identifiers are `DSL.name` of the [SqlIdentifier]-validated schema and constant column names; every value is a
 * bind value. The DSLContext runs on the TransactionRunner whose TenantAwareDataSource holds the lane gate (INV-Q-4502).
 */
class JdbcResolutionRecorder(
    private val dsl: DSLContext,
    schema: String,
    private val clock: Clock,
) : ResolutionRecorder {
    private val table: Table<*> =
        DSL.table(DSL.name(SqlIdentifier.require(schema, "schema"), ConfigSchema.RESOLVED_VALUE))
    private val columns = SelectedOptionsJooq()
    private val mapper: JsonMapper =
        JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    private val id: Field<UUID> = column("id", SQLDataType.UUID)
    private val tenantId: Field<UUID> = column("tenant_id", SQLDataType.UUID)
    private val entityId: Field<String> = column("entity_id", SQLDataType.CLOB)
    private val subjectType: Field<String> = column("subject_type", SQLDataType.CLOB)
    private val subjectRef: Field<String> = column("subject_ref", SQLDataType.CLOB)
    private val vpKey: Field<String> = column("vp_key", SQLDataType.CLOB)
    private val transactionDate: Field<LocalDate> = column("transaction_date", SQLDataType.LOCALDATE)
    private val source: Field<String> = column("source", SQLDataType.CLOB)
    private val sourceLevel: Field<String> = column("source_level", SQLDataType.CLOB)
    private val sourceScopeId: Field<String> = column("source_scope_id", SQLDataType.CLOB)
    private val sourceSettingId: Field<UUID> = column("source_setting_id", SQLDataType.UUID)
    private val profileId: Field<String> = column("profile_id", SQLDataType.CLOB)
    private val profileVersion: Field<String> = column("profile_version", SQLDataType.CLOB)
    private val resolvedAt: Field<OffsetDateTime> = column("resolved_at", SQLDataType.TIMESTAMPWITHTIMEZONE)
    private val cappedBy: Field<JSONB> = column("capped_by", SQLDataType.JSONB)

    override fun record(
        subject: ResolutionSubject,
        resolved: ResolvedSetting,
        scope: PolicyScope,
    ): RecordedResolution {
        val tenant = requireNotNull(scope.tenant) { "a resolution is recorded on a tenant's subject" }
        val origin = resolved.source
        val (level, scopeId, settingId) =
            when (origin) {
                is PolicySource.Override -> Triple(origin.level, origin.scopeId, origin.settingId)
                is PolicySource.Profile -> Triple(origin.level, origin.scopeId, origin.settingId)
                PolicySource.CatalogueDefault -> Triple(null, null, null)
            }
        dsl
            .insertInto(table)
            .set(id, UUID.randomUUID())
            .set(tenantId, tenant.value)
            .set(entityId, scope.entity)
            .set(subjectType, subject.type)
            .set(subjectRef, subject.ref)
            .set(vpKey, resolved.key.value)
            .set(transactionDate, resolved.at)
            .set(columns.keys, columns.keysOf(resolved.selected))
            .set(columns.values, columns.valuesOf(resolved.selected))
            .set(source, sourceName(origin))
            .set(sourceLevel, level?.name)
            .set(sourceScopeId, scopeId)
            .set(sourceSettingId, settingId)
            .set(profileId, (origin as? PolicySource.Profile)?.profileId)
            .set(profileVersion, (origin as? PolicySource.Profile)?.profileVersion)
            .set(resolvedAt, clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
            .set(cappedBy, capsJson(resolved.caps))
            .onConflict(tenantId, subjectType, subjectRef, vpKey)
            .doNothing()
            .execute()
        return select(subject, resolved.key).single()
    }

    override fun recorded(subject: ResolutionSubject): List<RecordedResolution> = select(subject, null)

    private fun select(
        subject: ResolutionSubject,
        key: VariationPointKey?,
    ): List<RecordedResolution> {
        // A null key selects every variation point of the subject; tenancy-jooq adds the bound-tenant predicate (TPL-11).
        val keyCondition = if (key == null) DSL.noCondition() else vpKey.eq(key.value)
        return dsl
            .select(
                vpKey,
                transactionDate,
                columns.keys,
                columns.values,
                source,
                sourceLevel,
                sourceScopeId,
                sourceSettingId,
                profileId,
                profileVersion,
                cappedBy,
            ).from(table)
            .where(subjectType.eq(subject.type))
            .and(subjectRef.eq(subject.ref))
            .and(keyCondition)
            .orderBy(vpKey)
            .fetch { row -> recorded(row, subject) }
    }

    private fun recorded(
        row: Record,
        subject: ResolutionSubject,
    ): RecordedResolution {
        val level = row.get(sourceLevel)?.let { ScopeLevel.valueOf(it) }
        val origin =
            when (row.get(source)) {
                "OVERRIDE" -> {
                    PolicySource.Override(checkNotNull(level), row.get(sourceScopeId), row.get(sourceSettingId))
                }

                "PROFILE" -> {
                    PolicySource.Profile(
                        row.get(profileId),
                        row.get(profileVersion),
                        checkNotNull(level),
                        row.get(sourceScopeId),
                        row.get(sourceSettingId),
                    )
                }

                else -> {
                    PolicySource.CatalogueDefault
                }
            }
        return RecordedResolution(
            subject,
            VariationPointKey(row.get(vpKey)),
            row.get(transactionDate),
            columns.read(row.get(columns.keys), row.get(columns.values)),
            origin,
            caps(row.get(cappedBy)?.data()),
        )
    }

    /** The caps as a JSON array, `null` when there are none (INV-REQ-1242 behaviour #5). */
    private fun capsJson(caps: List<PolicyCap>): Field<JSONB> {
        if (caps.isEmpty()) return DSL.`val`(null, SQLDataType.JSONB)
        val array = mapper.createArrayNode()
        caps.forEach { cap ->
            array
                .addObject()
                .put("option", cap.option)
                .put("field", cap.field)
                .put("stored", cap.stored)
                .put("capped", cap.capped)
                .put("by", cap.by.value)
                .put("level", cap.level.name)
        }
        return DSL.`val`(mapper.writeValueAsString(array)).cast(SQLDataType.JSONB)
    }

    private fun caps(json: String?): List<PolicyCap> {
        if (json == null) return emptyList()
        return mapper.readTree(json).values().map {
            PolicyCap(
                it.get("option").stringValue(),
                it.get("field").stringValue(),
                it.get("stored").decimalValue(),
                it.get("capped").decimalValue(),
                VariationPointKey(it.get("by").stringValue()),
                ScopeLevel.valueOf(it.get("level").stringValue()),
            )
        }
    }

    private fun <T> column(
        name: String,
        type: DataType<T>,
    ): Field<T> = DSL.field(DSL.name(name), type)

    private fun sourceName(source: PolicySource) =
        when (source) {
            is PolicySource.Override -> "OVERRIDE"
            is PolicySource.Profile -> "PROFILE"
            PolicySource.CatalogueDefault -> "DEFAULT"
        }
}
