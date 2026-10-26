package com.dynokore.gst.submission.domain.filer

import com.dynokore.gst.submission.domain.filer.FilerOption.ALWAYS_CLIENT
import com.dynokore.gst.submission.domain.filer.FilerOption.DERIVED
import com.dynokore.gst.submission.domain.filer.FilerOption.EITHER_PARTY
import com.dynokore.gst.submission.domain.filer.Party.AGENT
import com.dynokore.gst.submission.domain.filer.Party.CLIENT
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FilerOfRecordTest {
    @Test
    fun `INV-AC-2256 under DERIVED the client is named before the grant and the agent after it`() {
        FilerOfRecord.decide(DERIVED, agentHoldsFileGstReturn = false).named shouldBe CLIENT
        FilerOfRecord.decide(DERIVED, agentHoldsFileGstReturn = true).named shouldBe AGENT
    }

    @Test
    fun `INV-AC-10445 the six combinations name the expected party`() {
        FilerOfRecord.decide(DERIVED, true) shouldBe FilerDecision(AGENT, false)
        FilerOfRecord.decide(DERIVED, false) shouldBe FilerDecision(CLIENT, false)
        FilerOfRecord.decide(ALWAYS_CLIENT, true) shouldBe FilerDecision(CLIENT, false)
        FilerOfRecord.decide(ALWAYS_CLIENT, false) shouldBe FilerDecision(CLIENT, false)
        FilerOfRecord.decide(EITHER_PARTY, true) shouldBe FilerDecision(AGENT, true)
        FilerOfRecord.decide(EITHER_PARTY, false) shouldBe FilerDecision(CLIENT, true)
    }

    @Test
    fun `INV-AC-10445 every option and grant value names a party and only EITHER_PARTY lets the other record`() {
        for (option in FilerOption.entries) {
            for (grant in listOf(true, false)) {
                val decision = FilerOfRecord.decide(option, grant)
                (decision.named == AGENT || decision.named == CLIENT) shouldBe true
                decision.eitherMayRecord shouldBe (option == EITHER_PARTY)
            }
        }
    }

    @Test
    fun `INV-AC-10444 the members are exactly the VP-11 option keys`() {
        FilerOption.entries.map { it.name } shouldContainExactlyInAnyOrder
            listOf("DERIVED", "ALWAYS_CLIENT", "EITHER_PARTY")
    }

    @Test
    fun `INV-AC-10444 fromKey accepts the three real keys`() {
        FilerOption.fromKey("DERIVED") shouldBe DERIVED
        FilerOption.fromKey("ALWAYS_CLIENT") shouldBe ALWAYS_CLIENT
        FilerOption.fromKey("EITHER_PARTY") shouldBe EITHER_PARTY
    }

    @Test
    fun `INV-AC-10444 fromKey refuses derived, ALWAYS_CLIENTS and the empty string`() {
        for (key in listOf("derived", "ALWAYS_CLIENTS", "", " DERIVED")) {
            assertThrows<IllegalArgumentException> { FilerOption.fromKey(key) }
        }
    }
}
