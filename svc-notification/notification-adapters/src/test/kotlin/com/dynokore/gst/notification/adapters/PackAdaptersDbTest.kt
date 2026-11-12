package com.dynokore.gst.notification.adapters

import com.dynokore.gst.notification.adapters.persistence.JooqPackAlerts
import com.dynokore.gst.notification.adapters.persistence.JooqPackPolicy
import com.dynokore.gst.notification.application.alerting.PackPolicy
import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.PACK_ALERT_TRIGGER
import com.dynokore.gst.notification.domain.alerting.PackAlertDraft
import com.dynokore.gst.notification.domain.alerting.PolicyTier
import com.dynokore.gst.notification.domain.alerting.TENANT_WIDE_ENTITY_REF
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-REQ-1080, INV-Q-20864 A: the jOOQ adapters of the pack policy source and the pack alert store (INV-AC-20911). */
class PackAdaptersDbTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val clock = Clock.fixed(Instant.parse("2026-11-12T01:02:03Z"), ZoneOffset.UTC)
    private val draft = PackAlertDraft(AlertClass.PACK_STALENESS, PACK_ALERT_TRIGGER, 2, TENANT_WIDE_ENTITY_REF)

    private fun <T> bound(
        tenant: UUID,
        user: String = NotificationDb.RUNTIME,
        password: String = NotificationDb.RUNTIME_PASSWORD,
        block: (Connection, DSLContext) -> T,
    ): T =
        NotificationDb.connect(user, password).use { c ->
            c.autoCommit = false
            SignedBindings.bind(c, tenant)
            try {
                block(c, DSL.using(c, SQLDialect.POSTGRES))
            } finally {
                c.rollback()
            }
        }

    private fun exec(sql: String) = NotificationDb.superuser().use { it.createStatement().use { s -> s.execute(sql) } }

    private fun seedPolicy(
        tenant: UUID,
        cls: String,
        recipients: String = "[\"u1\",\"u2\"]",
        tiers: String = "[{\"tier\":1,\"daysToDue\":30},{\"tier\":2,\"daysToDue\":14}]",
    ) = exec(
        "insert into notification.alert_policy (tenant_id, entity_ref, alert_class, recipients, channels, " +
            "escalation_tiers, updated_at) values ('$tenant', '$TENANT_WIDE_ENTITY_REF', '$cls', '$recipients', " +
            "'[]', '$tiers', now())",
    )

    private fun seedAlert(
        tenant: UUID,
        cls: String,
        state: String,
        snoozedUntil: String = "null",
        trigger: String = PACK_ALERT_TRIGGER,
    ) = exec(
        "insert into notification.alert (tenant_id, alert_id, entity_ref, alert_class, state, raised_at, " +
            "\"trigger\", snoozed_until) values ('$tenant', gen_random_uuid(), '$TENANT_WIDE_ENTITY_REF', " +
            "'$cls', '$state', now(), '$trigger', $snoozedUntil)",
    )

    private fun cleanup() =
        exec("delete from notification.alert where tenant_id in ('$a', '$b')").also {
            exec("delete from notification.alert_policy where tenant_id in ('$a', '$b')")
        }

    private fun states(
        c: Connection,
        tenant: UUID,
    ): List<List<String?>> =
        c.createStatement().use { s ->
            s
                .executeQuery(
                    "select state, alert_class, \"trigger\", entity_ref, period_ref, current_tier::text, " +
                        "snoozed_until::text from notification.alert where tenant_id = '$tenant' " +
                        "order by state, alert_class",
                ).use { r ->
                    generateSequence { if (r.next()) (1..7).map { r.getString(it) } else null }.toList()
                }
        }

    @Test
    fun `INV-AC-20911 the policy has its recipients and its tiers with daysToDue`() {
        seedPolicy(a, "PACK_STALENESS")
        try {
            bound(a) { _, dsl -> JooqPackPolicy(dsl).policy(TenantId(a)) } shouldBe
                PackPolicy(listOf("u1", "u2"), listOf(PolicyTier(1, 30), PolicyTier(2, 14)))
        } finally {
            cleanup()
        }
    }

    @Test
    fun `INV-AC-20911 another class and another tenant read null`() {
        seedPolicy(a, "PACK_STALENESS")
        seedPolicy(b, "DEADLINE")
        try {
            bound(b) { _, dsl -> JooqPackPolicy(dsl).policy(TenantId(b)) } shouldBe null
            bound(b) { _, dsl -> JooqPackPolicy(dsl).policy(TenantId(a)) } shouldBe null
        } finally {
            cleanup()
        }
    }

    @Test
    fun `INV-AC-20911 first raise stores one RAISED row and answers true`() {
        bound(a) { c, dsl ->
            JooqPackAlerts(dsl, clock).raise(TenantId(a), draft) shouldBe true
            states(c, a) shouldBe
                listOf(
                    listOf("RAISED", "PACK_STALENESS", "PACK_NOT_ACTIVATED", TENANT_WIDE_ENTITY_REF, null, "2", null),
                )
            c.createStatement().use { s ->
                s.executeQuery("select raised_at::text from notification.alert where tenant_id = '$a'").use { r ->
                    r.next() shouldBe true
                    r.getString(1) shouldBe "2026-11-12 01:02:03+00"
                }
            }
        }
    }

    @Test
    fun `INV-AC-20911 repeated raise answers false and leaves one row`() {
        bound(a) { c, dsl ->
            val store = JooqPackAlerts(dsl, clock)
            store.raise(TenantId(a), draft) shouldBe true
            store.raise(TenantId(a), draft) shouldBe false
            states(c, a).size shouldBe 1
        }
    }

    @Test
    fun `INV-AC-20911 resolve sets RESOLVED and answers true, then false when nothing is open`() {
        bound(a) { c, dsl ->
            val store = JooqPackAlerts(dsl, clock)
            store.raise(TenantId(a), draft) shouldBe true
            store.resolveOpen(TenantId(a)) shouldBe true
            states(c, a).map { it[0] to it[6] } shouldBe listOf("RESOLVED" to null)
            store.resolveOpen(TenantId(a)) shouldBe false
        }
    }

    @Test
    fun `INV-AC-20911 resolve of a snoozed alert clears snoozed_until`() {
        seedAlert(a, "PACK_STALENESS", "SNOOZED", "now() + interval '1 day'")
        try {
            bound(a) { c, dsl ->
                JooqPackAlerts(dsl, clock).resolveOpen(TenantId(a)) shouldBe true
                states(c, a).map { it[0] to it[6] } shouldBe listOf("RESOLVED" to null)
            }
        } finally {
            cleanup()
        }
    }

    @Test
    fun `INV-AC-20911 raise after resolve stores a second row`() {
        bound(a) { c, dsl ->
            val store = JooqPackAlerts(dsl, clock)
            store.raise(TenantId(a), draft) shouldBe true
            store.resolveOpen(TenantId(a)) shouldBe true
            store.raise(TenantId(a), draft) shouldBe true
            states(c, a).map { it[0] } shouldBe listOf("RAISED", "RESOLVED")
        }
    }

    @Test
    fun `INV-AC-20911 other class and other tenant are untouched by a resolve`() {
        seedAlert(a, "DEADLINE", "RAISED", trigger = "X")
        seedAlert(a, "COVERAGE", "ACKNOWLEDGED", trigger = "X")
        seedAlert(a, "BACKLOG", "SNOOZED", "now() + interval '1 day'", trigger = "X")
        seedAlert(a, "SECURITY", "RESOLVED", trigger = "X")
        seedAlert(b, "PACK_STALENESS", "RAISED")
        try {
            bound(a) { c, dsl ->
                JooqPackAlerts(dsl, clock).resolveOpen(TenantId(a)) shouldBe false
                states(c, a).map { it[0] } shouldBe listOf("ACKNOWLEDGED", "RAISED", "RESOLVED", "SNOOZED")
            }
            bound(b) { c, dsl ->
                JooqPackAlerts(dsl, clock).resolveOpen(TenantId(a)) shouldBe false
                states(c, b).map { it[0] } shouldBe listOf("RAISED")
            }
        } finally {
            cleanup()
        }
    }

    @Test
    fun `INV-AC-20911 the second tenant raises its own alert`() {
        bound(a) { _, dsl -> JooqPackAlerts(dsl, clock).raise(TenantId(a), draft) shouldBe true }
        bound(b) { _, dsl -> JooqPackAlerts(dsl, clock).raise(TenantId(b), draft) shouldBe true }
        bound(b) { _, dsl -> shouldThrow<Exception> { JooqPackAlerts(dsl, clock).raise(TenantId(a), draft) } }
    }

    @Test
    fun `INV-AC-20911 the runtime role is refused an update of another column and any delete`() {
        seedAlert(a, "PACK_STALENESS", "RAISED")
        try {
            bound(a) { c, _ ->
                c.createStatement().use { s ->
                    val e = shouldThrow<SQLException> { s.execute("update notification.alert set entity_ref = 'x'") }
                    e.sqlState shouldBe "42501"
                }
            }
            bound(a) { c, _ ->
                c.createStatement().use { s ->
                    val e = shouldThrow<SQLException> { s.execute("delete from notification.alert") }
                    e.sqlState shouldBe "42501"
                }
            }
        } finally {
            cleanup()
        }
    }

    @Test
    fun `INV-AC-20911 tenant isolation, no row of the first tenant is readable under the second`() {
        seedAlert(a, "PACK_STALENESS", "RAISED")
        seedPolicy(a, "PACK_STALENESS")
        try {
            bound(b) { c, _ ->
                states(c, a) shouldBe emptyList()
                c.createStatement().use { s ->
                    s.executeQuery("select count(*) from notification.alert_policy").use { r ->
                        r.next()
                        r.getInt(1) shouldBe 0
                    }
                }
            }
        } finally {
            cleanup()
        }
    }
}
