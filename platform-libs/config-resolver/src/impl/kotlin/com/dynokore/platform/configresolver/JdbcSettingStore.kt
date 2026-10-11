package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/**
 * Reads the setting tables on a given connection: the row in force at a date is the latest effective date
 * on or before it, then the latest recorded (ruling INV-Q-2940). Tenant rows always carry an explicit
 * `tenant_id` predicate as well as RLS (defence in depth, TPL-11). Nothing here writes.
 */
internal class JdbcSettingStore(
    schema: String,
    private val columns: SelectedOptionsColumns = SelectedOptionsColumns(),
) {
    private val settings = "${SqlIdentifier.require(schema, "schema")}.${ConfigSchema.SETTING}"
    private val installation = "${SqlIdentifier.require(schema, "schema")}.${ConfigSchema.INSTALLATION_SETTING}"

    fun find(
        connection: Connection,
        key: VariationPointKey,
        ref: ScopeRef,
        tenant: TenantId?,
        source: SettingSource,
        at: LocalDate,
    ): StoredSetting? = if (ref.level == ScopeLevel.INSTALLATION) {
        // Installation values are overrides set by the operator; no operating profile applies there.
        if (source != SettingSource.OVERRIDE) {
            null
        } else {
            connection.prepareStatement(
                "select id, option_keys, option_values, 'OVERRIDE' as source, null as profile_id, " +
                    "null as profile_version, " +
                    "effective_from from $installation where vp_key = ? and effective_from <= ? " +
                    "order by effective_from desc, seq desc limit 1",
            ).use {
                it.setString(1, key.value)
                it.setObject(2, at)
                it.executeQuery().use { rows -> if (rows.next()) stored(rows) else null }
            }
        }
    } else {
        connection.prepareStatement(
            "select id, option_keys, option_values, source, profile_id, profile_version, effective_from " +
                "from $settings where tenant_id = ? and vp_key = ? and scope_level = ? and scope_id = ? " +
                "and source = ? and effective_from <= ? " +
                "order by effective_from desc, seq desc limit 1",
        ).use {
            it.setObject(1, requireNotNull(tenant) { "a ${ref.level} setting needs a tenant" }.value)
            it.setString(2, key.value)
            it.setString(3, ref.level.name)
            it.setString(4, ref.id)
            it.setString(5, source.name)
            it.setObject(6, at)
            it.executeQuery().use { rows -> if (rows.next()) stored(rows) else null }
        }
    }

    private fun stored(rows: ResultSet) = StoredSetting(
        id = rows.getObject("id", UUID::class.java),
        selected = columns.read(rows),
        source = SettingSource.valueOf(rows.getString("source")),
        profileId = rows.getString("profile_id"),
        profileVersion = rows.getString("profile_version"),
        effectiveFrom = rows.getObject("effective_from", LocalDate::class.java),
    )
}
