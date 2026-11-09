package com.dynokore.gst.tenancy.application.lifecycle

import com.dynokore.gst.tenancy.application.certification.CertificationRecord
import com.dynokore.gst.tenancy.application.certification.CertificationStore
import com.dynokore.gst.tenancy.application.confirmation.SecondConfirmation
import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.ElectionGate
import com.dynokore.gst.tenancy.application.election.ElectionPolicyStore
import com.dynokore.gst.tenancy.application.election.ElectionRecord
import com.dynokore.gst.tenancy.application.election.ElectionStatusQueries
import com.dynokore.gst.tenancy.application.election.EntityFactSource
import com.dynokore.gst.tenancy.application.offboarding.UnavailableInput
import com.dynokore.gst.tenancy.application.offboarding.UnsubmittedDocuments
import com.dynokore.gst.tenancy.application.period.PeriodOutcome
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.status.EntityStatusEntry
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.domain.CertificationOutcome
import com.dynokore.gst.tenancy.domain.CertificationStatus
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.gst.tenancy.domain.LifecycleState
import com.dynokore.gst.tenancy.domain.StaleTrigger
import com.dynokore.gst.tenancy.domain.UnsubmittedOverride
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** goLive, suspendEntity and deregisterEntity over in-memory ports (INV-AC-1064). */
@Suppress("LargeClass", "TooManyFunctions")
class EntityLifecycleTest {
    private val today = LocalDate.of(2026, 11, 9)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val caller = "identity:USR-004"
    private val entity =
        EntityRecord(UUID.randomUUID(), "E1", "G1", "Acme", "COMPANY", null, null, "ACME", "DRAFT", false, 1)

    private var current = entity
    private val outbox = mutableListOf<EntityProfileChange>()
    private val overrides = mutableListOf<UnsubmittedOverride>()
    private val submissions = mutableListOf("SUB-1", "SUB-2")
    private val grants =
        mutableMapOf(
            caller to setOf(ElectionPermissions.CONFIRM),
            "identity:USR-0040" to setOf(ElectionPermissions.CONFIRM),
            "identity:USR-FIN" to setOf("finance.view"),
            "identity:USR-TAX" to setOf(ElectionPermissions.CONFIRM),
        )
    private val authority = ActionAuthority { person, _, action -> action in grants[person].orEmpty() }
    private var coverage = TriggerCoverage { emptyList() }
    private var unsubmitted = UnsubmittedDocuments { _, _ -> 0 }
    private var finalPeriod: FinalPeriod = FinalPeriod { _, _ -> PeriodOutcome.Done(emptyList()) }
    private var publishFailure = false
    private val elected = ElectionKind.entries.toMutableSet()

    private val structure: StructureStore =
        proxy(StructureStore::class.java) { method, args ->
            when (method.name) {
                "entity" -> current.takeIf { it.ref == args[0] }
                "nextStructureVersion" -> current.structureVersion + 1
                "touchEntity" -> Unit
                else -> error("${method.name} is not used")
            }
        }
    private val policies: ElectionPolicyStore =
        proxy(ElectionPolicyStore::class.java) { method, args ->
            check(method.name == "history")
            if (args[1] in elected) {
                listOf(ElectionRecord("V", null, today.minusDays(1), "identity:USR-004"))
            } else {
                emptyList<ElectionRecord>()
            }
        }
    private val facts =
        object : EntityFactSource {
            override fun recordsPurchases(entityRef: String) = FactAnswer.NOT_RECORDED

            override fun hasForeignCurrencyDocuments(entityRef: String) = FactAnswer.NOT_RECORDED

            override fun claimsInputTaxOnPostingBasis(entityRef: String) = FactAnswer.NOT_RECORDED
        }
    private val certifications = Certs()
    private val lifecycleStore =
        EntityLifecycleStore { ref, state, version ->
            current = current.copy(lifecycleState = state.name, structureVersion = version)
            check(ref == current.ref)
        }
    private val statuses =
        object : EntityStatusStore {
            override fun history(entityId: UUID) = emptyList<EntityStatusEntry>()

