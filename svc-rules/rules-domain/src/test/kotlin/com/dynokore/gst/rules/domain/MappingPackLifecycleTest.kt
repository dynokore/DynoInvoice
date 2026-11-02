package com.dynokore.gst.rules.domain

import com.dynokore.gst.rules.domain.pack.MappingCommand
import com.dynokore.gst.rules.domain.pack.MappingPackLifecycle
import com.dynokore.gst.rules.domain.pack.MappingPackLifecycle.Transition
import com.dynokore.gst.rules.domain.pack.PackStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MappingPackLifecycleTest {
    private val allowed =
        mapOf(
            (PackStatus.DRAFT to MappingCommand.SUBMIT) to PackStatus.SUBMITTED,
            (PackStatus.SUBMITTED to MappingCommand.APPROVE) to PackStatus.APPROVED,
            (PackStatus.APPROVED to MappingCommand.ACTIVATE) to PackStatus.ARMED,
        )

    private fun expected(
        status: PackStatus,
        command: MappingCommand,
    ): Transition = allowed[status to command]?.let { Transition.To(it) } ?: Transition.Refused(status, command)

    @Test
    fun `INV-REQ-380 the twelve pairs of the four mapping statuses equal the expected table`() {
        val actual =
            MappingPackLifecycle.MAPPING_STATUSES.flatMap { s ->
                MappingCommand.entries.map { c -> (s to c) to MappingPackLifecycle.next(s, c) }
            }
        actual.size shouldBe 12
        actual.toMap() shouldBe
            MappingPackLifecycle.MAPPING_STATUSES
                .flatMap { s -> MappingCommand.entries.map { c -> (s to c) to expected(s, c) } }
                .toMap()
        actual.count { it.second is Transition.To } shouldBe 3
    }

    @Test
    fun `INV-REQ-380 the twelve pairs of STAGED ACKNOWLEDGED SUPERSEDED and ABORTED are refused naming the status`() {
        for (s in MappingPackLifecycle.OTHER_STATUSES) {
            for (c in MappingCommand.entries) {
                MappingPackLifecycle.next(s, c) shouldBe Transition.Refused(s, c)
            }
        }
        MappingPackLifecycle.OTHER_STATUSES.size * MappingCommand.entries.size shouldBe 12
    }

    @Test
    fun `INV-REQ-380 the two status sets cover all eight values without overlap`() {
        MappingPackLifecycle.MAPPING_STATUSES shouldBe
            setOf(PackStatus.DRAFT, PackStatus.SUBMITTED, PackStatus.APPROVED, PackStatus.ARMED)
        MappingPackLifecycle.OTHER_STATUSES shouldBe
            setOf(PackStatus.STAGED, PackStatus.ACKNOWLEDGED, PackStatus.SUPERSEDED, PackStatus.ABORTED)
        val all = MappingPackLifecycle.MAPPING_STATUSES + MappingPackLifecycle.OTHER_STATUSES
        all shouldBe PackStatus.entries.toSet()
    }

    @Test
    fun `INV-REQ-380 the look-alike APPROVED ACTIVATE passes and SUBMITTED ACTIVATE is refused`() {
        MappingPackLifecycle.next(PackStatus.APPROVED, MappingCommand.ACTIVATE) shouldBe Transition.To(PackStatus.ARMED)
        MappingPackLifecycle.next(PackStatus.SUBMITTED, MappingCommand.ACTIVATE) shouldBe
            Transition.Refused(PackStatus.SUBMITTED, MappingCommand.ACTIVATE)
    }

    @Test
    fun `INV-REQ-380 entries change only in DRAFT and an acceptance is recorded only in DRAFT or SUBMITTED`() {
        PackStatus.entries.filter { MappingPackLifecycle.entriesMayChange(it) } shouldBe listOf(PackStatus.DRAFT)
        PackStatus.entries.filter { MappingPackLifecycle.acceptanceMayBeRecorded(it) } shouldBe
            listOf(PackStatus.DRAFT, PackStatus.SUBMITTED)
    }
}
