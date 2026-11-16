package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID

class RowWritingResultBuilderTest {
    private val t1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val d1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val d2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2")
    private val uE = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val uP = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val date = LocalDate.parse("2026-03-20")

    private val calls = mutableListOf<String>()
    private val inserted = mutableListOf<NewDocument>()
    private val versionCalls = mutableListOf<Triple<UUID, Int, NewVersion>>()
    private val staged = mutableListOf<CanonicalVersionCreatedFacts>()
    private var conflict = false

    private val store =
        object : DocumentStore {
            override fun insert(document: NewDocument) {
                calls += "insert"
                inserted += document
            }

            override fun createVersion(
                docUuid: UUID,
                expectedVersion: Int,
                version: NewVersion,
            ): UpdateOutcome {
                calls += "createVersion"
                versionCalls += Triple(docUuid, expectedVersion, version)
                return if (conflict) UpdateOutcome.Conflict else UpdateOutcome.Updated(expectedVersion + 1L)
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
                staged += facts
            }
        }
    private val refs =
        object : DocumentAggregateRefs {
            override fun aggregateIdOf(docUuid: UUID): String? = null
        }
    private val rowWriter = DocumentRowWriter(CanonicalVersionWriter(store, publisher, refs))

    private fun version(
        verdict: ScopeVerdict? = ScopeVerdict.IN_SCOPE,
        canonical: String = "R2",
    ) = NewVersion(canonical, verdict, "S-2026.1", "M-3", null, null, date, "user-1")

    private val packs =
        listOf(AppliedPackFact("SCOPE", "gst-scope", "S-2026.1"), AppliedPackFact("MAPPING", "MAP-0042", "M-3"))

    private fun facts(
        verdict: ScopeVerdict? = ScopeVerdict.IN_SCOPE,
        periodRef: String = "2026-Q1",
    ) = DocumentRowFacts(
        uE,
        null,
        380,
        "CTP-118",
        2026,
        null,
        uP,
        DocumentState.INGESTED,
        periodRef,
        packs,
        version(verdict),
    )

    private fun command(
        direction: Direction? = Direction.SALE,
        number: String? = "INV-001",
        path: RoutingNetworkPath = RoutingNetworkPath.NOT_ON_NETWORK,
    ) = RecordDocumentCommand(
        tenantId = t1,
        entityRef = "E1",
        sourceSystem = "S1",
        sourceKey = "K1",
        sourceRevision = 1,
        transactionDate = date,
        direction = direction,
        supplier = PartyIdentifier("UEN", "SUP-1"),
        buyer = PartyIdentifier("UEN", "BUY-1"),
        invoiceBacked = true,
        documentNumber = number,
        networkPath = path,
    )

    private var innerCalls = 0
    private var factsAnswer: (() -> DocumentRowFacts?) = { facts() }

    private fun builder() =
        RowWritingResultBuilder(
            { _, _ ->
                innerCalls++
                calls += "inner"
                "R"
            },
            { if (it == d2) 3 else 1 },
            { _, _, _, _ -> factsAnswer() },
            rowWriter,
        )

    @Test
    fun `INV-AC-21478 new Doc UUID inserts the row and returns the inner text`() {
        builder().build(command(), d1) shouldBe "R"
        calls shouldBe listOf("inner", "insert", "stage")
        val expected =
            NewDocument(
                d1,
                t1,
                uE,
                null,
                Direction.SALE,
                380,
                "INV-001",
                "CTP-118",
                2026,
                null,
                DocumentState.INGESTED,
                uP,
                version(),
            )
        inserted.single() shouldBe expected
    }

