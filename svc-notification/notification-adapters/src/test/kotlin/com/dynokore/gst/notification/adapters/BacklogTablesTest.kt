package com.dynokore.gst.notification.adapters

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/** INV-REQ-1370, slice O1c-1: notification.alert_policy and notification.alert (INV-AC-20812, INV-AC-20813). */
class BacklogTablesTest {
    private companion object {
        const val COLUMNS = "select column_name from information_schema.columns where table_schema = 'notification'"
    }

    private fun <T> owner(block: (Connection) -> T): T = NotificationDb.superuser().use(block)

    private fun <T> runtime(
        tenant: UUID,
        signature: String = SignedBindings.signed(tenant),
        block: (Connection) -> T,
    ): T =
        NotificationDb.connect(NotificationDb.RUNTIME, NotificationDb.RUNTIME_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant, signature = signature)
            try {
                block(connection)
            } finally {
                connection.rollback()
            }
        }

    private fun <T> migrateRole(
        tenant: UUID,
        block: (Connection) -> T,
    ): T =
        NotificationDb.connect(NotificationDb.MIGRATE, NotificationDb.MIGRATE_PASSWORD).use { connection ->
            connection.autoCommit = false
            SignedBindings.bind(connection, tenant)
            try {
                block(connection)
            } finally {
                connection.rollback()
            }
        }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.strings(sql: String): List<String> =
        createStatement().use { s ->
            s.executeQuery(sql).use { r -> generateSequence { if (r.next()) r.getString(1) else null }.toList() }
        }

    private fun Connection.count(sql: String): Int =
        createStatement().use { s ->
            s.executeQuery(sql).use { r ->
                r.next()
                r.getInt(1)
            }
        }

    private fun refused(sql: String) {
        owner { c ->
            c.autoCommit = false
            try {
                shouldThrow<SQLException> { c.exec(sql) }
            } finally {
                c.rollback()
            }
        }
    }

    private fun accepted(vararg sql: String) {
        owner { c ->
            c.autoCommit = false
            try {
                sql.forEach { c.exec(it) }
            } finally {
                c.rollback()
            }
        }
    }

    private fun columns(table: String) = owner { it.strings("$COLUMNS and table_name = '$table'") }.toSet()

    private fun notNull(table: String) =
        owner {
            it.strings(
                "$COLUMNS and table_name = '$table' and is_nullable = 'NO'",
            )
        }.toSet()

    private fun primaryKey(table: String) =
        owner {
            it.strings(
                "select a.attname from pg_index i join pg_attribute a on a.attrelid = i.indrelid " +
                    "and a.attnum = any (i.indkey) " +
                    "where i.indrelid = 'notification.$table'::regclass and i.indisprimary " +
                    "order by array_position(i.indkey::int[], a.attnum::int)",
            )
        }

    private fun rls(table: String) =
        owner {
            it.strings(
                "select relrowsecurity::text || ',' || relforcerowsecurity::text " +
                    "from pg_class where oid = 'notification.$table'::regclass",
            )
        }.single()

    private fun privileges(table: String) =
        owner { c ->
            listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER")
                .filter {
                    c.count("select has_table_privilege('notification_runtime', 'notification.$table', '$it')::int") ==
                        1
                }.toSet()
        }

    private val t1 = TENANT_A

    private fun policyRows(
        tenant: UUID,
        entity: String,
    ) = runtime(tenant) { it.count("select count(*) from notification.alert_policy where entity_ref = '$entity'") }

    private val t2 = UUID.randomUUID()

    private fun policy(
        tenant: UUID = t1,
        entity: String = "e-" + UUID.randomUUID(),
        extra: String = "",
        values: String = "",
    ) = "insert into notification.alert_policy " +
        "(tenant_id, entity_ref, alert_class, recipients, channels, updated_at$extra) " +
        "values ('$tenant', '$entity', 'BACKLOG', '[]', '[]', now()$values)"

    private val ack =
        ", sole_operator_acknowledgement, accountable_person, acknowledgement_effective_from, " +
            "acknowledgement_recorded_by, acknowledgement_recorded_at"

    @Test
    fun `INV-AC-20812 columns, NOT NULL set and primary key of alert_policy`() {
        columns("alert_policy") shouldBe
            setOf(
                "tenant_id",
                "entity_ref",
                "alert_class",
                "recipients",
                "channels",
                "escalation_tiers",
                "threshold_days",
                "sole_operator_acknowledgement",
                "accountable_person",
                "acknowledgement_effective_from",
                "acknowledgement_recorded_by",
                "acknowledgement_recorded_at",
                "updated_at",
                "max_queue_depth",
                "max_oldest_unsent_age_seconds",
                "max_failure_ratio",
                "max_unacknowledged_count",
            )
        notNull("alert_policy") shouldBe
            setOf("tenant_id", "entity_ref", "alert_class", "recipients", "channels", "updated_at")
        primaryKey("alert_policy") shouldBe listOf("tenant_id", "entity_ref", "alert_class")
        owner { it.count("select count(*) from notification.alert_policy where tenant_id is null") } shouldBe 0
    }

    @Test
    fun `INV-AC-20812 the acknowledgement columns are all null or all set`() {
        accepted(policy())
        accepted(policy(extra = ack, values = ", true, 'Ann', '2026-01-01', 'user-1', now()"))
        refused(policy(extra = ", sole_operator_acknowledgement", values = ", true"))
        refused(policy(extra = ", accountable_person", values = ", 'Ann'"))
        refused(policy(extra = ack, values = ", true, 'Ann', '2026-01-01', 'user-1', null"))
        refused(policy(extra = ack, values = ", null, 'Ann', '2026-01-01', 'user-1', now()"))
    }

    @Test
    fun `INV-AC-20812 threshold and range checks of alert_policy`() {
        for ((column, bad, good) in listOf(
            Triple("max_queue_depth", "0", "1"),
            Triple("max_oldest_unsent_age_seconds", "0", "1"),
            Triple("max_unacknowledged_count", "0", "1"),
            Triple("max_failure_ratio", "1.5", "1.0"),
            Triple("max_failure_ratio", "-0.1", "0"),
            Triple("threshold_days", "366", "365"),
            Triple("threshold_days", "-1", "0"),
        )) {
            refused(policy(extra = ", $column", values = ", $bad"))
            accepted(policy(extra = ", $column", values = ", $good"))
        }
        accepted(policy(extra = ", max_queue_depth, max_failure_ratio", values = ", null, null"))
    }

    @Test
    fun `INV-AC-20812 a second row for the same primary key is refused`() {
        val entity = "e-" + UUID.randomUUID()
        accepted(policy(entity = entity), policy(tenant = t2, entity = entity))
        refused(policy(entity = entity) + "; " + policy(entity = entity))
    }

    @Test
    fun `INV-AC-20812 row level security, tenant isolation and the grants of alert_policy`() {
        rls("alert_policy") shouldBe "true,true"
        val entity = "e-" + UUID.randomUUID()
        owner { it.exec(policy(tenant = t1, entity = entity)) }
        try {
            policyRows(t1, entity) shouldBe 1
            policyRows(t2, entity) shouldBe 0
            migrateRole(t2) { c -> shouldThrow<SQLException> { c.exec(policy(tenant = t1)) }.sqlState shouldBe "42501" }
        } finally {
            owner { it.exec("delete from notification.alert_policy where entity_ref = '$entity'") }
        }
        privileges("alert_policy") shouldBe setOf("SELECT")
        runtime(t1) { c -> shouldThrow<SQLException> { c.exec(policy(tenant = t1)) }.sqlState shouldBe "42501" }
    }

    private data class Row(
        val tenant: UUID = TENANT_A,
        val entity: String = "e1",
        val cls: String = "BACKLOG",
        val state: String = "RAISED",
        val trigger: String? = "BACKLOG_ABOVE_THRESHOLD",
        val period: String? = "2026-10",
        val caseId: Boolean = false,
        val alertRef: Boolean = false,
        val taskRef: Boolean = false,
        val tier: Int? = null,
        val snoozed: Boolean = false,
    )

    private fun alert(row: Row): String {
        fun q(v: String?) = if (v == null) "null" else "'$v'"

        fun flag(on: Boolean) = if (on) "'r'" else "null"
        val c = if (row.caseId) "'${UUID.randomUUID()}'" else "null"
        val s = if (row.snoozed) "now()" else "null"
        return "insert into notification.alert (tenant_id, alert_id, entity_ref, alert_class, state, raised_at, " +
            "\"trigger\", case_id, alert_ref, task_ref, current_tier, period_ref, snoozed_until) " +
            "values ('${row.tenant}', '${UUID.randomUUID()}', '${row.entity}', '${row.cls}', '${row.state}', now(), " +
            "${q(row.trigger)}, $c, ${flag(row.alertRef)}, ${flag(row.taskRef)}, ${row.tier ?: "null"}, " +
            "${q(row.period)}, $s)"
    }

    @Test
    fun `INV-AC-20813 columns, NOT NULL set and primary key of alert`() {
        columns("alert") shouldBe
            setOf(
                "tenant_id",
                "alert_id",
                "entity_ref",
                "alert_class",
                "state",
                "raised_at",
                "trigger",
                "case_id",
                "alert_ref",
                "task_ref",
                "current_tier",
                "period_ref",
                "acknowledged_at",
                "acknowledged_by",
                "snoozed_until",
            )
        notNull("alert") shouldBe setOf("tenant_id", "alert_id", "entity_ref", "alert_class", "state", "raised_at")
        primaryKey("alert") shouldBe listOf("tenant_id", "alert_id")
    }

    @Test
    fun `INV-AC-20813 case_id, accreditation and snooze checks`() {
        accepted(alert(Row(trigger = "PRIVACY_NOTICE_DUE", caseId = true)))
        refused(alert(Row(trigger = "PRIVACY_NOTICE_DUE")))
        refused(alert(Row(trigger = "OTHER", caseId = true)))
        refused(alert(Row(trigger = null, caseId = true)))
        accepted(alert(Row(cls = "ACCREDITATION", alertRef = true, taskRef = true)))
        refused(alert(Row(cls = "BACKLOG", alertRef = true, taskRef = true)))
        refused(alert(Row(cls = "ACCREDITATION")))
        refused(alert(Row(cls = "BACKLOG", taskRef = true)))
        refused(alert(Row(cls = "ACCREDITATION", alertRef = true)))
        refused(alert(Row(cls = "ACCREDITATION", taskRef = true)))
        accepted(alert(Row(state = "SNOOZED", snoozed = true)))
        refused(alert(Row(state = "RAISED", snoozed = true)))
        refused(alert(Row(state = "ACKNOWLEDGED", snoozed = true)))
    }

    @Test
    fun `INV-AC-20813 current_tier is null or at least 1 and extensible enums are not checked`() {
        refused(alert(Row(tier = 0)))
        accepted(alert(Row(tier = 1)))
        accepted(alert(Row(state = "SOMETHING_NEW", trigger = "NEW_TRIGGER", cls = "NEW_CLASS")))
    }

    @Test
    fun `INV-AC-20813 one open alert per entity, class, trigger and period`() {
        val key = "entity-" + UUID.randomUUID()
        for (state in listOf("RAISED", "SNOOZED")) {
            val snoozed = state == "SNOOZED"
            val second = Row(entity = key, state = state, snoozed = snoozed)
            refused(alert(Row(entity = key, state = "RAISED")) + "; " + alert(second))
        }
        val nulls = Row(entity = key, trigger = null, period = null)
        refused(alert(nulls) + "; " + alert(nulls))
        accepted(
            alert(Row(entity = key, state = "ACKNOWLEDGED")),
            alert(Row(entity = key, state = "ACKNOWLEDGED")),
            alert(Row(entity = key)),
            alert(Row(entity = key, period = "2026-11")),
            alert(Row(entity = key, trigger = "OTHER")),
            alert(Row(entity = key, cls = "MISSING_ACKNOWLEDGEMENT")),
            alert(Row(entity = key, tenant = t2)),
            alert(Row(entity = key, trigger = null, period = null)),
            alert(Row(entity = key, trigger = null, period = "2026-10")),
            alert(Row(entity = key, trigger = "PRIVACY_NOTICE_DUE", caseId = true, period = null)),
        )
        refused(alert(Row(entity = key)) + "; " + alert(Row(entity = key)))
    }

    @Test
    fun `INV-AC-20813 row level security, tenant isolation and the grants of alert`() {
        rls("alert") shouldBe "true,true"
        val entity = "e-" + UUID.randomUUID()
        runtime(t1) { c ->
            c.exec(alert(Row(tenant = t1, entity = entity)))
            c.count("select count(*) from notification.alert where entity_ref = '$entity'") shouldBe 1
        }
        owner { it.exec(alert(Row(tenant = t1, entity = entity, period = "p-other"))) }
        try {
            runtime(t2) { it.count("select count(*) from notification.alert where entity_ref = '$entity'") } shouldBe 0
            runtime(t1) { it.count("select count(*) from notification.alert where entity_ref = '$entity'") } shouldBe 1
            runtime(t2) { c ->
                shouldThrow<SQLException> { c.exec(alert(Row(tenant = t1, entity = entity))) }.sqlState shouldBe "42501"
            }
        } finally {
            owner { it.exec("delete from notification.alert where entity_ref = '$entity'") }
        }
        privileges("alert") shouldBe setOf("SELECT", "INSERT")
    }
}

private val TENANT_A: UUID = UUID.randomUUID()
