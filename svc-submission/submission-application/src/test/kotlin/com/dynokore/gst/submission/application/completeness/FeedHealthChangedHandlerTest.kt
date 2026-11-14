package com.dynokore.gst.submission.application.completeness

import com.dynokore.gst.submission.domain.readiness.CompletenessState
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** In-memory completeness stores shared by the handler tests; one object implements all three ports. */
internal class InMemoryCompleteness :
    FeedControlStates,
    WindowPartStates,
    CompletenessRows {
    data class Stored(
        val row: StoredRow,
        val version: Long,
    )

    val feedRows = mutableMapOf<List<Any>, Stored>()
    val windowRows = mutableMapOf<List<Any>, Stored>()
    var writes = 0

    private fun put(
        map: MutableMap<List<Any>, Stored>,
        key: List<Any>,
        row: StoredRow,
        version: Long,
    ): Boolean {
        val old = map[key]
        if (old != null && old.version >= version) return false
        map[key] = Stored(row, version)
        writes++
        return true
    }

    override fun record(
        tenant: TenantId,
        feed: String,
        entityRef: String,
        period: String,
        control: String,
        failing: Boolean,
        stateVersion: Long,
    ): Boolean =
        put(
            feedRows,
            listOf(tenant, feed, entityRef, period, control),
            StoredRow(
                ControlIds.feed(feed, control),
                if (failing) StoredState.FAILING else StoredState.NOT_FAILING,
            ),
            stateVersion,
        )

    override fun record(
        tenant: TenantId,
        entityRef: String,
        period: String,
        from: LocalDate,
        to: LocalDate,
        state: StoredState,
        stateVersion: Long,
    ): Boolean =
        put(
            windowRows,
            listOf(tenant, entityRef, period, from, to),
            StoredRow(ControlIds.window(period), state),
            stateVersion,
        )

    override fun rows(
        tenant: TenantId,
        entityRef: String,
        period: String,
    ): List<StoredRow> =
        (
            feedRows.entries.map { Triple(it.key[0], it.key[2] to it.key[3], it.value) } +
                windowRows.entries.map { Triple(it.key[0], it.key[1] to it.key[2], it.value) }
        ).filter { (t, ep, _) -> t == tenant && ep == (entityRef to period) }
            .map { it.third.row }
}

internal fun fixedTenants(tenant: TenantId) =
    object : TenantContext {
        override fun current(): TenantId = tenant

        override fun currentActor(): String? = null

        override fun <T> bind(
            tenant: TenantId,
            actor: String,
            block: () -> T,
        ): T = block()
    }

internal fun testEnvelope(tenant: TenantId) =
    Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/feed-svc",
        type = "com.dynokore.gst.test.Changed.v1",
        subject = "E",
        time = Instant.parse("2026-11-14T08:00:00Z"),
        tenant = tenant,
        entity = EntityRef("E"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = "{}",
    )

/** The FeedHealthChanged consumer over in-memory stores (INV-AC-22204). */
class FeedHealthChangedHandlerTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000021"))
    private val other = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000022"))
    private val memory = InMemoryCompleteness()
    private val feedStates: FeedControlStates = memory
    private val windowStates: WindowPartStates = memory
    private val rowReader: CompletenessRows = memory
    private var next: () -> FeedHealthChangedData = { error("unset") }

    private fun handlerFor(t: TenantId) = FeedHealthChangedHandler(fixedTenants(t), { next() }, feedStates)

    private fun deliver(
        control: String,
        state: FeedHealthState,
        version: Long,
        t: TenantId = tenant,
    ): HandleOutcome {
        next = { FeedHealthChangedData("F1", "E", "P", control, state, version) }
        return handlerFor(t).handle(testEnvelope(t))
    }

    private fun derived() = StoredCompleteness.derive(rowReader.rows(tenant, "E", "P"))

    @Test
    fun `INV-AC-22204 FAILED v2 APPLIED, CLEARED v1 and repeated FAILED v2 IGNORED, CLEARED v3 APPLIED`() {
        deliver("COUNT", FeedHealthState.FAILED, 2) shouldBe HandleOutcome.APPLIED
        derived() shouldBe CompletenessState.Failed(listOf("feed:F1/COUNT"))
        deliver("COUNT", FeedHealthState.CLEARED, 1) shouldBe HandleOutcome.IGNORED
        deliver("COUNT", FeedHealthState.FAILED, 2) shouldBe HandleOutcome.IGNORED
        memory.writes shouldBe 1
        derived() shouldBe CompletenessState.Failed(listOf("feed:F1/COUNT"))
        deliver("COUNT", FeedHealthState.CLEARED, 3) shouldBe HandleOutcome.APPLIED
        memory.feedRows.values
            .single()
            .version shouldBe 3
        derived() shouldBe CompletenessState.Passed
    }

    @Test
    fun `INV-AC-22204 a versionless FAILED with no row is APPLIED at version 0, the second IGNORED`() {
        deliver("VALUE", FeedHealthState.FAILED, 0) shouldBe HandleOutcome.APPLIED
        memory.feedRows.values
            .single()
            .version shouldBe 0
        deliver("VALUE", FeedHealthState.FAILED, 0) shouldBe HandleOutcome.IGNORED
        deliver("VALUE", FeedHealthState.CLEARED, 0) shouldBe HandleOutcome.IGNORED
        memory.writes shouldBe 1
        deliver("VALUE", FeedHealthState.CLEARED, 1) shouldBe HandleOutcome.APPLIED
    }

    @Test
    fun `INV-AC-22204 a CLEARED for another control of the same feed leaves the FAILED row of the first`() {
        deliver("COUNT", FeedHealthState.FAILED, 2)
        deliver("VALUE", FeedHealthState.CLEARED, 5) shouldBe HandleOutcome.APPLIED
        derived() shouldBe CompletenessState.Failed(listOf("feed:F1/COUNT"))
    }

    @Test
    fun `INV-AC-22204 an event of another tenant does not change a row of this tenant`() {
        deliver("COUNT", FeedHealthState.FAILED, 2)
        deliver("COUNT", FeedHealthState.CLEARED, 9, other) shouldBe HandleOutcome.APPLIED
        derived() shouldBe CompletenessState.Failed(listOf("feed:F1/COUNT"))
        StoredCompleteness.derive(rowReader.rows(other, "E", "P")) shouldBe CompletenessState.Passed
    }

    @Test
    fun `INV-AC-22204 a malformed event is a ContractViolation that writes nothing and carries no value`() {
        val failing = FeedHealthChangedDataReader { throw ContractViolation("member is missing") }
        val h = FeedHealthChangedHandler(fixedTenants(tenant), failing, feedStates)
        val e = shouldThrow<ContractViolation> { h.handle(testEnvelope(tenant)) }
        e.message.orEmpty() shouldNotContain "F1"
        memory.writes shouldBe 0
    }

    @Test
    fun `INV-AC-22204 one fixture implements the three ports and is called through the port types`() {
        feedStates.record(tenant, "F1", "E", "P", "COUNT", true, 1) shouldBe true
        windowStates.record(
            tenant,
            "E",
            "P",
            LocalDate.of(2026, 4, 1),
            LocalDate.of(2026, 6, 30),
            StoredState.NOT_FAILING,
            1,
        ) shouldBe
            true
        rowReader.rows(tenant, "E", "P").size shouldBe 2
    }
}
