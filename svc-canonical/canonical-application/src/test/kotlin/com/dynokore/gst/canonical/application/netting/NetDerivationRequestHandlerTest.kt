package com.dynokore.gst.canonical.application.netting

import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.DocumentAggregateRefs
import com.dynokore.gst.canonical.application.document.DocumentStore
import com.dynokore.gst.canonical.application.document.NetDerivationCreditNoteFact
import com.dynokore.gst.canonical.application.document.NetDerivationFact
import com.dynokore.gst.canonical.application.document.NetDerivationKind
import com.dynokore.gst.canonical.application.document.RoutingDirection
import com.dynokore.gst.canonical.application.document.RoutingFactsData
import com.dynokore.gst.canonical.application.document.RoutingNetworkPath
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.netting.Derived
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import com.dynokore.gst.canonical.domain.netting.NetBase
import com.dynokore.gst.canonical.domain.netting.NetCreditNote
import com.dynokore.gst.canonical.domain.netting.NetDerivation
import com.dynokore.gst.canonical.domain.netting.NetLine
import com.dynokore.gst.canonical.domain.netting.NetRefusal
import com.dynokore.gst.canonical.domain.netting.NetSubtotal
import com.dynokore.gst.canonical.domain.netting.NetTotals
import com.dynokore.gst.canonical.domain.netting.Refused
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class NetDerivationRequestHandlerTest {
    private fun id(n: Int) = UUID.fromString("00000000-0000-0000-0000-0000000000a$n")

    private fun d(s: String) = BigDecimal(s)

    private val entity = id(9)
    private val original = id(0)
    private val noteA = id(1)
    private val r1 = id(5)

    private val processed = mutableSetOf<UUID>()
    private val writes = mutableListOf<String>()
    private val staged = mutableListOf<CanonicalVersionCreatedFacts>()
    private val refusals = mutableListOf<List<Any>>()
    private val recorded = mutableListOf<String>()
    private var currentVersion: Int? = 3
    private var conflict = false
    private var failPublisher = false

    private fun line(
        amount: String,
        tax: String,
    ) = NetLine(1, BigDecimal.ONE, d(amount), d(amount), "TX", d(tax))

    private val base =
        NetBase(
            entity,
            Direction.PURCHASE,
            DocumentFunction.INVOICE,
            "SGD",
            null,
            listOf(line("9000.00", "540.00")),
            listOf(NetSubtotal("TX", d("9000.00"), d("540.00"))),
            NetTotals(d("9000.00"), d("540.00"), d("9540.00")),
        )

    private fun note(
        docUuid: UUID,
        amount: String,
        tax: String,
        entityId: UUID = entity,
    ) = NetCreditNote(
        docUuid,
        2,
        original,
        entityId,
        Direction.PURCHASE,
        DocumentFunction.CREDIT_NOTE,
        "SGD",
        null,
        listOf(line(amount, tax)),
        listOf(NetSubtotal("TX", d(amount), d(tax))),
        NetTotals(d(amount), d(tax), (d(amount) + d(tax))),
    )

    private val notes = mutableMapOf(noteA to note(noteA, "1000.00", "60.00"))

    private val documents =
        object : NetDerivationDocuments {
            override fun currentVersion(docUuid: UUID) = if (docUuid == original) currentVersion else null

            override fun base(
                docUuid: UUID,
                version: Int,
            ) = if (docUuid == original && version == currentVersion) base else null

            override fun creditNote(
                docUuid: UUID,
                version: Int,
            ) = notes[docUuid]
        }

    private val store =
        object : DocumentStore {
            override fun insert(document: NewDocument) = error("not used")

            override fun createVersion(
                docUuid: UUID,
                expectedVersion: Int,
                version: NewVersion,
            ): UpdateOutcome {
                writes += "createVersion $docUuid $expectedVersion ${version.canonical}"
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

    private val eventPublisher =
        object : CanonicalVersionCreatedPublisher {
            override fun stage(facts: CanonicalVersionCreatedFacts) {
                staged += facts
            }
        }

    private val refs =
        object : DocumentAggregateRefs {
            override fun aggregateIdOf(docUuid: UUID): String? = null

            override fun bind(
                docUuid: UUID,
                aggregateId: String,
            ) = error("not used")
        }

    private val refusedPublisher =
        object : NetDerivationRefusedPublisher {
            override fun stage(
                originalDocUuid: UUID,
                requestEventId: UUID,
                reason: NetRefusal,
                creditNotes: List<NetCreditNoteRef>,
            ) {
                if (failPublisher) error("outbox down")
                refusals += listOf(originalDocUuid, requestEventId, reason)
            }
        }

    private val derivedMarker = "derived"

    private val handler =
        NetDerivationRequestHandler(
            requests =
                object : ProcessedNetDerivationRequests {
                    override fun markIfNew(requestEventId: UUID) = processed.add(requestEventId)
                },
            documents = documents,
            versions =
                object : NetDerivedVersions {
                    override fun build(
                        docUuid: UUID,
                        baseVersion: Int,
                        derived: Derived,
                    ) = NewVersion(
                        "$derivedMarker ${derived.totals.excludingTax}",
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.parse("2026-03-20"),
                        "system",
                    )
                },
            writer = CanonicalVersionWriter(store, eventPublisher, refs),
            records =
                object : NetDerivationRecords {
                    override fun record(
                        docUuid: UUID,
                        version: Int,
                        baseVersion: Int,
                        creditNotes: List<NetCreditNoteRef>,
                    ) {
                        recorded += "$docUuid $version $baseVersion ${creditNotes.map { it.docUuid }}"
                    }

                    override fun recordOf(
                        docUuid: UUID,
                        version: Int,
                    ): NetDerivationFact? = null
                },
            refusals = refusedPublisher,
            factsFor = { doc, fact ->
                CanonicalVersionCreatedFacts(
                    docUuid = doc,
                    entityRef = "E1",
                    version = 0,
                    transactionDate = LocalDate.parse("2026-03-20"),
                    prescribedPeriodRef = "2026-Q1",
                    scopeVerdict = "IN_SCOPE",
                    appliedPackVersions = emptyList(),
                    traceReference = "trace",
                    canonicalDocument = "{}",
                    aggregateId = null,
                    routingFacts = RoutingFactsData(RoutingDirection.PURCHASE, RoutingNetworkPath.NOT_ON_NETWORK),
                    netDerivation = fact,
                )
            },
        )

    private val refA = listOf(NetDerivationCreditNoteFact(noteA, 2))

    /** The fake store is transactional: an exception rolls the processed set back. */
    private fun inTx(block: () -> Unit) {
        val snapshot = processed.toSet()
        try {
            block()
        } catch (e: IllegalStateException) {
            processed.clear()
            processed += snapshot
            throw e
        }
    }

    private fun handle(
        event: UUID = r1,
        expected: Int = 3,
        creditNotes: List<NetCreditNoteRef> = refA,
    ) = inTx { handler.handle(event, original, expected, creditNotes) }

    @Test
    fun `INV-AC-25340 first request writes once with the expected arguments`() {
        handle()
        val expected = (NetDerivation.derive(original, base, listOf(notes.getValue(noteA))) as Derived)
        writes shouldBe listOf("createVersion $original 3 $derivedMarker ${expected.totals.excludingTax}")
        staged.size shouldBe 1
        staged.single().netDerivation shouldBe NetDerivationFact(3, refA, NetDerivationKind.SYSTEM_DERIVED)
        staged.single().netDerivation!!.nettedCreditNoteDocUuids shouldBe listOf(noteA)
        staged.single().version shouldBe 4
        recorded shouldBe listOf("$original 4 3 [$noteA]")
        refusals shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25340 repeated request writes nothing`() {
        handle()
        writes.clear()
        staged.clear()
        recorded.clear()
        handle()
        writes shouldBe emptyList()
        staged shouldBe emptyList()
        recorded shouldBe emptyList()
        refusals shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25340 expected base version 2 against version 3 stages BASE_CHANGED and writes nothing`() {
        handle(expected = 2)
        refusals shouldBe listOf(listOf(original, r1, NetRefusal.BASE_CHANGED))
        writes shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25340 other entity request stages OTHER_ENTITY with the request event id`() {
        notes[noteA] = note(noteA, "1000.00", "60.00", entityId = id(8))
        handle()
        refusals shouldBe listOf(listOf(original, r1, NetRefusal.OTHER_ENTITY))
        writes shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25340 non-positive net stages NET_NOT_POSITIVE with the request event id`() {
        notes[noteA] = note(noteA, "9000.00", "540.00")
        handle()
        (NetDerivation.derive(original, base, listOf(notes.getValue(noteA))) as Refused).reason shouldBe
            NetRefusal.NET_NOT_POSITIVE
        refusals shouldBe listOf(listOf(original, r1, NetRefusal.NET_NOT_POSITIVE))
        writes shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25340 unknown document stages DOCUMENT_NOT_FOUND with the request event id`() {
        currentVersion = null
        handle()
        refusals shouldBe listOf(listOf(original, r1, NetRefusal.DOCUMENT_NOT_FOUND))
        writes shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25340 unknown credit note version stages DOCUMENT_NOT_FOUND`() {
        handle(creditNotes = listOf(NetDerivationCreditNoteFact(id(7), 1)))
        refusals shouldBe listOf(listOf(original, r1, NetRefusal.DOCUMENT_NOT_FOUND))
        writes shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25340 a writer Conflict stages BASE_CHANGED`() {
        conflict = true
        handle()
        refusals shouldBe listOf(listOf(original, r1, NetRefusal.BASE_CHANGED))
        staged shouldBe emptyList()
        recorded shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25340 a publisher exception leaves the processed mark unset`() {
        failPublisher = true
        shouldThrow<IllegalStateException> { handle(expected = 2) }
        processed shouldBe emptySet()
        failPublisher = false
        handle(expected = 2)
        refusals.size shouldBe 1
    }

    @Test
    fun `INV-AC-25340 a request with a new event id for the same data is handled as new`() {
        handle()
        handle(event = id(6))
        writes.size shouldBe 2
    }
}
