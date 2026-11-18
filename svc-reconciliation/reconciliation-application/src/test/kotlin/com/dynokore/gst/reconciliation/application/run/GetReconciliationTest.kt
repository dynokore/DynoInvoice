package com.dynokore.gst.reconciliation.application.run

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class GetReconciliationTest {
    private val run = RecordedRun("R1", "E1", "P1", 2, Instant.parse("2026-04-02T00:00:00Z"), "F5")
    private val snapshot = RunSnapshot(run, emptyList(), BigDecimal.ZERO, emptyList(), false)

    private class Reader(
        private val held: Map<Triple<String, String, String>, RunSnapshot>,
    ) : RunReader {
        val calls = mutableListOf<Triple<String, String, String>>()

        override fun latest(
            tenant: String,
            entityRef: String,
            periodRef: String,
        ): RunSnapshot? {
            calls += Triple(tenant, entityRef, periodRef)
            return held[Triple(tenant, entityRef, periodRef)]
        }

        override fun page(
            tenant: String,
            entityRef: String,
            periodRef: String,
            limit: Int,
            after: RunCursor?,
        ): List<RecordedRun> = error("not used")

        override fun find(
            tenant: String,
            runId: String,
        ): RunSnapshot? = error("not used")
    }

    private class Returns(
        private val recorded: Set<Triple<String, String, String>>,
    ) : ReturnRecorded {
        val calls = mutableListOf<Triple<String, String, String>>()

        override fun exists(
            tenant: String,
            entityRef: String,
            periodRef: String,
        ): Boolean {
            calls += Triple(tenant, entityRef, periodRef)
            return Triple(tenant, entityRef, periodRef) in recorded
        }
    }

    private val reader = Reader(mapOf(Triple("T1", "E1", "P1") to snapshot))
    private val returns = Returns(setOf(Triple("T1", "E1", "P2")))
    private val use = GetReconciliation(reader, returns)

    @Test
    fun `INV-AC-24418 latest run answers Found unchanged and ReturnRecorded is not called`() {
        use.execute("T1", "E1", "P1") shouldBe ReconciliationView.Found(snapshot)
        (use.execute("T1", "E1", "P1") as ReconciliationView.Found).snapshot shouldBe snapshot
        returns.calls.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-24418 recorded return without run answers Pending after one ReturnRecorded call`() {
        use.execute("T1", "E1", "P2") shouldBe ReconciliationView.Pending("E1", "P2")
        returns.calls shouldBe listOf(Triple("T1", "E1", "P2"))
    }

    @Test
    fun `INV-AC-24418 no return answers NoReturn PER-008 after one ReturnRecorded call`() {
        val view = use.execute("T1", "E1", "P3")
        view shouldBe ReconciliationView.NoReturn("PER-008")
        (view as ReconciliationView.NoReturn).code shouldBe "PER-008"
        returns.calls shouldBe listOf(Triple("T1", "E1", "P3"))
    }

    @Test
    fun `INV-AC-24418 other tenant holds nothing and both ports see only that tenant`() {
        use.execute("T2", "E1", "P1") shouldBe ReconciliationView.NoReturn("PER-008")
        reader.calls shouldBe listOf(Triple("T2", "E1", "P1"))
        returns.calls shouldBe listOf(Triple("T2", "E1", "P1"))
    }

    @Test
    fun `INV-AC-24418 throwing RunReader propagates and ReturnRecorded is not called`() {
        val boom = IllegalStateException("read failed")
        val failing =
            object : RunReader by reader {
                override fun latest(
                    tenant: String,
                    entityRef: String,
                    periodRef: String,
                ): RunSnapshot? = throw boom
            }
        val use = GetReconciliation(failing, returns)
        val thrown = shouldThrow<IllegalStateException> { use.execute("T1", "E1", "P2") }
        thrown shouldBe boom
        returns.calls.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-24418 throwing ReturnRecorded propagates and no NoReturn is produced`() {
        val boom = IllegalStateException("return read failed")
        val failing = ReturnRecorded { _, _, _ -> throw boom }
        var produced: ReconciliationView? = null
        shouldThrow<IllegalStateException> {
            produced = GetReconciliation(reader, failing).execute("T1", "E1", "P3")
        } shouldBe boom
        produced shouldBe null
    }
}
