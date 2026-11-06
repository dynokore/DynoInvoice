package com.dynokore.gst.tenancy.application.certification

import com.dynokore.gst.tenancy.application.installation.InstallationAudit
import com.dynokore.gst.tenancy.application.installation.InstallationFact
import com.dynokore.gst.tenancy.application.installation.InstallationFactRepository
import com.dynokore.gst.tenancy.application.installation.InstallationStep
import com.dynokore.gst.tenancy.application.installation.InstallationStepStore
import com.dynokore.gst.tenancy.application.installation.RecordInstallationAction
import com.dynokore.gst.tenancy.application.installation.StoredInstallationStep
import com.dynokore.gst.tenancy.domain.CertificationOutcome
import com.dynokore.gst.tenancy.domain.CertificationStatus
import com.dynokore.gst.tenancy.domain.StaleTrigger
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

private class Ctx :
    TenantContext,
    PlatformTenantBinding {
    var tenant: TenantId = TenantId(UUID(0, 1))
    val bound = mutableListOf<TenantId>()

    override fun current(): TenantId = tenant

    override fun currentActor(): String = "actor"

    override fun <T> bind(
        tenant: TenantId,
        actor: String,
        block: () -> T,
    ): T {
        val prior = this.tenant
        this.tenant = tenant
        bound += tenant
        try {
            return block()
        } finally {
            this.tenant = prior
        }
    }

    override fun <T> bindPlatform(
        actor: String,
        block: () -> T,
    ): T = block()
}

private object Direct : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()
}

/** Rows per tenant: (tenant, entityRef) -> records, status, last trigger. */
private class Store(
    private val ctx: Ctx,
) : CertificationStore,
    CertifiedEntityDirectory,
    CertifiableEntities {
    val records = mutableMapOf<Pair<TenantId, String>, MutableList<CertificationRecord>>()
    val statuses = mutableMapOf<Pair<TenantId, String>, CertificationStatus>()
    val triggers = mutableMapOf<Pair<TenantId, String>, StaleTrigger?>()
    val statusWrites = mutableListOf<Pair<String, CertificationStatus>>()
    val known = mutableSetOf<Pair<TenantId, String>>()

    private fun key(ref: String) = ctx.tenant to ref

    override fun append(record: CertificationRecord) {
        records.getOrPut(key(record.entityRef)) { mutableListOf() }.add(record)
        if (record.outcome == CertificationOutcome.PASSED && key(record.entityRef) !in statuses) {
            statuses[key(record.entityRef)] = CertificationStatus.CURRENT
        }
    }

    override fun latest(entityRef: String) = records[key(entityRef)]?.lastOrNull()

    override fun hasPassed(entityRef: String) =
        records[key(entityRef)].orEmpty().any {
            it.outcome ==
                CertificationOutcome.PASSED
        }

    override fun status(entityRef: String) = if (hasPassed(entityRef)) statuses[key(entityRef)] else null

    override fun setStatus(
        entityRef: String,
        status: CertificationStatus,
        trigger: StaleTrigger?,
    ) {
        statuses[key(entityRef)] = status
        triggers[key(entityRef)] = trigger
        statusWrites += entityRef to status
    }

    override fun certifiedEntities() =
        records
            .filter { (_, list) -> list.any { it.outcome == CertificationOutcome.PASSED } }
            .keys
            .map { CertifiedEntity(it.first, it.second) }

    override fun exists(entityRef: String) = key(entityRef) in known
}

private class Events : CertificationStatusPublisher {
    val published = mutableListOf<Triple<String, CertificationStatus, StaleTrigger?>>()

    override fun publish(
        entityRef: String,
        status: CertificationStatus,
        trigger: StaleTrigger?,
    ) {
        published += Triple(entityRef, status, trigger)
    }
}

class CertificationTest {
    private val ctx = Ctx()
    private val store = Store(ctx)
    private val events = Events()
    private val held = mutableSetOf<Triple<String, String, String>>()
    private val now = Instant.parse("2026-11-06T00:00:00Z")
    private val mark = MarkCertificationStale(Direct, store, events)
    private val certify =
        CertifySandbox(
            Direct,
            store,
            store,
            { person, entity, action -> Triple(person, entity, action) in held },
            events,
        ) { now }

