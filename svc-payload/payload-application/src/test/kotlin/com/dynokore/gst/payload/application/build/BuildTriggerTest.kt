package com.dynokore.gst.payload.application.build

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class BuildTriggerTest {
    private val d1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val d2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
    private val d3 = UUID.fromString("00000000-0000-0000-0000-0000000000d3")

    private class Fakes :
        CanonicalInputs,
        BuildInputs,
        TypeDecisions,
        BuildJobs {
        private val inputs = mutableSetOf<Pair<UUID, Int>>()
        val decisions = mutableSetOf<Pair<UUID, Int>>()
        val jobKeys = linkedSetOf<String>()
        val enqueueCalls = mutableListOf<String>()

        override fun store(
            docUuid: UUID,
            version: Int,
            canonicalDocument: String,
        ): Boolean = inputs.add(docUuid to version)

        override fun exists(
            docUuid: UUID,
            version: Int,
        ): Boolean = (docUuid to version) in inputs

        fun decide(
            docUuid: UUID,
            version: Int,
        ) = decisions.add(docUuid to version)

        override fun enqueueOnce(
            docUuid: UUID,
            version: Int,
        ): Boolean {
            val key = "build:$docUuid:$version"
            enqueueCalls += key
            return jobKeys.add(key)
        }
    }

    private val fakes = Fakes()
    private val trigger =
        BuildTrigger(
            inputs = { d, v -> fakes.exists(d, v) },
            decisions = { d, v -> (d to v) in fakes.decisions },
            jobs = fakes,
        )

    @Test
    fun `INV-AC-22932 input first then decision enqueues once`() {
        fakes.store(d1, 1, "{}")
        trigger.onInputStored(d1, 1) shouldBe false
        fakes.jobKeys shouldBe emptySet()
        fakes.decide(d1, 1)
        trigger.onDecisionStored(d1, 1) shouldBe true
        fakes.jobKeys shouldBe setOf("build:$d1:1")
    }

    @Test
    fun `INV-AC-22932 decision first then input enqueues once`() {
        fakes.decide(d2, 2)
        trigger.onDecisionStored(d2, 2) shouldBe false
        fakes.jobKeys shouldBe emptySet()
        fakes.store(d2, 2, "{}")
        trigger.onInputStored(d2, 2) shouldBe true
        fakes.jobKeys shouldBe setOf("build:$d2:2")
        fakes.enqueueCalls.size shouldBe 1
    }

    @Test
    fun `INV-AC-22932 input only enqueues nothing`() {
        fakes.store(d3, 1, "{}")
        trigger.onInputStored(d3, 1) shouldBe false
        fakes.jobKeys shouldBe emptySet()
        fakes.enqueueCalls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22932 second store enqueues nothing`() {
        fakes.store(d1, 1, "{}")
        fakes.decide(d1, 1)
        trigger.onInputStored(d1, 1) shouldBe true
        fakes.store(d1, 1, "{}") shouldBe false
        trigger.onInputStored(d1, 1) shouldBe false
        fakes.jobKeys shouldBe setOf("build:$d1:1")
    }
}
