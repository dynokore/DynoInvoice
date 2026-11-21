package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class HandleFeedHealthChangedTest {
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val t3 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
    private val clock = Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC)

    private class FakeStates : FeedHealthStates {
        val rows = mutableMapOf<List<Any>, FeedHealthState>()
        var saves = 0

        override fun find(
            tenant: TenantId,
            feed: String,
            entityRef: String,
            period: String,
            control: String,
        ) = rows[listOf(tenant, feed, entityRef, period, control)]

        override fun save(state: FeedHealthState) {
            saves++
            rows[listOf(state.tenant, state.feed, state.entityRef, state.period, state.control)] = state
        }
    }

    private class FakeAlerts(
        private val states: FakeStates,
    ) : FeedAlertStore {
        val drafts = mutableListOf<FeedAlertDraft>()
        val open = mutableMapOf<List<Any>, AlertId>()
        val resolved = mutableListOf<AlertId>()

        override fun raise(draft: FeedAlertDraft): AlertId {
            drafts += draft
            val key = listOf(draft.tenant, draft.entityRef, draft.alertClass, draft.trigger, draft.periodRef)
            return open.getOrPut(key) { AlertId("A${open.size + 1}") }
        }

        override fun resolveIfNoFeedFailed(
            tenant: TenantId,
            entityRef: String,
            period: String,
            control: String,
            at: Instant,
        ): Boolean {
            val stillFailed =
                states.rows.values.any {
                    it.tenant == tenant && it.entityRef == entityRef && it.period == period &&
                        it.control == control && it.state == "FAILED"
                }
            val id =
                if (stillFailed) null else open.remove(listOf(tenant, entityRef, AlertClass.COVERAGE, control, period))
            id?.let { resolved += it }
            return id != null
        }
    }

    private val holders = mutableMapOf<Pair<TenantId, String>, List<String>>()
    private val states = FakeStates()
    private val alerts = FakeAlerts(states)
    private val handler =
        HandleFeedHealthChanged(
            states,
            alerts,
            ResolveDefaultRecipients { tenant, role -> holders[tenant to role].orEmpty() },
            clock,
        )

    init {
        holders[t1 to "integration.engineer"] = listOf("u-b", "u-a")
        holders[t2 to "integration.engineer"] = listOf("u-other")
    }

    private fun payload(
        state: String = "FAILED",
        version: Any? = 1,
        feed: Any? = "F",
        control: Any? = "WATERMARK",
        entityRef: Any? = "E",
    ): Map<String, Any?> =
        buildMap {
            put("feed", feed)
            put("entityRef", entityRef)
            put("period", "2026Q1")
            put("control", control)
            put("state", state)
            if (version != null) put("stateVersion", version)
        }

    @Test
    @DisplayName(
        "INV-AC-26515 FAILED raises one COVERAGE alert with trigger and period " +
            "and returns the recipients of its own tenant",
    )
    fun `INV-AC-26515 FAILED raises one COVERAGE alert and returns the recipients of its own tenant`() {
        handler.handle(t1, payload()) shouldBe FeedHealthResult.APPLIED_RAISED
        alerts.drafts.size shouldBe 1
        val d = alerts.drafts.single()
        d.alertClass shouldBe AlertClass.COVERAGE
        d.trigger shouldBe "WATERMARK"
        d.periodRef shouldBe "2026Q1"
        d.tenant shouldBe t1
        val row = states.find(t1, "F", "E", "2026Q1", "WATERMARK")!!
        row.recipients shouldBe listOf("u-a", "u-b")
        row.alertId shouldBe AlertId("A1")
    }

    @Test
    fun `INV-AC-26515 a repeat of the same event raises nothing more`() {
        handler.handle(t1, payload())
        handler.handle(t1, payload()) shouldBe FeedHealthResult.IGNORED_STALE
        alerts.drafts.size shouldBe 1
        states.saves shouldBe 1
    }

    @Test
    fun `INV-AC-26515 CLEARED resolves the alert`() {
        handler.handle(t1, payload())
        handler.handle(t1, payload("CLEARED", 2)) shouldBe FeedHealthResult.APPLIED_CLEARED
        alerts.resolved shouldBe listOf(AlertId("A1"))
        states.find(t1, "F", "E", "2026Q1", "WATERMARK")!!.state shouldBe "CLEARED"
    }

    @Test
    fun `INV-AC-26515 CLEARED does not resolve while another feed is still FAILED for the same key`() {
        handler.handle(t1, payload(feed = "F"))
        handler.handle(t1, payload(feed = "G"))
        handler.handle(t1, payload("CLEARED", 2, feed = "F")) shouldBe FeedHealthResult.APPLIED_CLEARED
        alerts.resolved shouldBe emptyList()
        handler.handle(t1, payload("CLEARED", 2, feed = "G"))
        alerts.resolved.size shouldBe 1
    }

    @Test
    fun `INV-AC-26515 a stale or equal stateVersion changes nothing`() {
        handler.handle(t1, payload("FAILED", 3))
        handler.handle(t1, payload("CLEARED", 2)) shouldBe FeedHealthResult.IGNORED_STALE
        handler.handle(t1, payload("CLEARED", 3)) shouldBe FeedHealthResult.IGNORED_STALE
        states.find(t1, "F", "E", "2026Q1", "WATERMARK")!!.state shouldBe "FAILED"
        states.saves shouldBe 1
        alerts.resolved shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26515 an event without stateVersion applies only when no row exists`() {
        handler.handle(t1, payload(version = null)) shouldBe FeedHealthResult.APPLIED_RAISED
        handler.handle(t1, payload("CLEARED", null)) shouldBe FeedHealthResult.IGNORED_STALE
        states.saves shouldBe 1
        alerts.resolved shouldBe emptyList()
    }

    @Test
    @DisplayName("INV-AC-26515 no integration.engineer and no tenant.admin holder throws and writes nothing")
    fun `INV-AC-26515 no integration-engineer and no tenant-admin holder throws and writes nothing`() {
        shouldThrow<FeedHealthNotDelivered> { handler.handle(t3, payload()) }
        states.saves shouldBe 0
        alerts.drafts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26515 a payload with a bad control, state, empty feed or over-long entityRef is refused`() {
        listOf(
            payload(control = "OTHER"),
            payload(control = "watermark"),
            payload(control = "WATERMARKS"),
            payload(control = ""),
            payload(state = "OPEN"),
            payload(feed = ""),
            payload(entityRef = "x".repeat(65)),
            payload(version = 0),
            payload(version = "1"),
        ).forEach { shouldThrow<ContractViolation> { handler.handle(t1, it) } }
        states.saves shouldBe 0
        alerts.drafts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-26515 the alert class passed to the store is COVERAGE in every call`() {
        listOf("SEQUENCE", "COUNT", "VALUE", "WATERMARK", "MANIFEST").forEachIndexed { i, c ->
            handler.handle(t1, payload(control = c, entityRef = "E$i"))
        }
        alerts.drafts.size shouldBe 5
        alerts.drafts.all { it.alertClass == AlertClass.COVERAGE } shouldBe true
    }
}
