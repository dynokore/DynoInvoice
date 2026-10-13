package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * SQL of the write path and of the history, on a given connection. Tenant rows always carry an explicit
 * `tenant_id` predicate as well as RLS (defence in depth, TPL-11).
 */
internal class JdbcSettingWrites(
    schema: String,
    private val columns: SelectedOptionsColumns = SelectedOptionsColumns(),
) {
    private val s = SqlIdentifier.require(schema, "schema")
    private val settings = "$s.${ConfigSchema.SETTING}"
    private val installation = "$s.${ConfigSchema.INSTALLATION_SETTING}"
    private val acknowledgements = "$s.${ConfigSchema.RELAXATION_ACKNOWLEDGEMENT}"
    private val writeInstallation = "$s.${ConfigSchema.WRITE_INSTALLATION_SETTING}"

    /** Stores a tenant-and-below value; an installation value goes through [writeInstallation]. */
    fun insert(
        connection: Connection,
        version: SettingVersion,
    ) {
        val target = version.target
        require(target.level != ScopeLevel.INSTALLATION) { "an installation value is written by $writeInstallation" }
        connection
            .prepareStatement(
                "insert into $settings (id, tenant_id, entity_id, vp_key, scope_level, scope_id, option_keys, " +
                    "option_values, source, profile_id, profile_version, effective_from, set_by, reason, " +
                    "recorded_at, relaxation_ref) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use {
                it.setObject(1, version.id)
                it.setObject(2, checkNotNull(target.tenant).value)
                it.setString(3, target.entity)
                it.setString(4, version.key.value)
                it.setString(5, target.level.name)
                it.setString(6, target.ref.id)
                columns.bind(it, connection, 7, 8, version.selected)
                it.setString(9, version.source.name)
                it.setString(10, version.profileId)
                it.setString(11, version.profileVersion)
                it.setObject(12, version.effectiveFrom)
                it.setString(13, version.setBy)
                it.setString(14, version.reason)
                it.setTimestamp(15, Timestamp.from(version.recordedAt))
                it.setObject(16, version.relaxation?.value)
                it.executeUpdate()
            }
    }

    /**
     * Stores an installation value through [ConfigSchema.WRITE_INSTALLATION_SETTING] with its staged audit row and,
     * in a service with an outbox, its staged `PolicyChanged` row (JSON objects of their outbox columns,
     * [StagedRows]); the function checks them, sets the id, actor and time itself and writes all of them or none
     * (coordinator rulings INV-Q-4254 A, INV-Q-4316).
     */
    fun writeInstallation(
        connection: Connection,
        version: SettingVersion,
        audit: String,
        policyChanged: String?,
    ): StoredInstallation {
        require(version.target.level == ScopeLevel.INSTALLATION) { "not an installation value" }
        return connection
            .prepareStatement(
                "select setting_id, stored_at from $writeInstallation(?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)",
            ).use {
                it.setString(1, version.key.value)
                columns.bind(it, connection, 2, 3, version.selected)
                it.setObject(4, version.effectiveFrom)
                it.setString(5, version.reason)
                it.setString(6, audit)
                it.setString(7, policyChanged)
                it.executeQuery().use { rows ->
                    check(rows.next()) { "the installation write returned no row" }
                    StoredInstallation(rows.getObject(1, UUID::class.java), rows.getTimestamp(2).toInstant())
                }
            }
    }

    /** Every version of [key] at [target], oldest recorded first. */
    fun versions(
        connection: Connection,
        key: VariationPointKey,
        target: SettingTarget,
    ): List<SettingVersion> =
        if (target.level == ScopeLevel.INSTALLATION) {
            connection
                .prepareStatement(
                    "select id, option_keys, option_values, 'OVERRIDE' as source, null as profile_id, " +
                        "null as profile_version, effective_from, set_by, reason, recorded_at, null::uuid as " +
                        "relaxation_ref " +
                        "from $installation where vp_key = ? order by seq",
                ).use {
                    it.setString(1, key.value)
                    it.executeQuery().use { rows ->
                        generateSequence { if (rows.next()) version(rows, key, target) else null }.toList()
                    }
                }
        } else {
            connection
                .prepareStatement(
                    "select id, option_keys, option_values, source, profile_id, profile_version, effective_from, " +
                        "set_by, " +
                        "reason, recorded_at, relaxation_ref from $settings where tenant_id = ? and vp_key = ? " +
                        "and scope_level = ? and scope_id = ? order by seq",
                ).use {
                    it.setObject(1, checkNotNull(target.tenant).value)
                    it.setString(2, key.value)
                    it.setString(3, target.level.name)
                    it.setString(4, target.ref.id)
                    it.executeQuery().use { rows ->
                        generateSequence { if (rows.next()) version(rows, key, target) else null }.toList()
                    }
                }
        }

    /** Whether any value of [key] has ever been stored at [target] (the first-value test, INV-Q-3140). */
    fun hasAny(
        connection: Connection,
        key: VariationPointKey,
        target: SettingTarget,
    ): Boolean = versions(connection, key, target).isNotEmpty()

    /** Whether a setting already consumed [relaxation] (INV-Q-3138). */
    fun consumed(
        connection: Connection,
        tenant: TenantId,
        relaxation: RelaxationId,
    ): Boolean =
        connection
            .prepareStatement(
                "select 1 from $settings where tenant_id = ? and relaxation_ref = ?",
            ).use {
                it.setObject(1, tenant.value)
                it.setObject(2, relaxation.value)
                it.executeQuery().use { rows -> rows.next() }
            }

    fun insertAcknowledgement(
        connection: Connection,
        row: AcknowledgementRow,
    ) {
        connection
            .prepareStatement(
                "insert into $acknowledgements (id, relaxation_id, tenant_id, entity_id, vp_key, scope_level, " +
                    "scope_id, " +
                    "option_keys, option_values, role, principal, effective_from, reason, acknowledged_at) " +
                    "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use {
                it.setObject(1, row.acknowledgement.id)
                it.setObject(2, row.acknowledgement.relaxation.value)
                it.setObject(3, checkNotNull(row.target.tenant).value)
                it.setString(4, row.target.entity)
                it.setString(5, row.key.value)
                it.setString(6, row.target.level.name)
                it.setString(7, row.target.ref.id)
                columns.bind(it, connection, 8, 9, row.selected)
                it.setString(10, row.acknowledgement.role)
                it.setString(11, row.acknowledgement.principal)
                it.setObject(12, row.acknowledgement.effectiveFrom)
                it.setString(13, row.reason)
                it.setTimestamp(14, Timestamp.from(row.acknowledgement.acknowledgedAt))
                it.executeUpdate()
            }
    }

    /** The acknowledgements of [relaxation], in the order given. */
    fun acknowledgements(
        connection: Connection,
        tenant: TenantId,
        relaxation: RelaxationId,
    ): List<AcknowledgementRow> =
        connection
            .prepareStatement(
                "select id, relaxation_id, entity_id, vp_key, scope_level, scope_id, option_keys, option_values, " +
                    "role, principal, effective_from, reason, acknowledged_at from $acknowledgements " +
                    "where tenant_id = ? and relaxation_id = ? order by seq",
            ).use {
                it.setObject(1, tenant.value)
                it.setObject(2, relaxation.value)
                it.executeQuery().use { rows ->
                    generateSequence {
                        if (!rows.next()) {
                            null
                        } else {
                            AcknowledgementRow(
                                key = VariationPointKey(rows.getString("vp_key")),
                                target =
                                    SettingTarget(
                                        tenant,
                                        ScopeRef(
                                            ScopeLevel.valueOf(rows.getString("scope_level")),
                                            rows.getString("scope_id"),
                                        ),
                                        rows.getString("entity_id"),
                                    ),
                                selected = columns.read(rows),
                                reason = rows.getString("reason"),
                                acknowledgement =
                                    RelaxationAcknowledgement(
                                        id = rows.getObject("id", UUID::class.java),
                                        relaxation = RelaxationId(rows.getObject("relaxation_id", UUID::class.java)),
                                        role = rows.getString("role"),
                                        principal = rows.getString("principal"),
                                        effectiveFrom = rows.getObject("effective_from", LocalDate::class.java),
                                        acknowledgedAt = rows.getTimestamp("acknowledged_at").toInstant(),
                                    ),
                            )
                        }
                    }.toList()
                }
            }

    private fun version(
        rows: ResultSet,
        key: VariationPointKey,
        target: SettingTarget,
    ) = SettingVersion(
        id = rows.getObject("id", UUID::class.java),
        key = key,
        target = target,
        selected = columns.read(rows),
        source = SettingSource.valueOf(rows.getString("source")),
        profileId = rows.getString("profile_id"),
        profileVersion = rows.getString("profile_version"),
        effectiveFrom = rows.getObject("effective_from", LocalDate::class.java),
        setBy = rows.getString("set_by"),
        reason = rows.getString("reason"),
        recordedAt = rows.getTimestamp("recorded_at").toInstant(),
        relaxation = rows.getObject("relaxation_ref", UUID::class.java)?.let { RelaxationId(it) },
    )
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
