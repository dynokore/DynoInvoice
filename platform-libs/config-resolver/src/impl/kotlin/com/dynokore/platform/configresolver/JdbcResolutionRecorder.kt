package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.SqlIdentifier
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [ResolutionRecorder] over `config_resolved_value`: insert-only, first write wins (ruling INV-Q-2938). A
 * second record for the same subject and variation point writes nothing and returns the stored one, so a
 * document keeps the value and source it was first resolved with (INV-AC-2093).
 */
class JdbcResolutionRecorder(
    private val connections: CurrentConnection,
    schema: String,
    private val clock: Clock,
) : ResolutionRecorder {
    private val table = "${SqlIdentifier.require(schema, "schema")}.${ConfigSchema.RESOLVED_VALUE}"
    private val columns = SelectedOptionsColumns()
    private val mapper: JsonMapper =
        JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()

    override fun record(
        subject: ResolutionSubject,
        resolved: ResolvedSetting,
        scope: PolicyScope,
    ): RecordedResolution {
        val tenant = requireNotNull(scope.tenant) { "a resolution is recorded on a tenant's subject" }
        val connection = connections.require()
        val source = resolved.source
        val (level, scopeId, settingId) =
            when (source) {
                is PolicySource.Override -> Triple(source.level, source.scopeId, source.settingId)
                is PolicySource.Profile -> Triple(source.level, source.scopeId, source.settingId)
                PolicySource.CatalogueDefault -> Triple(null, null, null)
            }
        connection
            .prepareStatement(
                "insert into $table (id, tenant_id, entity_id, subject_type, subject_ref, vp_key, transaction_date, " +
                    "option_keys, option_values, source, source_level, source_scope_id, source_setting_id, " +
                    "profile_id, " +
                    "profile_version, resolved_at, capped_by) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb)) " +
                    "on conflict (tenant_id, subject_type, subject_ref, vp_key) do nothing",
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, tenant.value)
                it.setString(3, scope.entity)
                it.setString(4, subject.type)
                it.setString(5, subject.ref)
                it.setString(6, resolved.key.value)
                it.setObject(7, resolved.at)
                columns.bind(it, connection, 8, 9, resolved.selected)
                it.setString(10, sourceName(source))
                it.setString(11, level?.name)
                it.setString(12, scopeId)
                it.setObject(13, settingId)
                it.setString(14, (source as? PolicySource.Profile)?.profileId)
                it.setString(15, (source as? PolicySource.Profile)?.profileVersion)
                it.setTimestamp(16, Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)))
                it.setString(17, capsJson(resolved.caps))
                it.executeUpdate()
            }
        return select(subject, resolved.key).single()
    }

    override fun recorded(subject: ResolutionSubject): List<RecordedResolution> = select(subject, null)

    private fun select(
        subject: ResolutionSubject,
        key: VariationPointKey?,
    ): List<RecordedResolution> {
        // One static statement: a null key selects every variation point of the subject.
        return connections
            .require()
            .prepareStatement(
                "select vp_key, transaction_date, option_keys, option_values, source, source_level, source_scope_id, " +
                    "source_setting_id, profile_id, profile_version, capped_by from $table " +
                    "where tenant_id = $BOUND_TENANT and subject_type = ? and subject_ref = ? " +
                    "and vp_key = coalesce(?, vp_key) order by vp_key",
            ).use {
                it.setString(1, subject.type)
                it.setString(2, subject.ref)
                it.setString(3, key?.value)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(recorded(rows, subject)) } }
            }
    }

    private fun recorded(
        rows: ResultSet,
        subject: ResolutionSubject,
    ): RecordedResolution {
        val level = rows.getString("source_level")?.let { ScopeLevel.valueOf(it) }
        val source =
            when (rows.getString("source")) {
                "OVERRIDE" -> {
                    PolicySource.Override(
                        checkNotNull(level),
                        rows.getString("source_scope_id"),
                        rows.getObject("source_setting_id", UUID::class.java),
                    )
                }

                "PROFILE" -> {
                    PolicySource.Profile(
                        rows.getString("profile_id"),
                        rows.getString("profile_version"),
                        checkNotNull(level),
                        rows.getString("source_scope_id"),
                        rows.getObject("source_setting_id", UUID::class.java),
                    )
                }

                else -> {
                    PolicySource.CatalogueDefault
                }
            }
        return RecordedResolution(
            subject,
            VariationPointKey(rows.getString("vp_key")),
            rows.getObject("transaction_date", LocalDate::class.java),
            columns.read(rows),
            source,
            caps(rows.getString("capped_by")),
        )
    }

    /** The caps as a JSON array, `null` when there are none (INV-REQ-1242 behaviour #5). */
    private fun capsJson(caps: List<PolicyCap>): String? {
        if (caps.isEmpty()) return null
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
        return mapper.writeValueAsString(array)
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

    private companion object {
        /** The tenant bound to the transaction (ARC-21): the explicit predicate RLS also enforces (TPL-11). */
        const val BOUND_TENANT = "nullif(current_setting('app.tenant_id', true), '')::uuid"
    }

    private fun sourceName(source: PolicySource) =
        when (source) {
            is PolicySource.Override -> "OVERRIDE"
            is PolicySource.Profile -> "PROFILE"
            PolicySource.CatalogueDefault -> "DEFAULT"
        }
}
