package com.dynokore.gst.ingestion.application.triggers

import com.dynokore.gst.ingestion.domain.triggers.CoverageRecord
import com.dynokore.gst.ingestion.domain.triggers.TriggerClass
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID

class RecordTriggerCoverageTest {
    private val tenant = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val entity = "entity-1"
    private val cls = TriggerClass.JOURNAL_ONLY_SUPPLY
    private val day = LocalDate.of(2027, 1, 31)

    private class Log {
        val calls = mutableListOf<String>()
    }

    private class FakeStore(
        private val log: Log,
        private val failSave: Boolean = false,
    ) : TriggerCoverageStore {
        val rows = mutableMapOf<TriggerClass, CoverageRecord>()
        val actors = mutableListOf<UUID>()

        override fun find(
            tenantId: UUID,
            entityRef: String,
            triggerClass: TriggerClass,
        ): CoverageRecord? = rows[triggerClass]

        override fun save(
            tenantId: UUID,
            entityRef: String,
            triggerClass: TriggerClass,
            record: CoverageRecord,
            actorUserId: UUID,
        ) {
            log.calls += "save"
            if (failSave) error("store down")
            rows[triggerClass] = record
            actors += actorUserId
        }
    }

    private class FakeEvents(
        private val log: Log,
        private val fail: Boolean = false,
    ) : TriggerCoverageEvents {
        val published = mutableListOf<TriggerCoverageChanged>()

        override fun publish(event: TriggerCoverageChanged) {
            log.calls += "publish"
            if (fail) error("outbox down")
            published += event
        }
    }

    private class FakePermission(
        private val log: Log,
        private val allow: Boolean = true,
    ) : CoverageWritePermission {
        val asked = mutableListOf<Triple<UUID, CoverageOperation, String>>()

        override fun permits(
            actorUserId: UUID,
            operation: CoverageOperation,
            entityRef: String,
        ): Boolean {
            log.calls += "permit"
            asked += Triple(actorUserId, operation, entityRef)
            return allow
        }
    }

    private class Rig(
        allow: Boolean = true,
        failSave: Boolean = false,
        failPublish: Boolean = false,
    ) {
        val log = Log()
        val store = FakeStore(log, failSave)
        val events = FakeEvents(log, failPublish)
        val permission = FakePermission(log, allow)
        val map = MapTriggerClass(store, events, permission)
        val waive = WaiveTriggerClass(store, events, permission)
    }

    private fun mapCmd(ref: String = "erp-ar") = MapTriggerClassCommand(tenant, entity, actor, cls, ref)

    private fun waiveCmd(date: LocalDate? = day) =
        WaiveTriggerClassCommand(tenant, entity, actor, cls, WaiverMode.REVIEW_WAIVER, reviewDate = date)

    private fun elsewhereCmd(evidence: String = "ev-1") =
        WaiveTriggerClassCommand(
            tenant,
            entity,
            actor,
            cls,
            WaiverMode.COVERED_BY_OTHER_SOLUTION,
            coveringSolution = "other-erp",
            evidenceReference = evidence,
        )

    @Test
    fun `INV-AC-20935 mapping stores once with the acting user and publishes once`() {
        val r = Rig()
        r.map.execute(mapCmd()) shouldBe CoverageOutcome.Stored(CoverageRecord.Mapped("erp-ar"))
        r.store.rows[cls] shouldBe CoverageRecord.Mapped("erp-ar")
        r.store.actors shouldBe listOf(actor)
        r.events.published shouldBe
            listOf(TriggerCoverageChanged(tenant, entity, cls, CoverageRecord.Mapped("erp-ar"), actor))
    }

    @Test
    fun `INV-AC-20935 waiver stores once with the acting user and publishes once`() {
        val r = Rig()
        r.waive.execute(waiveCmd()) shouldBe CoverageOutcome.Stored(CoverageRecord.Waived(day))
        r.store.actors shouldBe listOf(actor)
        r.events.published.size shouldBe 1
    }

    @Test
    fun `INV-AC-20935 covered elsewhere stores once and publishes once`() {
        val r = Rig()
        r.waive.execute(elsewhereCmd()) shouldBe
            CoverageOutcome.Stored(CoverageRecord.CoveredElsewhere("other-erp", "ev-1"))
        r.events.published.size shouldBe 1
    }

    @Test
    fun `INV-AC-20935 repeat returns the stored record and stores and publishes nothing`() {
        val r = Rig()
        r.map.execute(mapCmd())
        r.map.execute(mapCmd()) shouldBe CoverageOutcome.Unchanged(CoverageRecord.Mapped("erp-ar"))
        r.log.calls.count { it == "save" } shouldBe 1
        r.events.published.size shouldBe 1
        val w = Rig()
        w.waive.execute(waiveCmd())
        w.waive.execute(waiveCmd()) shouldBe CoverageOutcome.Unchanged(CoverageRecord.Waived(day))
        w.events.published.size shouldBe 1
    }

