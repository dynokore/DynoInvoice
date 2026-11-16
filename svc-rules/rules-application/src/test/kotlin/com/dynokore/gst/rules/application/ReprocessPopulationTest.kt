package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.reprocessing.PopulationItem
import com.dynokore.gst.rules.domain.reprocessing.ResubmissionEntry
import com.dynokore.platform.outboxandqueue.Lane
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReprocessPopulationTest {
    private val type = "GST"
    private val version = "2026.3-rollback"
    private val key = "regulatory-pack.reprocess"
    private val stored = DocumentOutcome("SR")
    private val changedVerdict = DocumentOutcome("ZR")

    private fun item(
        n: Int,
        acknowledged: Boolean = true,
    ) = PopulationItem("00000000-0000-4000-8000-00000000000$n", "2026.3", acknowledged, stored)

    private class Runs : ReprocessingRunStore {
        var run: ReprocessingRun? = null
        var rows = 0
        private var entities = emptySet<String>()
        private val finished = mutableSetOf<String>()
        private val received = mutableSetOf<String>()
        private val done = mutableSetOf<String>()

        override fun find(
            packType: String,
            version: String,
        ) = run

        override fun begin(
            packType: String,
            version: String,
            entities: List<String>,
        ): ReprocessingRun {
            rows++
            this.entities = entities.toSet()
            return ReprocessingRun(packType, version, ReprocessingRunState.RUNNING, false, 0, 0).also { run = it }
        }

        override fun pageArrived(
            packType: String,
            version: String,
            entityRef: String,
            last: Boolean,
            docUuids: List<String>,
        ): ReprocessingRun {
            received += docUuids
            if (last) finished += entityRef
            return refresh(finished.containsAll(entities))
        }

        override fun processed(
            packType: String,
            version: String,
            docUuid: String,
        ): ReprocessingRun {
            done += docUuid
            return refresh(run!!.lastPageArrived)
        }

        private fun refresh(allLast: Boolean) =
            run!!
                .copy(lastPageArrived = allLast, received = received.size, processed = done.size)
                .also { run = it }

        override fun complete(
            packType: String,
            version: String,
        ) = run!!.copy(state = ReprocessingRunState.COMPLETE).also { run = it }
    }

    private class Requests : PopulationRequestPort {
        val calls = mutableListOf<String>()

        override fun request(
            packType: String,
            version: String,
            entityRef: String,
        ) {
            calls += entityRef
        }
    }

    /** Fails the case on any lane but REPROCESSING. */
    private class Queue : ReprocessingQueuePort {
        val items = mutableListOf<ReprocessingWorkItem>()

        override fun enqueue(
            lane: Lane,
            partitionKey: String,
            work: ReprocessingWorkItem,
        ) {
            check(lane == Lane.REPROCESSING) { "lane $lane is not REPROCESSING" }
            items += work
        }
    }

    /** Idempotent by (pack type, version, Doc UUID), as the port KDoc states; keeps every call for the case. */
    private class Resubmissions : ResubmissionStore {
        val calls = mutableListOf<Pair<Triple<String, String, String>, ResubmissionEntry>>()
        private val kept = linkedMapOf<Triple<String, String, String>, ResubmissionEntry>()
        val entries: List<ResubmissionEntry> get() = kept.values.toList()

        override fun add(
            packType: String,
            version: String,
            entry: ResubmissionEntry,
        ) {
            val key = Triple(packType, version, entry.docUuid)
            calls += key to entry
            kept[key] = entry
        }
    }

    private class Packs(
        var armed: Boolean = true,
        val newVerdict: DocumentOutcome,
    ) : SupersedingPackPort {
        override fun isArmed(
            packType: String,
            version: String,
        ) = armed

        override fun verdict(
            packType: String,
            version: String,
            item: PopulationItem,
        ) = newVerdict
    }

    private class Env(
        newVerdict: DocumentOutcome,
        allow: Boolean = true,
        armed: Boolean = true,
    ) {
        val runs = Runs()
        val requests = Requests()
        val queue = Queue()
        val resubmissions = Resubmissions()
        val calls = mutableListOf<Pair<String, String>>()
        val uc =
            ReprocessPopulation(
                { u, k ->
                    calls += u to k
                    allow
                },
                runs,
                requests,
                queue,
                resubmissions,
                Packs(armed, newVerdict),
            )
    }

    @Test
    fun `INV-AC-24013 the first call writes one RUNNING row and requests once per entity`() {
        val e = Env(changedVerdict)
        val r = e.uc.start(type, version, listOf("E1", "E2"), "user-1", key)
        (r as ReprocessPopulationResult.Started).run.state shouldBe ReprocessingRunState.RUNNING
        e.runs.rows shouldBe 1
        e.requests.calls shouldBe listOf("E1", "E2")
        e.calls shouldBe listOf("user-1" to key)
    }

    @Test
    fun `INV-AC-24013 a repeat resumes the same run and sends no second request`() {
        val e = Env(changedVerdict)
        e.uc.start(type, version, listOf("E1", "E2"), "user-1", key)
        val again = e.uc.start(type, version, listOf("E1", "E2"), "user-1", key)
        (again is ReprocessPopulationResult.Resumed) shouldBe true
        e.runs.rows shouldBe 1
        e.requests.calls shouldBe listOf("E1", "E2")
    }

    @Test
    fun `INV-AC-24013 a precondition failure and a denied permission write nothing`() {
        val unarmed = Env(changedVerdict, armed = false)
        unarmed.uc.start(type, version, listOf("E1"), "u", key) shouldBe
            ReprocessPopulationResult.NoSupersedingPackArmed
        unarmed.runs.run shouldBe null
        unarmed.requests.calls shouldBe emptyList()
        val denied = Env(changedVerdict, allow = false)
        denied.uc.start(type, version, listOf("E1"), "u", key) shouldBe ReprocessPopulationResult.Denied
        denied.runs.run shouldBe null
        denied.requests.calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24013 items are enqueued only with lane REPROCESSING`() {
        val e = Env(changedVerdict)
        e.uc.start(type, version, listOf("E1"), "u", key)
        e.uc.pageArrived(type, version, PopulationPage("E1", true, listOf(item(1), item(2))))
        e.queue.items.map { it.item.docUuid } shouldBe listOf(item(1).docUuid, item(2).docUuid)
    }

    @Test
    fun `INV-AC-24013 a resubmission entry exists exactly for acknowledged and reclassified items`() {
        val e = Env(changedVerdict)
        e.uc.start(type, version, listOf("E1"), "u", key)
        val items = listOf(item(1), item(2, acknowledged = false))
        e.uc.pageArrived(type, version, PopulationPage("E1", true, items))
        items.forEach { e.uc.process(type, version, it) }
        e.resubmissions.entries shouldBe listOf(ResubmissionEntry(item(1).docUuid, "2026.3"))
    }

    @Test
    fun `INV-AC-24013 an unchanged item gets no entry`() {
        val e = Env(stored)
        e.uc.start(type, version, listOf("E1"), "u", key)
        e.uc.pageArrived(type, version, PopulationPage("E1", true, listOf(item(1))))
        e.uc.process(type, version, item(1))
        e.resubmissions.entries shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24013 the run is COMPLETE only after the last page and every item`() {
        val e = Env(changedVerdict)
        e.uc.start(type, version, listOf("E1", "E2"), "u", key)
        e.uc.pageArrived(type, version, PopulationPage("E1", true, listOf(item(1)))).state shouldBe
            ReprocessingRunState.RUNNING
        // item 1 is processed but the last page of E2 has not arrived
        e.uc.process(type, version, item(1)).state shouldBe ReprocessingRunState.RUNNING
        // the last page arrives with an item not yet processed
        e.uc.pageArrived(type, version, PopulationPage("E2", true, listOf(item(2)))).state shouldBe
            ReprocessingRunState.RUNNING
        e.uc.process(type, version, item(2)).state shouldBe ReprocessingRunState.COMPLETE
    }

    @Test
    fun `INV-AC-24013 a redelivered item passes an equal key and entry and the store keeps one`() {
        val e = Env(changedVerdict)
        e.uc.start(type, version, listOf("E1"), "u", key)
        e.uc.pageArrived(type, version, PopulationPage("E1", false, listOf(item(1))))
        e.uc.process(type, version, item(1))
        e.uc.process(type, version, item(1))
        e.resubmissions.calls.size shouldBe 2
        e.resubmissions.calls[0] shouldBe e.resubmissions.calls[1]
        e.resubmissions.calls[0].first shouldBe Triple(type, version, item(1).docUuid)
        e.resubmissions.entries shouldBe listOf(ResubmissionEntry(item(1).docUuid, "2026.3"))
        e.runs.run!!.processed shouldBe 1
        e.uc.pageArrived(type, version, PopulationPage("E1", true, emptyList())).state shouldBe
            ReprocessingRunState.COMPLETE
    }

    @Test
    fun `INV-AC-24013 entities A and B send exactly two requests, for A and for B`() {
        val e = Env(changedVerdict)
        e.uc.start(type, version, listOf("A", "B"), "u", key)
        e.requests.calls shouldBe listOf("A", "B")
    }

    @Test
    fun `INV-AC-24013 a redelivered item is counted once`() {
        val e = Env(changedVerdict)
        e.uc.start(type, version, listOf("E1"), "u", key)
        e.uc.pageArrived(type, version, PopulationPage("E1", false, listOf(item(1))))
        e.uc.process(type, version, item(1)).state shouldBe ReprocessingRunState.RUNNING
        e.uc.process(type, version, item(1)).state shouldBe ReprocessingRunState.RUNNING
        e.uc.pageArrived(type, version, PopulationPage("E1", true, listOf(item(2)))).state shouldBe
            ReprocessingRunState.RUNNING
        e.uc.process(type, version, item(2)).state shouldBe ReprocessingRunState.COMPLETE
    }

    @Test
    fun `INV-AC-24013 the use case has no resubmission call port, only the list store`() {
        ReprocessPopulation::class.java.declaredConstructors
            .single()
            .parameterTypes
            .map { it.simpleName }
            .filter { it.contains("Resubmi", ignoreCase = true) } shouldBe listOf("ResubmissionStore")
        ResubmissionStore::class.java.declaredMethods.map { it.name } shouldBe listOf("add")
    }
}
