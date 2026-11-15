package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.document.SpecificationVersion
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID

class CanonicalVersionWriterTest {
    private fun id(n: Int) = UUID.fromString("00000000-0000-0000-0000-0000000000a$n")

    private val date = LocalDate.parse("2026-03-20")
    private val calls = mutableListOf<String>()
    private val staged = mutableListOf<CanonicalVersionCreatedFacts>()
    private val stored = mutableListOf<String>()
    private var failPublisher = false

    /** Documents and their current version; a stale expected version answers Conflict. */
    private val current = mutableMapOf(id(2) to 2, id(3) to 4, id(4) to 2)

    private val store =
        object : DocumentStore {
            override fun insert(document: NewDocument) {
                calls += "insert ${document.docUuid}"
                stored += "doc ${document.docUuid}"
            }

            override fun createVersion(
                docUuid: UUID,
                expectedVersion: Int,
                version: NewVersion,
            ): UpdateOutcome {
                calls += "createVersion $docUuid $expectedVersion"
                if (current[docUuid] != expectedVersion) return UpdateOutcome.Conflict
                stored += "version $docUuid ${expectedVersion + 1}"
                return UpdateOutcome.Updated(expectedVersion + 1L)
            }

            override fun rebuildDerived(
                docUuid: UUID,
                version: Int,
            ) = error("not used")

            override fun versionKeys(tenantId: UUID) = error("not used")

            override fun readWindow(
                tenantId: UUID,
                entityId: UUID,
                from: LocalDate,
                to: LocalDate,
                pageSize: Int,
                afterDocUuid: UUID?,
            ) = error("not used")
        }

    private val publisher =
        object : CanonicalVersionCreatedPublisher {
            override fun stage(facts: CanonicalVersionCreatedFacts) {
                calls += "stage"
                if (failPublisher) error("outbox down")
                staged += facts
            }
        }

    private val refs =
        object : DocumentAggregateRefs {
            override fun aggregateIdOf(docUuid: UUID): String? = if (docUuid == id(2)) "AGG-2026-0002" else null
        }