    @Test
    fun `INV-AC-20935 different content for a recorded class is a precondition failure and changes nothing`() {
        val r = Rig()
        r.map.execute(mapCmd())
        r.map.execute(mapCmd("other")) shouldBe CoverageOutcome.PreconditionFailed(CoverageRecord.Mapped("erp-ar"))
        r.waive.execute(waiveCmd()) shouldBe CoverageOutcome.PreconditionFailed(CoverageRecord.Mapped("erp-ar"))
        r.store.rows[cls] shouldBe CoverageRecord.Mapped("erp-ar")
        r.log.calls.count { it == "save" } shouldBe 1
        r.events.published.size shouldBe 1
    }

    @Test
    fun `INV-AC-20935 refused permission stores and publishes nothing`() {
        val r = Rig(allow = false)
        r.map.execute(mapCmd()) shouldBe CoverageOutcome.Forbidden
        r.waive.execute(waiveCmd()) shouldBe CoverageOutcome.Forbidden
        r.waive.execute(elsewhereCmd()) shouldBe CoverageOutcome.Forbidden
        r.log.calls.none { it == "save" || it == "publish" } shouldBe true
        r.store.rows.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-20935 permission is asked before anything is stored`() {
        val r = Rig()
        r.map.execute(mapCmd())
        r.log.calls shouldBe listOf("permit", "save", "publish")
    }

    @Test
    fun `INV-AC-20935 permission port is asked MAP for a mapping with the acting user id`() {
        val r = Rig()
        r.map.execute(mapCmd())
        r.permission.asked shouldBe listOf(Triple(actor, CoverageOperation.MAP, entity))
    }

    @Test
    fun `INV-AC-20935 permission port is asked WAIVE for a review waiver`() {
        val r = Rig()
        r.waive.execute(waiveCmd())
        r.permission.asked shouldBe listOf(Triple(actor, CoverageOperation.WAIVE, entity))
    }

    @Test
    fun `INV-AC-20935 permission port is asked COVER_ELSEWHERE for a covered by other solution waiver`() {
        val r = Rig()
        r.waive.execute(elsewhereCmd())
        r.permission.asked shouldBe listOf(Triple(actor, CoverageOperation.COVER_ELSEWHERE, entity))
    }

    @Test
    fun `INV-AC-20935 operation names are exactly MAP WAIVE COVER_ELSEWHERE`() {
        CoverageOperation.entries.map { it.name } shouldBe listOf("MAP", "WAIVE", "COVER_ELSEWHERE")
    }

    @Test
    fun `INV-AC-20935 failing store fails the whole call and publishes nothing`() {
        val r = Rig(failSave = true)
        assertThrows<IllegalStateException> { r.map.execute(mapCmd()) }
        r.events.published.isEmpty() shouldBe true
        r.store.rows.isEmpty() shouldBe true
    }

    @Test
    fun `INV-AC-20935 failing events port fails the whole call`() {
        val r = Rig(failPublish = true)
        assertThrows<IllegalStateException> { r.map.execute(mapCmd()) }
        assertThrows<IllegalStateException> { Rig(failPublish = true).waive.execute(waiveCmd()) }
    }

    @Test
    fun `INV-AC-20935 invalid content is refused before the store is touched`() {
        val r = Rig()
        assertThrows<IllegalArgumentException> { r.waive.execute(waiveCmd(null)) }
        r.log.calls.none { it == "save" || it == "publish" } shouldBe true
    }

    @Test
    fun `INV-AC-20935 conformance fixture assigns a lambda to each port and calls each use case`() {
        val store =
            object : TriggerCoverageStore {
                override fun find(
                    tenantId: UUID,
                    entityRef: String,
                    triggerClass: TriggerClass,
                ): CoverageRecord? = null

                override fun save(
                    tenantId: UUID,
                    entityRef: String,
                    triggerClass: TriggerClass,
                    record: CoverageRecord,
                    actorUserId: UUID,
                ) = Unit
            }
        val events = TriggerCoverageEvents { _ -> }
        val permission = CoverageWritePermission { _, _, _ -> true }
        MapTriggerClass(store, events, permission).execute(mapCmd()).shouldBe(
            CoverageOutcome.Stored(CoverageRecord.Mapped("erp-ar")),
        )
        WaiveTriggerClass(store, events, permission).execute(waiveCmd()).shouldBe(
            CoverageOutcome.Stored(CoverageRecord.Waived(day)),
        )
    }
}
