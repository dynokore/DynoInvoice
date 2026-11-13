package com.dynokore.gst.canonical.adapters.clients

import com.dynokore.gst.canonical.application.document.CounterpartyLookup
import com.dynokore.gst.canonical.application.document.PartyRefAnswer
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class MasterDataCounterpartyDirectoryTest {
    private val lookup = CounterpartyLookup("E1", "COMPANY", "0195", "V1", LocalDate.of(2026, 10, 1))

    private fun answerOf(answer: MasterDataAnswer<ResolvedCounterparty>): PartyRefAnswer =
        MasterDataCounterpartyDirectory { _, _, _, _, _ -> answer }.partyRefOf(lookup)

    private fun ref(value: String?) = MasterDataAnswer.Answered(ResolvedCounterparty(value))

    @Test
    fun `INV-AC-21374 the five client answers map to Found, NotFound, NotFound, Unavailable, Unavailable`() {
        listOf(
            ref("CTP-118"),
            ref(null),
            MasterDataAnswer.Refused(404, "SRT-005"),
            MasterDataAnswer.Refused(400, "SRT-001"),
            MasterDataAnswer.Unavailable("down"),
        ).map { answerOf(it) } shouldBe
            listOf(
                PartyRefAnswer.Found("CTP-118"),
                PartyRefAnswer.NotFound,
                PartyRefAnswer.NotFound,
                PartyRefAnswer.Unavailable,
                PartyRefAnswer.Unavailable,
            )
    }

    @Test
    fun `INV-AC-21374 a blank or 41-character reference is Unavailable and a 40-character one is Found`() {
        listOf(ref(""), ref("  "), ref("x".repeat(41)), ref("x".repeat(40)), ref("x"))
            .map { answerOf(it) } shouldBe
            listOf(
                PartyRefAnswer.Unavailable,
                PartyRefAnswer.Unavailable,
                PartyRefAnswer.Unavailable,
                PartyRefAnswer.Found("x".repeat(40)),
                PartyRefAnswer.Found("x"),
            )
    }

    @Test
    fun `INV-AC-21374 the lookup fields reach the client in order`() {
        var seen: List<Any> = emptyList()
        MasterDataCounterpartyDirectory { a, b, c, d, e ->
            seen = listOf(a, b, c, d, e)
            ref("P")
        }.partyRefOf(lookup)
        seen shouldBe listOf("E1", "COMPANY", "0195", "V1", LocalDate.of(2026, 10, 1))
    }
}
