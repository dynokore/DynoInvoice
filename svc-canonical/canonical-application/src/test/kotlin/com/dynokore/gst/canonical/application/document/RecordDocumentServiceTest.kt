package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.application.source.RecordSourceEvent
import com.dynokore.gst.canonical.application.source.RecordedResult
import com.dynokore.gst.canonical.application.source.SourceEventStore
import com.dynokore.gst.canonical.domain.document.Direction
import com.dynokore.gst.canonical.domain.source.SourceKey
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class RecordDocumentServiceTest {
    private val t1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val d1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val date = LocalDate.of(2026, 3, 20)

    private class Harness(
        d1: UUID,
    ) {
        val keys = mutableListOf<SourceKey>()
        val lookups = mutableListOf<CounterpartyLookup>()
        val asked = mutableListOf<Pair<UUID, String>>()
        val built = mutableListOf<Pair<RecordDocumentCommand, UUID>>()
        val rows = linkedMapOf<SourceKey, Pair<UUID, String>>()

        private val store =
            object : SourceEventStore {
                override fun lockSourceKey(key: SourceKey) {
                    keys += key
                }

                override fun docUuidOf(key: SourceKey): UUID? = rows[key]?.first

                override fun boundDocUuid(key: SourceKey): UUID? = null

                override fun resultOf(key: SourceKey): String? = rows[key]?.second

                override fun insert(
                    key: SourceKey,
                    docUuid: UUID,
                    result: String,
                ): Boolean {
                    rows[key] = docUuid to result
                    return true
                }
            }
        private val tx =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            }
        private val directory =
            object : CounterpartyDirectory {
                override fun partyRefOf(lookup: CounterpartyLookup): PartyRefAnswer {
                    lookups += lookup
                    return PartyRefAnswer.NotFound
                }
            }
        private val constitutions =
            object : EntityConstitutions {
                override fun constitutionOf(
                    tenantId: UUID,
                    entityRef: String,
                ): String? {
                    asked += tenantId to entityRef
                    return if (entityRef == "E1") "C1" else null
                }
            }
        private val builder =
            DocumentResultBuilder { command, docUuid ->
                built += command to docUuid
                "R-$docUuid"
            }
        val service =
            RecordDocumentService(
                RecordDocumentPipeline(
                    directory,
                    RecordSourceEvent(store, tx) { d1 },
                    RecordPartyRef(
                        object : DocumentPartyRefs {
                            override fun upsert(
                                docUuid: UUID,
                                partyRef: String,
                            ) = Unit
                        },
                    ),
                ),
                constitutions,
                builder,
            )
    }

    private fun command(
        direction: Direction? = Direction.SALE,
        entityRef: String = "E1",
        buyer: PartyIdentifier = PartyIdentifier("UEN", "BUY-1"),
    ) = RecordDocumentCommand(
        t1,
        entityRef,
        "S1",
        "K1",
        2,
        date,
        direction,
        PartyIdentifier("UEN", "SUP-1"),
        buyer,
    )

    private val key = SourceKey(t1, "S1", "K1", 2)

    private fun run(c: RecordDocumentCommand): Pair<Harness, RecordedResult> {
        val h = Harness(d1)
        return h to h.service.record(c)
    }

    private fun nullCase(
        c: RecordDocumentCommand,
        asks: Int,
    ) {
        val (h, r) = run(c)
        h.lookups shouldBe emptyList()
        h.keys shouldBe listOf(key)
        h.asked.size shouldBe asks
        r shouldBe RecordedResult(d1, true, "R-$d1")
    }

    @Test
    fun `INV-AC-21385 SALE names the buyer and passes key and lookup`() {
        val (h, r) = run(command())
        h.keys shouldBe listOf(key)
        h.lookups shouldBe listOf(CounterpartyLookup("E1", "C1", "UEN", "BUY-1", date))
        h.asked shouldBe listOf(t1 to "E1")
        r shouldBe RecordedResult(d1, true, "R-$d1")
    }

    @Test
    fun `INV-AC-21385 PURCHASE names the supplier`() {
        val (h, _) = run(command(Direction.PURCHASE))
        h.keys shouldBe listOf(key)
        h.lookups shouldBe listOf(CounterpartyLookup("E1", "C1", "UEN", "SUP-1", date))
        h.asked.size shouldBe 1
    }

    @Test
    fun `INV-AC-21385 direction absent gives a null lookup and the port is not asked`() = nullCase(command(null), 0)

    @Test
    fun `INV-AC-21385 unknown constitution gives a null lookup after one port call`() =
        nullCase(command(entityRef = "E2"), 1)

    @Test
    fun `INV-AC-21385 blank scheme gives a null lookup and the port is not asked`() =
        nullCase(command(buyer = PartyIdentifier(" ", "BUY-1")), 0)

    @Test
    fun `INV-AC-21385 blank value gives a null lookup and the port is not asked`() =
        nullCase(command(buyer = PartyIdentifier("UEN", "")), 0)

    @Test
    fun `INV-AC-21385 builder receives the command and the Doc UUID and its text is the result`() {
        val c = command()
        val (h, r) = run(c)
        h.built shouldBe listOf(c to d1)
        r.result shouldBe "R-$d1"
        h.rows[key] shouldBe (d1 to "R-$d1")
    }
}