    private fun entity(ref: String) = store.known.add(ctx.tenant to ref)

    private fun allow(
        person: String,
        entity: String,
    ) = held.add(Triple(person, entity, "sandbox-certification.record"))

    private fun pass(
        entity: String,
        suite: String = "s1",
    ) = certify.handle("alice", entity, CertificationOutcome.PASSED, suite, null)

    private fun setUp(vararg refs: String) {
        refs.forEach {
            entity(it)
            allow("alice", it)
        }
    }

    @Test
    fun `INV-AC-1148 a new train marks a passed certification stale and publishes once`() {
        setUp("e1")
        pass("e1")
        val changed = train("2026.10", "2026.11", listOf("e1"))
        assertEquals(CertificationStatus.STALE, store.status("e1"))
        assertEquals(listOf(Triple("e1", CertificationStatus.STALE, StaleTrigger.NEW_TRAIN)), events.published)
        assertEquals(1, changed)
    }

    @Test
    fun `INV-AC-1148 a record keeps outcome suiteVersion and failedScenarios equal to the request`() {
        setUp("e1")
        certify.handle("alice", "e1", CertificationOutcome.FAILED, "s9", listOf("a", "b"))
        val latest = store.latest("e1")!!
        assertEquals(CertificationOutcome.FAILED, latest.outcome)
        assertEquals("s9", latest.suiteVersion)
        assertEquals(listOf("a", "b"), latest.failedScenarios)
    }

    @Test
    fun `a repeat with the same suiteVersion adds one more record and changes no earlier record`() {
        setUp("e1")
        pass("e1")
        val first =
            store.records.values
                .single()
                .toList()
        pass("e1")
        val all = store.records.values.single()
        assertEquals(2, all.size)
        assertEquals(first, all.take(1))
    }

    @Test
    fun `FAILED with failedScenarios absent is rejected and stores nothing`() =
        rejected(CertificationOutcome.FAILED, null)

    @Test
    fun `FAILED with an empty list is rejected`() = rejected(CertificationOutcome.FAILED, emptyList())

    @Test
    fun `PASSED with one failed scenario is rejected`() = rejected(CertificationOutcome.PASSED, listOf("a"))

    @Test
    fun `FAILED with one item of 129 characters is rejected`() =
        rejected(CertificationOutcome.FAILED, listOf("x".repeat(129)))