    @Test
    fun `INV-AC-21478 bound Doc UUID creates the next version from version minus one`() {
        builder().build(command(), d2) shouldBe "R"
        versionCalls.single() shouldBe Triple(d2, 2, version())
        inserted shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21478 conflict throws DocumentRowConflict and returns no text`() {
        conflict = true
        assertThrows<DocumentRowConflict> { builder().build(command(), d2) }
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21478 facts source failure propagates and the store is not called`() {
        factsAnswer = { error("source down") }
        assertThrows<IllegalStateException> { builder().build(command(), d1) }
        calls shouldBe listOf("inner")
    }

    @Test
    fun `INV-AC-21478 absent direction is refused SRT-001 before any call`() {
        assertThrows<RecordRefused> { builder().build(command(direction = null), d1) }.code shouldBe "SRT-001"
        calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21478 absent document number is refused SRT-001 before any call`() {
        assertThrows<RecordRefused> { builder().build(command(number = null), d1) }.code shouldBe "SRT-001"
        calls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-21478 null facts write nothing and the inner builder runs once`() {
        factsAnswer = { null }
        builder().build(command(), d1) shouldBe "R"
        calls shouldBe listOf("inner")
        innerCalls shouldBe 1
    }

    @Test
    fun `INV-AC-21478 inner builder runs once and before the store write`() {
        builder().build(command(), d1)
        innerCalls shouldBe 1
        calls.indexOf("inner") shouldBe 0
    }

    @Test
    fun `INV-AC-22904 first version is inserted and announced with the event facts`() {
        rowWriter.write(command(), d1, 1, facts())
        inserted.size shouldBe 1
        val f = staged.single()
        f.docUuid shouldBe d1
        f.entityRef shouldBe "E1"
        f.version shouldBe 1
        f.transactionDate shouldBe date
        f.prescribedPeriodRef shouldBe "2026-Q1"
        f.scopeVerdict shouldBe "IN_SCOPE"
        f.appliedPackVersions shouldBe
            listOf(AppliedPackVersion("gst-scope", "S-2026.1"), AppliedPackVersion("MAP-0042", "M-3"))
        f.traceReference shouldBe "trace/$d1/v1"
        f.canonicalDocument shouldBe "R2"
        f.aggregateId shouldBe null
        f.routingFacts shouldBe RoutingFactsData(RoutingDirection.SALE, RoutingNetworkPath.NOT_ON_NETWORK)
    }

    @Test
    fun `INV-AC-22904 next version calls createVersion once and stages version 3`() {
        rowWriter.write(command(), d2, 3, facts())
        versionCalls.single().second shouldBe 2
        val f = staged.single()
        f.version shouldBe 3
        f.traceReference shouldBe "trace/$d2/v3"
        f.routingFacts shouldBe RoutingFactsData(RoutingDirection.SALE, RoutingNetworkPath.NOT_ON_NETWORK)
    }

    @Test
    fun `INV-AC-22904 held facts write the row and stage nothing`() {
        rowWriter.write(command(), d1, 1, facts(verdict = null))
        inserted.size shouldBe 1
        staged shouldBe emptyList()
        rowWriter.write(command(), d2, 3, facts(verdict = null))
        versionCalls.size shouldBe 1
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22904 period unassigned facts write the row and stage nothing`() {
        rowWriter.write(command(), d1, 1, facts(periodRef = ""))
        inserted.size shouldBe 1
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22904 conflict throws DocumentRowConflict and stages nothing`() {
        conflict = true
        assertThrows<DocumentRowConflict> { rowWriter.write(command(), d2, 3, facts()) }
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22904 networkPath is copied from the command`() {
        rowWriter.write(command(path = RoutingNetworkPath.RELAY_ISSUANCE), d1, 1, facts())
        val r = staged.single().routingFacts
        r.direction shouldBe RoutingDirection.SALE
        r.networkPath shouldBe RoutingNetworkPath.RELAY_ISSUANCE
        r.counterpartyOnNetwork shouldBe null
        r.debitNote shouldBe null
        r.selfBillingAssignment shouldBe null
    }

    @Test
    fun `INV-AC-22904 no constructor parameter of DocumentRowWriter is a DocumentStore`() {
        DocumentRowWriter::class.java.constructors
            .flatMap { it.parameterTypes.toList() }
            .none { DocumentStore::class.java.isAssignableFrom(it) } shouldBe true
    }
}
