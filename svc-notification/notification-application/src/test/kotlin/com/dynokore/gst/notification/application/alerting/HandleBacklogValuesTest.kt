package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.BacklogThresholds
import com.dynokore.gst.notification.domain.alerting.BacklogValue
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class HandleBacklogValuesTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val now = Instant.parse("2026-11-12T01:02:03Z")
    private val reads = mutableListOf<Triple<TenantId, String, AlertClass>>()
    private val drafts = mutableListOf<BacklogAlertDraft>()
    private val open = mutableSetOf<Triple<String, AlertClass, String>>()
    private var rows: Map<AlertClass, BacklogThresholds?> =
        mapOf(
            AlertClass.BACKLOG to BacklogThresholds(10, 100, 0.5, null),
            AlertClass.MISSING_ACKNOWLEDGEMENT to BacklogThresholds(null, null, null, 5),
        )

    private val handler =
        HandleBacklogValues(
            thresholds =
                object : BacklogThresholdSource {
                    override fun thresholds(
                        tenant: TenantId,
                        entityRef: String,
                        alertClass: AlertClass,
                    ): BacklogThresholds? {
                        reads += Triple(tenant, entityRef, alertClass)
                        return rows[alertClass]
                    }
                },
            store =
                object : BacklogAlertStore {
                    override fun raise(draft: BacklogAlertDraft): Boolean {
                        drafts += draft
                        return open.add(Triple(draft.entityRef, draft.alertClass, draft.periodRef))
                    }
                },
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun payload(
        depth: Any? = 1,
        age: Any? = 1,
        rate: Any? = 0.1,
        unack: Any? = 1,
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> =
        buildMap {
            put("entityRef", "E1")
            put("periodRef", "2026-Q3")
            put("queueDepth", depth)
            if (age != null) put("oldestUnsentAgeSeconds", age)
            put("failureRate", rate)
            put("unacknowledgedCount", unack)
            put("measuredAt", "2026-11-12T01:00:00Z")
            putAll(extra)
        }

    private fun kinds(p: Map<String, Any?>) = handler.handle(tenant, p).results.map { it.kind }

    @Test
    fun `INV-AC-20816 the source is read once per class in order and with the given tenant`() {
        handler.handle(tenant, payload())
        reads shouldBe
            listOf(
                Triple(tenant, "E1", AlertClass.BACKLOG),
                Triple(tenant, "E1", AlertClass.MISSING_ACKNOWLEDGEMENT),
            )
    }

    @Test
    fun `INV-AC-20816 a backlog breach raises one BACKLOG draft with trigger entity period and clock`() {
        kinds(payload(depth = 11)) shouldBe listOf(BacklogResultKind.RAISED, BacklogResultKind.WITHIN_THRESHOLD)
        drafts shouldBe
            listOf(BacklogAlertDraft(tenant, AlertClass.BACKLOG, "BACKLOG_THRESHOLD", "E1", "2026-Q3", now))
    }

    @Test
    fun `INV-AC-20816 an unacknowledged breach raises one MISSING_ACKNOWLEDGEMENT draft`() {
        kinds(payload(unack = 6)) shouldBe listOf(BacklogResultKind.WITHIN_THRESHOLD, BacklogResultKind.RAISED)
        drafts shouldBe
            listOf(
                BacklogAlertDraft(tenant, AlertClass.MISSING_ACKNOWLEDGEMENT, "BACKLOG_THRESHOLD", "E1", "2026-Q3", now),
            )
    }

    @Test
    fun `INV-AC-20816 three backlog values breached together raise one draft`() {
        val r = handler.handle(tenant, payload(depth = 11, age = 101L, rate = 1))
        drafts.size shouldBe 1
        r.results[0].breached shouldBe
            listOf(BacklogValue.QUEUE_DEPTH, BacklogValue.OLDEST_UNSENT_AGE, BacklogValue.FAILURE_RATE)
    }

    @Test
    fun `INV-AC-20816 a store answering false gives ALREADY_OPEN and no second draft per call`() {
        kinds(payload(depth = 11))[0] shouldBe BacklogResultKind.RAISED
        kinds(payload(depth = 12))[0] shouldBe BacklogResultKind.ALREADY_OPEN
        drafts.size shouldBe 2
        drafts.map { it.alertClass } shouldBe listOf(AlertClass.BACKLOG, AlertClass.BACKLOG)
    }

    @Test
    fun `INV-AC-20816 no row or all thresholds absent gives NOT_CONFIGURED with names and no draft`() {
        rows = mapOf(AlertClass.BACKLOG to null, AlertClass.MISSING_ACKNOWLEDGEMENT to BacklogThresholds())
        val r = handler.handle(tenant, payload(depth = 1000, unack = 1000))
        r.results.map { it.kind } shouldBe listOf(BacklogResultKind.NOT_CONFIGURED, BacklogResultKind.NOT_CONFIGURED)
        r.results[0].notConfigured shouldBe
            listOf(BacklogValue.QUEUE_DEPTH, BacklogValue.OLDEST_UNSENT_AGE, BacklogValue.FAILURE_RATE)
        r.results[1].notConfigured shouldBe listOf(BacklogValue.UNACKNOWLEDGED_COUNT)
        drafts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20816 values within or equal to their maxima give WITHIN_THRESHOLD and no draft`() {
        kinds(payload(depth = 10, age = 100, rate = 0.5, unack = 5)) shouldBe
            listOf(BacklogResultKind.WITHIN_THRESHOLD, BacklogResultKind.WITHIN_THRESHOLD)
        drafts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20816 an absent oldestUnsentAgeSeconds is accepted and Long integers too`() {
        kinds(payload(age = null, depth = 3L, unack = 2L)) shouldBe
            listOf(BacklogResultKind.WITHIN_THRESHOLD, BacklogResultKind.WITHIN_THRESHOLD)
    }

    @Test
    fun `INV-AC-20816 every invalid payload member throws ContractViolation and reads and raises nothing`() {
        val bad =
            listOf(
                payload(extra = mapOf("entityRef" to null)),
                payload(extra = mapOf("entityRef" to " ")),
                payload(extra = mapOf("entityRef" to "x".repeat(65))),
                payload(extra = mapOf("periodRef" to null)),
                payload(extra = mapOf("periodRef" to "")),
                payload(extra = mapOf("periodRef" to "x".repeat(33))),
                payload(depth = -1),
                payload(depth = 1.5),
                payload(depth = "1"),
                payload(depth = null),
                payload(unack = -1),
                payload(unack = 2.0),
                payload(unack = null),
                payload(rate = 1.01),
                payload(rate = -0.01),
                payload(rate = null),
                payload(rate = "0.1"),
                payload(age = -1),
                payload(age = 1.5),
                payload(extra = mapOf("measuredAt" to "not a date")),
                payload(extra = mapOf("measuredAt" to null)),
            )
        for (p in bad) {
            assertThrows<ContractViolation> { handler.handle(tenant, p) }
        }
        reads shouldBe emptyList()
        drafts shouldBe emptyList()
    }
}