    private fun rejected(
        outcome: CertificationOutcome,
        scenarios: List<String>?,
    ) {
        setUp("e1")
        val result = certify.handle("alice", "e1", outcome, "s1", scenarios)
        assertTrue(result is CertifyOutcome.Invalid, result.toString())
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `FAILED with the items a and b is stored equal`() {
        setUp("e1")
        val r = certify.handle("alice", "e1", CertificationOutcome.FAILED, "s1", listOf("a", "b"))
        assertEquals(listOf("a", "b"), (r as CertifyOutcome.Recorded).record.failedScenarios)
    }

    @Test
    fun `each of the five trigger kinds marks a passed certification stale`() {
        StaleTrigger.entries.forEach { trigger ->
            val ref = "e-${trigger.name}"
            setUp(ref)
            pass(ref)
            assertEquals(MarkOutcome.Marked, mark.mark(ref, trigger))
            assertEquals(CertificationStatus.STALE, store.status(ref))
            assertEquals(trigger, store.triggers[ctx.tenant to ref])
        }
        assertEquals(5, events.published.size)
    }

    @Test
    fun `a second identical mark leaves the state stale and writes and publishes nothing`() {
        setUp("e1")
        pass("e1")
        mark.mark("e1", StaleTrigger.NEW_PACK)
        val writes = store.statusWrites.size
        assertEquals(MarkOutcome.AlreadyStale, mark.mark("e1", StaleTrigger.NEW_PACK))
        assertEquals(CertificationStatus.STALE, store.status("e1"))
        assertEquals(writes, store.statusWrites.size)
        assertEquals(1, events.published.size)
    }

    @Test
    fun `the trigger kind NEW_TRAINS is rejected and nothing is marked`() {
        setUp("e1")
        pass("e1")
        assertEquals(MarkOutcome.UnknownTrigger("NEW_TRAINS"), mark.mark("e1", "NEW_TRAINS"))
        assertEquals(CertificationStatus.CURRENT, store.status("e1"))
        assertTrue(events.published.isEmpty())
    }

    @Test
    fun `an entity never certified marked by NEW_PACK stays without a mark`() {
        setUp("e1")
        assertEquals(MarkOutcome.NoCertification, mark.mark("e1", StaleTrigger.NEW_PACK))
        assertNull(store.status("e1"))
        assertTrue(store.statusWrites.isEmpty())
        assertTrue(events.published.isEmpty())
    }

    @Test
    fun `an entity with only a FAILED record gets no mark`() {
        setUp("e1")
        certify.handle("alice", "e1", CertificationOutcome.FAILED, "s1", listOf("a"))
        assertEquals(MarkOutcome.NoCertification, mark.mark("e1", StaleTrigger.NEW_PACK))
    }

    @Test
    fun `a caller holding only entity-config manage is denied and no record is stored`() {
        entity("e1")
        held += Triple("bob", "e1", "entity-config.manage")
        assertEquals(CertifyOutcome.Forbidden, certify.handle("bob", "e1", CertificationOutcome.PASSED, "s1", null))
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `a caller named with the role integration engineer but without the key is denied`() {
        entity("e1")
        assertEquals(
            CertifyOutcome.Forbidden,
            certify.handle("integration.engineer", "e1", CertificationOutcome.PASSED, "s1", null),
        )
        assertTrue(store.records.isEmpty())
    }

    @Test
    fun `a failing authority counts as not held`() {
        entity("e1")
        val broken =
            CertifySandbox(Direct, store, store, { _, _, _ -> error("replica down") }, events) { now }
        assertEquals(CertifyOutcome.Forbidden, broken.handle("alice", "e1", CertificationOutcome.PASSED, "s1", null))
    }

    @Test
    fun `a caller holding the key is accepted`() {
        setUp("e1")
        assertTrue(pass("e1") is CertifyOutcome.Recorded)
    }

    @Test
    fun `a STALE to CURRENT transition by a new PASSED record publishes one CURRENT event`() {
        setUp("e1")
        pass("e1")
        mark.mark("e1", StaleTrigger.NEW_PACK)
        events.published.clear()
        pass("e1", "s2")
        assertEquals(CertificationStatus.CURRENT, store.status("e1"))
        assertEquals(listOf(Triple("e1", CertificationStatus.CURRENT, null)), events.published)
    }

    @Test
    fun `a FAILED record leaves the status stale and publishes nothing`() {
        setUp("e1")
        pass("e1")
        mark.mark("e1", StaleTrigger.NEW_PACK)
        events.published.clear()
        certify.handle("alice", "e1", CertificationOutcome.FAILED, "s2", listOf("a"))
        assertEquals(CertificationStatus.STALE, store.status("e1"))
        assertTrue(events.published.isEmpty())
    }

    @Test
    fun `a first PASSED record publishes nothing`() {
        setUp("e1")
        pass("e1")
        assertTrue(events.published.isEmpty())
    }

    // ---- Subject D: the train hook ----

    private class Facts : InstallationFactRepository {
        val map = mutableMapOf<String, InstallationFact>()

        override fun put(fact: InstallationFact) {
            map[fact.key] = fact
        }

        override fun putIfNotOlder(fact: InstallationFact) {
            val stored = map[fact.key]
            if (stored == null || !stored.recordedAt.isAfter(fact.recordedAt)) map[fact.key] = fact
        }

        override fun find(key: String) = map[key]

        override fun all() = map.values.toList()
    }

    private val facts = Facts()
    private var stepNo = 0L

    private fun nextStep(): Long = ++stepNo

    private val steps =
        object : InstallationStepStore {
            override fun find(
                runId: UUID,
                step: Int,
            ): StoredInstallationStep? = null

            override fun append(
                step: InstallationStep,
                fingerprint: String,
                auditEntryId: UUID,
                recordedAt: Instant,
            ) = Unit
        }

    private val action =
        RecordInstallationAction(
            ctx,
            Direct,
            steps,
            facts,
            object : InstallationAudit {
                override fun record(
                    step: InstallationStep,
                    priorStateJson: String?,
                ) = UUID.randomUUID()
            },
            { },
            { now },
            "svc:tenancy",
            { MarkAllStaleForNewTrain(store, ctx, Direct, mark, "svc:tenancy").run() },
        )

    private fun store(
        facts: Map<String, String>,
        at: Instant = now.plusSeconds(nextStep()),
    ) = action.handle(
        InstallationStep(UUID.randomUUID(), 1, "c", "s", "OK", at, "r", "op", null, null, null, facts),
    )

    /** Seeds [from], certifies the [entities], stores [to]; returns the number of STALE marks written. */
    private fun train(
        from: String,
        to: String,
        entities: List<String>,
    ): Int {
        entities.forEach {
            setUp(it)
            pass(it)
        }
        store(mapOf("releaseTrain" to from))
        events.published.clear()
        store(mapOf("releaseTrain" to to))
        return events.published.size
    }

    @Test
    fun `releaseTrain changed from 2026-10 to 2026-11 marks the two certified entities and not the third`() {
        setUp("e1", "e2", "e3")
        pass("e1")
        pass("e2")
        store(mapOf("releaseTrain" to "2026.10"))
        events.published.clear()
        store(mapOf("releaseTrain" to "2026.11"))
        assertEquals(
            setOf("e1", "e2"),
            events.published.map { it.first }.toSet(),
        )
        assertEquals(2, events.published.size)
        assertTrue(events.published.all { it.third == StaleTrigger.NEW_TRAIN })
        assertNull(store.status("e3"))
    }

    @Test
    fun `an entity of another tenant is marked under that tenant only`() {
        val other = TenantId(UUID(0, 2))
        val home = ctx.tenant
        setUp("e1")
        pass("e1")
        ctx.tenant = other
        setUp("e2")
        pass("e2")
        ctx.tenant = home
        store(mapOf("releaseTrain" to "2026.10"))
        ctx.bound.clear()
        store(mapOf("releaseTrain" to "2026.11"))
        assertEquals(setOf(home, other), ctx.bound.toSet())
        assertEquals(CertificationStatus.STALE, store.statuses[other to "e2"])
        assertEquals(CertificationStatus.STALE, store.statuses[home to "e1"])
        assertEquals(home, ctx.tenant)
    }

    private fun zeroMarks(
        before: Map<String, String>,
        after: Map<String, String>,
    ) {
        setUp("e1")
        pass("e1")
        store(before)
        events.published.clear()
        store(after)
        assertTrue(events.published.isEmpty())
        assertEquals(CertificationStatus.CURRENT, store.status("e1"))
    }

    @Test
    fun `the fact key releaseTrains changed marks nothing`() =
        zeroMarks(mapOf("releaseTrains" to "a"), mapOf("releaseTrains" to "b"))

    @Test
    fun `supportedWindow changed marks nothing`() =
        zeroMarks(mapOf("supportedWindow" to "a"), mapOf("supportedWindow" to "b"))

    @Test
    fun `releaseTrain stored equal again marks nothing`() =
        zeroMarks(mapOf("releaseTrain" to "2026.10"), mapOf("releaseTrain" to "2026.10"))

    @Test
    fun `releaseTrain stored first time marks nothing`() = zeroMarks(emptyMap(), mapOf("releaseTrain" to "2026.10"))

    @Test
    fun `a step older than the stored fact marks nothing`() {
        setUp("e1")
        pass("e1")
        store(mapOf("releaseTrain" to "2026.11"), now.plusSeconds(1000))
        events.published.clear()
        store(mapOf("releaseTrain" to "2026.10"), now.plusSeconds(10))
        assertEquals("2026.11", facts.find("releaseTrain")?.value)
        assertTrue(events.published.isEmpty())
        assertFalse(store.status("e1") == CertificationStatus.STALE)
    }
}