    private var commits = 0
    private var rollbacks = 0

    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                val before = stored.toList()
                val stagedBefore = staged.toList()
                return try {
                    block().also { commits++ }
                } catch (e: Throwable) {
                    rollbacks++
                    stored.clear()
                    stored += before
                    staged.clear()
                    staged += stagedBefore
                    throw e
                }
            }
        }

    private val writer = CanonicalVersionWriter(store, publisher, refs)

    private val sale = RoutingFactsData(RoutingDirection.SALE, RoutingNetworkPath.ISSUED_VIA_NETWORK)

    private fun facts(
        n: Int,
        aggregateId: String? = null,
        routing: RoutingFactsData = sale,
    ) = CanonicalVersionCreatedFacts(
        docUuid = id(n),
        entityRef = "E1",
        version = 1,
        transactionDate = date,
        prescribedPeriodRef = "2026-Q1",
        scopeVerdict = "IN_SCOPE",
        appliedPackVersions = listOf(AppliedPackVersion("scope", "1.0.0")),
        traceReference = "trace-$n",
        canonicalDocument = "{}",
        aggregateId = aggregateId,
        routingFacts = routing,
    )

    private val newVersion =
        NewVersion("{}", ScopeVerdict.IN_SCOPE, "1", "1", null, SpecificationVersion.PINT_SG, date, "u")

    private fun newDocument(n: Int) =
        NewDocument(
            id(n),
            id(8),
            id(9),
            null,
            Direction.SALE,
            380,
            "INV-$n",
            "P",
            2026,
            null,
            DocumentState.INGESTED,
            null,
            newVersion,
        )

    @Test
    fun `INV-AC-22846 first version stages one fact set with the caller's aggregateId`() {
        tx.inTransaction { writer.createFirst(newDocument(1), facts(1, aggregateId = "AGG-2026-0001")) }
        staged.map { it.version to it.aggregateId } shouldBe listOf(1 to "AGG-2026-0001")
        calls shouldBe listOf("insert ${id(1)}", "stage")
    }

    @Test
    fun `INV-AC-22846 next version stages expected plus one with the stored aggregate reference`() {
        val outcome = tx.inTransaction { writer.next(id(2), 2, newVersion, facts(2).copy(version = 99)) }
        outcome shouldBe UpdateOutcome.Updated(3)
        staged.map { it.docUuid to it.version to it.aggregateId } shouldBe
            listOf(id(2) to 3 to "AGG-2026-0002")
        calls shouldBe listOf("createVersion ${id(2)} 2", "stage")
    }

    @Test
    fun `INV-AC-22846 next version of a document without a stored reference stages aggregateId null`() {
        tx.inTransaction { writer.next(id(3), 4, newVersion, facts(3)) }
        staged.size shouldBe 1
        staged.single().aggregateId shouldBe null
        staged.single().version shouldBe 5
    }

    @Test
    fun `INV-AC-22846 a stale write returns Conflict and stages nothing`() {
        val outcome = tx.inTransaction { writer.next(id(3), 1, newVersion, facts(3)) }
        outcome shouldBe UpdateOutcome.Conflict
        staged shouldBe emptyList<CanonicalVersionCreatedFacts>()
        calls shouldBe listOf("createVersion ${id(3)} 1")
    }

    @Test
    fun `INV-AC-22846 a publisher exception propagates and the store write rolls back`() {
        failPublisher = true
        val e =
            assertThrows<IllegalStateException> {
                tx.inTransaction { writer.next(id(2), 2, newVersion, facts(2)) }
            }
        e.message shouldBe "outbox down"
        rollbacks shouldBe 1
        stored shouldBe emptyList<String>()
    }

    @Test
    fun `INV-AC-22846 blank aggregateId, traceReference and prescribedPeriodRef are refused with nothing staged`() {
        assertThrows<IllegalArgumentException> { facts(1, aggregateId = " ") }
        assertThrows<IllegalArgumentException> { facts(1).copy(traceReference = "") }
        assertThrows<IllegalArgumentException> { facts(1).copy(traceReference = "  ") }
        assertThrows<IllegalArgumentException> { facts(1).copy(prescribedPeriodRef = " ") }
        staged shouldBe emptyList<CanonicalVersionCreatedFacts>()
        calls shouldBe emptyList<String>()
    }

    @Test
    fun `INV-AC-22903 unannounced writes call the store and never stage, and Conflict is returned unchanged`() {
        val document = newDocument(1)
        tx.inTransaction { writer.createFirstUnannounced(document, UnannouncedReason.NO_SCOPE_VERDICT) }
        val e =
            tx.inTransaction {
                writer.nextUnannounced(
                    id(2),
                    2,
                    newVersion,
                    UnannouncedReason.NO_PRESCRIBED_PERIOD,
                )
            }
        val f = tx.inTransaction { writer.nextUnannounced(id(3), 1, newVersion, UnannouncedReason.NO_SCOPE_VERDICT) }
        e shouldBe UpdateOutcome.Updated(3)
        f shouldBe UpdateOutcome.Conflict
        calls shouldBe listOf("insert ${id(1)}", "createVersion ${id(2)} 2", "createVersion ${id(3)} 1")
        staged shouldBe emptyList<CanonicalVersionCreatedFacts>()
        commits shouldBe 3
    }

    @Test
    fun `INV-AC-23003 announced writes stage the supplied routing facts unchanged`() {
        val selfBilled =
            RoutingFactsData(
                RoutingDirection.PURCHASE,
                RoutingNetworkPath.NOT_ON_NETWORK,
                selfBillingAssignment =
                    SelfBillingAssignmentData(RoutingDirection.PURCHASE, SelfBillingSubmissionType.TYPE_3),
            )
        val relayedDebit =
            RoutingFactsData(
                RoutingDirection.SALE,
                RoutingNetworkPath.RELAY_ISSUANCE,
                counterpartyOnNetwork = true,
                debitNote = true,
            )
        tx.inTransaction { writer.createFirst(newDocument(1), facts(1, routing = sale)) }
        tx.inTransaction { writer.next(id(2), 2, newVersion, facts(2, routing = selfBilled)) }
        tx.inTransaction { writer.next(id(3), 4, newVersion, facts(3, routing = relayedDebit)) }
        staged.map { it.routingFacts } shouldBe listOf(sale, selfBilled, relayedDebit)
        staged[0].routingFacts.counterpartyOnNetwork shouldBe null
        staged[0].routingFacts.debitNote shouldBe null
        staged[0].routingFacts.selfBillingAssignment shouldBe null
        staged[1].routingFacts.direction shouldBe RoutingDirection.PURCHASE
        staged[2].routingFacts.debitNote shouldBe true
    }

    @Test
    fun `INV-AC-23003 an unannounced write stages no routing facts`() {
        tx.inTransaction { writer.nextUnannounced(id(4), 2, newVersion, UnannouncedReason.NO_SCOPE_VERDICT) }
        staged shouldBe emptyList<CanonicalVersionCreatedFacts>()
    }
}