            override fun insert(
                entityId: UUID,
                entry: EntityStatusEntry,
            ) = Unit
        }
    private val publisher =
        object : EntityProfileChangePublisher {
            override fun publish(change: EntityProfileChange) {
                outbox += change
                if (publishFailure) error("publish failed after the row")
            }
        }

    /** A transaction that restores the state and outbox when the block throws. */
    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                val savedState = current
                val savedOutbox = outbox.toList()
                return try {
                    block()
                } catch (e: IllegalStateException) {
                    current = savedState
                    outbox.clear()
                    outbox += savedOutbox
                    throw e
                }
            }
        }

    private class Certs : CertificationStore {
        val rows = mutableListOf<CertificationRecord>()

        override fun append(record: CertificationRecord) {
            rows += record
        }

        override fun latest(entityRef: String) = rows.lastOrNull()

        override fun hasPassed(entityRef: String) = rows.any { it.outcome == CertificationOutcome.PASSED }

        override fun status(entityRef: String): CertificationStatus? = null

        override fun setStatus(
            entityRef: String,
            status: CertificationStatus,
            trigger: StaleTrigger?,
        ) = Unit
    }

    private fun certify(
        outcome: CertificationOutcome,
        failed: List<String>? = null,
    ) {
        certifications.rows += CertificationRecord("E1", outcome, "S1", failed, "runner", Instant.EPOCH)
    }

    private fun goLive() =
        GoLive(
            tx,
            structure,
            SecondConfirmation(authority),
            coverage,
            certifications,
            ElectionGate(ElectionStatusQueries(tx, structure, policies, facts)),
            lifecycleStore,
            statuses,
            { emptyList() },
            publisher,
            { tenant },
            { today },
        )

    private fun suspend() =
        SuspendEntity(tx, structure, lifecycleStore, statuses, { emptyList() }, publisher, { tenant }, { today })

    private fun deregister() =
        DeregisterEntity(
            tx,
            structure,
            unsubmitted,
            finalPeriod,
            { _, override, _ -> overrides += override },
            lifecycleStore,
            statuses,
            { emptyList() },
            publisher,
            { tenant },
            { today },
        )

    private fun live(second: String = "identity:USR-0040") = goLive().handle(caller, "E1", second)

    private fun refused(outcome: LifecycleOutcome) =
        assertInstanceOf(LifecycleOutcome.Refused::class.java, outcome).detail

    private fun assertUntouched() {
        assertEquals("DRAFT", current.lifecycleState)
        assertTrue(outbox.isEmpty())
    }

    private fun onlyEvent(
        attribute: String,
        value: String,
    ): EntityProfileChange {
        val event = outbox.single()
        assertEquals(attribute, event.changedAttributes.last().attribute)
        assertEquals(value, event.changedAttributes.last().newValue)
        assertEquals(today, event.effectiveDate)
        return event
    }

    // ---- go-live gate ----

    @Test
    fun `goLive with the trigger-coverage port unbound is refused naming trigger coverage unknown`() {
        coverage = UnboundTriggerCoverage()
        certify(CertificationOutcome.PASSED)
        assertTrue(refused(live()).contains("trigger coverage unknown"))
        assertUntouched()
    }

    @Test
    fun `goLive with no certification record is refused naming the sandbox certification`() {
        assertTrue(refused(live()).contains("sandbox certification"))
        assertUntouched()
    }

    @Test
    fun `INV-AC-1064 goLive whose latest certification is FAILED is refused naming s1 and s2`() {
        certify(CertificationOutcome.FAILED, listOf("s1", "s2"))
        val detail = refused(live())
        assertTrue(detail.contains("s1") && detail.contains("s2"), detail)
        assertUntouched()
    }

    @Test
    fun `goLive with an outstanding election is refused naming it`() {
        certify(CertificationOutcome.PASSED)
        elected -= ElectionKind.ISSUANCE_MODE
        assertTrue(refused(live()).contains("ISSUANCE_MODE"))
        assertUntouched()
    }

    @Test
    fun `goLive names every unmet item at once`() {
        coverage = TriggerCoverage { listOf("T1") }
        elected -= ElectionKind.ISSUANCE_MODE
        val detail = refused(live())
        listOf("T1", "sandbox certification", "ISSUANCE_MODE").forEach { assertTrue(detail.contains(it), detail) }
    }

    @Test
    fun `goLive whose latest certification is PASSED with every item met sets LIVE and answers LIVE`() {
        certify(CertificationOutcome.FAILED, listOf("s1"))
        certify(CertificationOutcome.PASSED)
        val done = assertInstanceOf(LifecycleOutcome.Done::class.java, live())
        assertEquals(LifecycleState.LIVE, done.state)
        assertEquals("LIVE", current.lifecycleState)
        onlyEvent("lifecycleState", "LIVE")
    }

    @Test
    fun `goLive repeated leaves LIVE and writes no second outbox row`() {
        certify(CertificationOutcome.PASSED)
        live()
        val again = assertInstanceOf(LifecycleOutcome.Done::class.java, live())
        assertTrue(again.repeated)
        assertEquals("LIVE", current.lifecycleState)
        assertEquals(1, outbox.size)
    }

    // ---- second confirmation of go-live (INV-REQ-875) ----

    @Test
    fun `goLive with secondConfirmationBy equal to the caller is refused 403 and the state is not LIVE`() {
        certify(CertificationOutcome.PASSED)
        assertInstanceOf(LifecycleOutcome.Forbidden::class.java, live(caller))
        assertNotEquals("LIVE", current.lifecycleState)
        assertUntouched()
    }

    @Test
    fun `goLive accepts identity USR-0040 against the caller USR-004 as another person`() {
        certify(CertificationOutcome.PASSED)
        assertInstanceOf(LifecycleOutcome.Done::class.java, live("identity:USR-0040"))
        assertEquals("LIVE", current.lifecycleState)
    }

    @Test
    fun `goLive with a confirmer who holds only finance viewer is refused 403`() {
        certify(CertificationOutcome.PASSED)
        assertInstanceOf(LifecycleOutcome.Forbidden::class.java, live("identity:USR-FIN"))
        assertUntouched()
    }

    @Test
    fun `goLive with a confirmer who holds only tax approver permission is accepted`() {
        certify(CertificationOutcome.PASSED)
        assertInstanceOf(LifecycleOutcome.Done::class.java, live("identity:USR-TAX"))
        assertEquals("LIVE", current.lifecycleState)
    }

    @Test
    fun `goLive without secondConfirmationBy is refused 400`() {
        assertInstanceOf(LifecycleOutcome.Invalid::class.java, goLive().handle(caller, "E1", null))
        assertUntouched()
    }

    // ---- suspend ----

    @Test
    fun `suspendEntity sets SUSPENDED answers SUSPENDED and leaves earlier submissions untouched`() {
        current = current.copy(lifecycleState = "LIVE")
        val done = assertInstanceOf(LifecycleOutcome.Done::class.java, suspend().handle("E1"))
        assertEquals(LifecycleState.SUSPENDED, done.state)
        assertEquals("SUSPENDED", current.lifecycleState)
        assertNotEquals("LIVE", current.lifecycleState)
        assertEquals(listOf("SUB-1", "SUB-2"), submissions)
        onlyEvent("lifecycleState", "SUSPENDED")
    }

    // ---- deregister ----

    @Test
    fun `deregisterEntity with an unsubmitted document and no override is refused TNC-008`() {
        unsubmitted = UnsubmittedDocuments { _, _ -> 3 }
        val outcome = deregister().handle("E1", LocalDate.of(2026, 8, 15), null)
        assertEquals("TNC-008", assertInstanceOf(LifecycleOutcome.Conflict::class.java, outcome).code)
        assertUntouched()
        assertTrue(overrides.isEmpty())
    }

    @Test
    fun `deregisterEntity with unsubmittedOverride records it and sets DEREGISTERED`() {
        unsubmitted = UnsubmittedDocuments { _, _ -> 3 }
        val ack = UnsubmittedOverride(caller, "accepted")
        val outcome = deregister().handle("E1", LocalDate.of(2026, 8, 15), ack)
        val done = assertInstanceOf(LifecycleOutcome.Done::class.java, outcome)
        assertEquals(LifecycleState.DEREGISTERED, done.state)
        assertEquals("DEREGISTERED", current.lifecycleState)
        assertEquals(listOf(ack), overrides)
    }

    @Test
    fun `deregisterEntity writes one event with registrationEndDate first and lifecycleState second`() {
        val end = LocalDate.of(2026, 8, 15)
        deregister().handle("E1", end, null)
        val event = outbox.single()
        assertEquals("registrationEndDate", event.changedAttributes[0].attribute)
        assertEquals("2026-08-15", event.changedAttributes[0].newValue)
        assertEquals("lifecycleState", event.changedAttributes[1].attribute)
        assertEquals("DEREGISTERED", event.changedAttributes[1].newValue)
        assertEquals(today, event.effectiveDate)
        assertEquals(
            setOf("registrationEndDate", "lifecycleState"),
            event.node.profile
                .map { it.attribute }
                .toSet(),
        )
    }

    @Test
    fun `deregisterEntity with the count port unbound is refused 422 and changes nothing`() {
        unsubmitted = UnsubmittedDocuments { _, _ -> throw UnavailableInput("documents-count") }
        assertTrue(refused(deregister().handle("E1", today, null)).contains("documents-count"))
        assertUntouched()
    }

    @Test
    fun `deregisterEntity whose final period is refused is refused 422 and records nothing`() {
        finalPeriod = FinalPeriod { _, _ -> PeriodOutcome.Refused("no registration date recorded") }
        assertEquals("no registration date recorded", refused(deregister().handle("E1", today, null)))
        assertUntouched()
    }

    @Test
    fun `deregisterEntity calls the final period with the entity and the registration end date`() {
        val calls = mutableListOf<Pair<String, LocalDate>>()
        finalPeriod = FinalPeriod { ref, date -> calls.add(ref to date).let { PeriodOutcome.Done(emptyList()) } }
        deregister().handle("E1", LocalDate.of(2026, 8, 15), null)
        assertEquals(listOf("E1" to LocalDate.of(2026, 8, 15)), calls)
    }

    // ---- events and rollback ----

    @Test
    fun `each lifecycle change writes only EntityProfileChanged rows and no organisation structure change`() {
        certify(CertificationOutcome.PASSED)
        live()
        suspend().handle("E1")
        deregister().handle("E1", today, null)
        assertEquals(listOf("LIVE", "SUSPENDED", "DEREGISTERED"), outbox.map { it.changedAttributes.last().newValue })
        assertEquals(3, outbox.size)
        assertEquals(
            listOf("LIVE", "SUSPENDED", "DEREGISTERED"),
            outbox.map {
                it.node.profile
                    .last()
                    .value
            },
        )
    }

    @Test
    fun `a goLive that rolls back writes no row and keeps the state`() {
        certify(CertificationOutcome.PASSED)
        publishFailure = true
        assertThrows(IllegalStateException::class.java) { live() }
        assertUntouched()
    }

    @Test
    fun `a deregisterEntity that rolls back writes no row and keeps the state`() {
        publishFailure = true
        assertThrows(IllegalStateException::class.java) { deregister().handle("E1", today, null) }
        assertUntouched()
    }

    @Test
    fun `an unknown entity is not found by all three operations`() {
        assertEquals(LifecycleOutcome.NotFound, goLive().handle(caller, "E9", "identity:USR-0040"))
        assertEquals(LifecycleOutcome.NotFound, suspend().handle("E9"))
        assertEquals(LifecycleOutcome.NotFound, deregister().handle("E9", today, null))
    }

    private fun <T : Any> proxy(
        type: Class<T>,
        handler: (java.lang.reflect.Method, Array<Any?>) -> Any?,
    ): T =
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                handler(method, args ?: emptyArray())
            },
        )
}
