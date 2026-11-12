package com.dynokore.gst.notification.adapters

import com.dynokore.gst.notification.adapters.persistence.JooqBacklogAlerts
import com.dynokore.gst.notification.adapters.persistence.JooqBacklogThresholds
import com.dynokore.gst.notification.application.alerting.BacklogAlertDraft
import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.BACKLOG_ALERT_TRIGGER
import com.dynokore.gst.notification.domain.alerting.BacklogThresholds
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** INV-REQ-1370, slice O1c-3: the jOOQ adapters of the backlog threshold source and the alert store (INV-AC-20818). */
class BacklogAdaptersDbTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val raisedAt = Instant.parse("2026-11-12T01:02:03Z")

    private fun <T> bound(
        tenant: UUID,
        block: (Connection, DSLContext) -> T,
    ): T =
        NotificationDb.connect(NotificationDb.RUNTIME, NotificationDb.RUNTIME_PASSWORD).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                block(c, DSL.using(c, SQLDialect.POSTGRES))
            } finally {
                c.rollback()
            }
        }

    private fun seed(
        tenant: UUID,
        entity: String,
        cls: String,
        columns: String = "",
        values: String = "",
    ) = NotificationDb.superuser().use {
        it.createStatement().use { s ->
            s.execute(
                "insert into notification.alert_policy (tenant_id, entity_ref, alert_class, recipients, channels, " +
                    "updated_at$columns) values ('$tenant', '$entity', '$cls', '[]', '[]', now()$values)",
            )
        }
    }

    private fun cleanup(entity: String) =
        NotificationDb.superuser().use {
            it.createStatement().use { s ->
                s.execute("delete from notification.alert_policy where entity_ref = '$entity'")
                s.execute("delete from notification.alert where entity_ref = '$entity'")
            }
        }

    private fun entity() = "e-" + UUID.randomUUID()

    private fun draft(
        tenant: UUID,
        entity: String,
        cls: AlertClass = AlertClass.BACKLOG,
        period: String = "2026-10",
    ) = BacklogAlertDraft(TenantId(tenant), cls, BACKLOG_ALERT_TRIGGER, entity, period, raisedAt)

    private fun rows(
        c: Connection,
        entity: String,
    ): List<List<String?>> =
        c.createStatement().use { s ->
            s
                .executeQuery(
                    "select state, \"trigger\", alert_class, period_ref, raised_at::text from notification.alert " +
                        "where entity_ref = '$entity' order by period_ref, alert_class",
                ).use { r ->
                    generateSequence {
                        if (r.next()) (1..5).map { r.getString(it) } else null
                    }.toList()
                }
        }

    private fun read(
        tenant: UUID,
        entity: String,
        cls: AlertClass = AlertClass.BACKLOG,
    ) = bound(tenant) { _, dsl -> JooqBacklogThresholds(dsl).thresholds(TenantId(tenant), entity, cls) }

    @Test
    fun `INV-AC-20818 four values are read`() {
        val e = entity()
        seed(
            a,
            e,
            "BACKLOG",
            ", max_queue_depth, max_oldest_unsent_age_seconds, max_failure_ratio, max_unacknowledged_count",
            ", 7, 86400, 0.25, 3",
        )
        try {
            read(a, e) shouldBe BacklogThresholds(7L, 86400L, 0.25, 3L)
        } finally {
            cleanup(e)
        }
    }

    @Test
    fun `INV-AC-20818 a null column is an absent member and no default is invented`() {
        val e = entity()
        seed(a, e, "BACKLOG", ", max_queue_depth, max_failure_ratio", ", 5, null")
        try {
            read(a, e) shouldBe BacklogThresholds(5L, null, null, null)
        } finally {
            cleanup(e)
        }
    }

    @Test
    fun `INV-AC-20818 no row gives null and another class row is not read`() {
        val e = entity()
        read(a, e) shouldBe null
        seed(a, e, "MISSING_ACKNOWLEDGEMENT", ", max_unacknowledged_count", ", 4")
        try {
            read(a, e, AlertClass.BACKLOG) shouldBe null
            read(a, e, AlertClass.MISSING_ACKNOWLEDGEMENT) shouldBe BacklogThresholds(null, null, null, 4L)
        } finally {
            cleanup(e)
        }
    }

    @Test
    fun `INV-AC-20818 tenant isolation, the policy of tenant A is not read under the binding of tenant B`() {
        val e = entity()
        seed(a, e, "BACKLOG", ", max_queue_depth", ", 7")
        try {
            read(a, e) shouldBe BacklogThresholds(7L, null, null, null)
            read(b, e) shouldBe null
            bound(b) { _, dsl -> JooqBacklogThresholds(dsl).thresholds(TenantId(a), e, AlertClass.BACKLOG) } shouldBe
                null
        } finally {
            cleanup(e)
        }
    }

    @Test
    fun `INV-AC-20818 first raise inserts one RAISED row with the trigger and answers true`() {
        val e = entity()
        bound(a) { c, dsl ->
            JooqBacklogAlerts(dsl).raise(draft(a, e)) shouldBe true
            rows(c, e) shouldBe
                listOf(listOf("RAISED", "BACKLOG_THRESHOLD", "BACKLOG", "2026-10", "2026-11-12 01:02:03+00"))
        }
    }

    @Test
    fun `INV-AC-20818 repeated raise answers false and leaves one row, a conflict is no exception`() {
        val e = entity()
        bound(a) { c, dsl ->
            val store = JooqBacklogAlerts(dsl)
            store.raise(draft(a, e)) shouldBe true
            store.raise(draft(a, e)) shouldBe false
            store.raise(draft(a, e)) shouldBe false
            rows(c, e).size shouldBe 1
        }
    }

    @Test
    fun `INV-AC-20818 another period or another class answers true`() {
        val e = entity()
        bound(a) { c, dsl ->
            val store = JooqBacklogAlerts(dsl)
            store.raise(draft(a, e)) shouldBe true
            store.raise(draft(a, e, period = "2026-11")) shouldBe true
            store.raise(draft(a, e, cls = AlertClass.MISSING_ACKNOWLEDGEMENT)) shouldBe true
            rows(c, e).size shouldBe 3
        }
    }

    @Test
    fun `INV-AC-20818 another tenant answers true and a draft of another tenant is refused`() {
        val e = entity()
        bound(a) { _, dsl -> JooqBacklogAlerts(dsl).raise(draft(a, e)) shouldBe true }
        bound(b) { _, dsl -> JooqBacklogAlerts(dsl).raise(draft(b, e)) shouldBe true }
        bound(b) { _, dsl ->
            shouldThrow<Exception> { JooqBacklogAlerts(dsl).raise(draft(a, e)) }
        }
    }

    @Test
    fun `INV-AC-20818 after acknowledgement the same key answers true`() {
        val e = entity()
        NotificationDb.superuser().use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, a)
            try {
                val store = JooqBacklogAlerts(DSL.using(c, SQLDialect.POSTGRES))
                store.raise(draft(a, e)) shouldBe true
                store.raise(draft(a, e)) shouldBe false
                c.createStatement().use {
                    it.execute("update notification.alert set state = 'ACKNOWLEDGED' where entity_ref = '$e'")
                }
                store.raise(draft(a, e)) shouldBe true
                rows(c, e).map { it[0] }.toSet() shouldBe setOf("ACKNOWLEDGED", "RAISED")
                rows(c, e).size shouldBe 2
            } finally {
                c.rollback()
            }
        }
    }
}
