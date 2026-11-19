package com.dynokore.gst.canonical.application.netting

import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedFacts
import com.dynokore.gst.canonical.application.document.CanonicalVersionCreatedPublisher
import com.dynokore.gst.canonical.application.document.CanonicalVersionWriter
import com.dynokore.gst.canonical.application.document.DocumentAggregateRefs
import com.dynokore.gst.canonical.application.document.DocumentStore
import com.dynokore.gst.canonical.application.document.NetDerivationCreditNoteFact
import com.dynokore.gst.canonical.application.document.NetDerivationFact
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
import com.dynokore.gst.canonical.domain.netting.NetSubtotal
import com.dynokore.gst.canonical.domain.netting.NetTotals
import com.dynokore.platform.serviceruntime.persistence.UpdateOutcome
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class NetRederivationTest {
    private fun id(n: Int) = UUID.fromString("00000000-0000-0000-0000-0000000000b$n")

    private fun d(s: String) = BigDecimal(s)

    private val entity = id(9)
    private val original = id(0)
    private val noteA = id(1)
    private val refA = listOf(NetDerivationCreditNoteFact(noteA, 2))

    private val calls = mutableListOf<String>()
    private val staged = mutableListOf<CanonicalVersionCreatedFacts>()
    private val asked = mutableListOf<Pair<UUID, Int>>()
    private var predecessor: NetDerivationFact? = NetDerivationFact(2, refA)
    private var baseAmount = "9000.00"

    private fun line(
        amount: String,
        tax: String,
    ) = NetLine(1, BigDecimal.ONE, d(amount), d(amount), "TX", d(tax))

    private fun base() =
        NetBase(
            entity,
            Direction.PURCHASE,
            DocumentFunction.INVOICE,
            "SGD",
            null,
            listOf(line(baseAmount, "540.00")),
            listOf(NetSubtotal("TX", d(baseAmount), d("540.00"))),
            NetTotals(d(baseAmount), d("540.00"), d(baseAmount) + d("540.00")),
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
            override fun insert(document: NewDocument) = error("not used")

            override fun createVersion(
                docUuid: UUID,
                expectedVersion: Int,
                version: NewVersion,
            ): UpdateOutcome {
                calls += "createVersion $expectedVersion ${version.canonical}"
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
                        calls += "record $version $baseVersion ${creditNotes.map { it.docUuid }}"
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
                    ) = if (docUuid == original && version == 3) base() else null

                    override fun creditNote(
                        docUuid: UUID,
                        version: Int,
                    ) = if (docUuid == noteA && version == 2) note else null
                },
            versions =
                object : NetDerivedVersions {
                    override fun build(
                        docUuid: UUID,
                        baseVersion: Int,
                        derived: Derived,
                    ) = NewVersion(
                        "derived ${derived.totals.excludingTax}",
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDate.parse("2026-03-20"),
                        "system",
                    )
                },
            writer = CanonicalVersionWriter(store, publisher, refs),
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

    private val expectedNet get() =
        (
            NetDerivation.derive(
                original,
                base(),
                listOf(note),
            ) as Derived
        ).totals.excludingTax

    @Test
    fun `INV-AC-25341 version 3 after derived version 2 writes the derived version 4 net of the same credit note`() {
        rederivation.afterErpVersion(original, 3, emptyList())
        calls shouldBe listOf("createVersion 3 derived $expectedNet", "record 4 3 [$noteA]")
        staged.single().netDerivation shouldBe NetDerivationFact(3, refA)
        staged.single().version shouldBe 4
    }

    @Test
    fun `INV-AC-25341 version 3 declaring the credit note writes nothing more`() {
        rederivation.afterErpVersion(original, 3, listOf(noteA))
        calls shouldBe emptyList()
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25341 a declared Doc UUID of another credit note still re-derives`() {
        val lookAlikes = listOf(id(2), original, entity)
        rederivation.afterErpVersion(original, 3, lookAlikes)
        calls shouldBe listOf("createVersion 3 derived $expectedNet", "record 4 3 [$noteA]")
    }

    @Test
    fun `INV-AC-25341 no derived predecessor writes nothing more`() {
        predecessor = null
        rederivation.afterErpVersion(original, 3, emptyList())
        calls shouldBe emptyList()
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25341 a refused re-derivation writes nothing more`() {
        baseAmount = "500.00" // net not positive: NET_NOT_POSITIVE
        rederivation.afterErpVersion(original, 3, emptyList())
        calls shouldBe emptyList()
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25341 recordOf is asked for exactly the version before the new one and null writes nothing more`() {
        predecessor = null
        rederivation.afterErpVersion(original, 3, emptyList())
        asked shouldBe listOf(original to 2)
        calls shouldBe emptyList()
    }
}
