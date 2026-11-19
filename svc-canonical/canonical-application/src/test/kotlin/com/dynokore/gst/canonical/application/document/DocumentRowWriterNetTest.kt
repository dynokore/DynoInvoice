package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.application.netting.NetCreditNoteRef
import com.dynokore.gst.canonical.application.netting.NetDerivationDocuments
import com.dynokore.gst.canonical.application.netting.NetDerivationRecords
import com.dynokore.gst.canonical.application.netting.NetDerivedVersions
import com.dynokore.gst.canonical.application.netting.NetRederivation
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.document.DocumentState
import com.dynokore.gst.canonical.domain.document.NewDocument
import com.dynokore.gst.canonical.domain.document.NewVersion
import com.dynokore.gst.canonical.domain.document.ScopeVerdict
import com.dynokore.gst.canonical.domain.netting.Derived
import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import com.dynokore.gst.canonical.domain.netting.NetBase
import com.dynokore.gst.canonical.domain.netting.NetCreditNote
import com.dynokore.gst.canonical.domain.netting.NetLine
import com.dynokore.gst.canonical.domain.netting.NetSubtotal
import com.dynokore.gst.canonical.domain.netting.NetTotals
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class DocumentRowWriterNetTest {
    private fun id(n: Int) = UUID.fromString("00000000-0000-0000-0000-0000000000c$n")

    private fun d(s: String) = BigDecimal(s)

    private val tenant = id(8)
    private val entity = id(9)
    private val original = id(0)
    private val noteA = id(1)
    private val date = LocalDate.parse("2026-03-20")

    private val calls = mutableListOf<String>()
    private val asked = mutableListOf<Pair<UUID, Int>>()
    private var predecessor: NetDerivationFact? = NetDerivationFact(2, listOf(NetDerivationCreditNoteFact(noteA, 2)))
    private var declared: List<String> = emptyList()

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

    private val note =
        NetCreditNote(
            noteA,
            2,
            original,
            entity,
            Direction.PURCHASE,
            DocumentFunction.CREDIT_NOTE,
            "SGD",
            null,
            listOf(line("1000.00", "60.00")),
            listOf(NetSubtotal("TX", d("1000.00"), d("60.00"))),
            NetTotals(d("1000.00"), d("60.00"), d("1060.00")),
        )

    private val store =
        object : DocumentStore {
            override fun insert(document: NewDocument) {
                calls += "insert"
            }

            override fun createVersion(
                docUuid: UUID,
                expectedVersion: Int,
                version: NewVersion,
            ): UpdateOutcome {
                calls += "createVersion ${expectedVersion + 1} ${version.canonical}"
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
                calls += "stage ${facts.version}"
            }
        }

    private val refs =
        object : DocumentAggregateRefs {
            override fun aggregateIdOf(docUuid: UUID): String? = null

            override fun bind(
                docUuid: UUID,
                aggregateId: String,
            ) = true
        }

    private val versionWriter = CanonicalVersionWriter(store, publisher, refs)

    private val rederivation =
        NetRederivation(
            records =
                object : NetDerivationRecords {
                    override fun record(
                        docUuid: UUID,
                        version: Int,
                        baseVersion: Int,
                        creditNotes: List<NetCreditNoteRef>,
                    ) {
                        calls += "record $version $baseVersion"
                    }

                    override fun recordOf(
                        docUuid: UUID,
                        version: Int,
                    ): NetDerivationFact? {
                        asked += docUuid to version
                        return predecessor
                    }
                },
            documents =
                object : NetDerivationDocuments {
                    override fun currentVersion(docUuid: UUID) = 3

                    override fun base(
                        docUuid: UUID,
                        version: Int,
                    ) = if (version == 3) base else null

                    override fun creditNote(
                        docUuid: UUID,
                        version: Int,
                    ) = note
                },
            versions =
                object : NetDerivedVersions {
                    override fun build(
                        docUuid: UUID,
                        baseVersion: Int,
                        derived: Derived,
                    ) = NewVersion("derived", null, null, null, null, null, date, "system")
                },
            writer = versionWriter,
            factsFor = { doc, fact ->
                CanonicalVersionCreatedFacts(
                    docUuid = doc,
                    entityRef = "E1",
                    version = 0,
                    transactionDate = date,
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

    private val rowWriter = DocumentRowWriter(versionWriter, rederivation) { declared }

    private val command =
        RecordDocumentCommand(
            tenantId = tenant,
            entityRef = "E1",
            sourceSystem = "S1",
            sourceKey = "K1",
            sourceRevision = 1,
            transactionDate = date,
            direction = Direction.PURCHASE,
            supplier = PartyIdentifier("UEN", "SUP-1"),
            buyer = PartyIdentifier("UEN", "BUY-1"),
            invoiceBacked = true,
            documentNumber = "INV-001",
        )

    private val facts =
        DocumentRowFacts(
            entity,
            null,
            380,
            "CTP-118",
            2026,
            null,
            id(7),
            DocumentState.INGESTED,
            "2026-Q1",
            emptyList(),
            NewVersion("erp", ScopeVerdict.IN_SCOPE, "S-2026.1", "M-3", null, null, date, "user-1"),
        )

    @Test
    fun `INV-AC-25341 the derived version is written after version 3 in the same call (call order)`() {
        rowWriter.write(command, original, 3, facts)
        calls shouldBe listOf("createVersion 3 erp", "stage 3", "createVersion 4 derived", "stage 4", "record 4 3")
    }

    @Test
    fun `INV-AC-25341 version 3 declaring the credit note writes the ERP version only`() {
        declared = listOf(noteA.toString())
        rowWriter.write(command, original, 3, facts)
        calls shouldBe listOf("createVersion 3 erp", "stage 3")
    }

    @Test
    fun `INV-AC-25341 version 3 without a derived predecessor writes the ERP version only`() {
        predecessor = null
        rowWriter.write(command, original, 3, facts)
        calls shouldBe listOf("createVersion 3 erp", "stage 3")
        asked shouldBe listOf(original to 2)
    }

    @Test
    fun `INV-AC-25341 version 1 does not call the re-derivation`() {
        rowWriter.write(command, original, 1, facts)
        calls shouldBe listOf("insert", "stage 1")
        asked shouldBe emptyList()
    }
}
