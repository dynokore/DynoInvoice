package com.dynokore.gst.evidence.application.artefact

import com.dynokore.platform.serviceruntime.events.EvidenceArtefact
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactType
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ArtefactRecorderTest {
    private class Memory : ArtefactStore {
        val keys = linkedSetOf<List<Any?>>()
        val entityRefs = mutableListOf<String?>()

        override fun insert(
            tenant: TenantId,
            entityRef: String?,
            artefact: EvidenceArtefact,
            eventId: String,
            recordedAt: Instant,
        ): Boolean {
            entityRefs += entityRef
            return keys.add(listOf(tenant, artefact.type.value, artefact.reference, artefact.hash))
        }
    }

    private val tenant = TenantId(UUID.randomUUID())
    private val at = Instant.parse("2026-01-15T00:00:00Z")
    private val hash = "a".repeat(64)
    private val memory = Memory()
    private val recorder = ArtefactRecorder(memory)

    @Test
    fun `INV-AC-20306 a repeat of the same artefact reports it was already held`() {
        val artefact = EvidenceArtefact(EvidenceArtefactType.SAMPLE_CHECK, "r", hash)
        recorder.record(tenant, "E", artefact, "e1", at) shouldBe true
        recorder.record(tenant, "E", artefact, "e2", at) shouldBe false
        memory.keys.size shouldBe 1
    }

    @Test
    fun `INV-AC-20306 an unknown type reaches the store unchanged and a null entity is passed on`() {
        val artefact = EvidenceArtefact(EvidenceArtefactType("NEW_KIND"), "r", hash)
        artefact.type.isKnown shouldBe false
        recorder.record(tenant, null, artefact, "e1", at) shouldBe true
        memory.keys.single()[1] shouldBe "NEW_KIND"
        memory.entityRefs shouldBe listOf(null)
    }
}
