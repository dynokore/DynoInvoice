package com.dynokore.gst.ingestion.domain.fallback

import com.dynokore.gst.ingestion.domain.fallback.FallbackDecision.Fallback
import com.dynokore.gst.ingestion.domain.fallback.FallbackDecision.NotFallback
import com.dynokore.gst.ingestion.domain.fallback.FallbackDecision.Refused
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class RelayFallbackDetectionTest {
    private val checks =
        listOf(
            AfterTheFactCheck.VALIDATION_LAYER_1,
            AfterTheFactCheck.VALIDATION_LAYER_2,
            AfterTheFactCheck.WRONGFUL_GST_CHARGE,
        )

    private fun decide(
        posted: Boolean,
        relay: Boolean,
        mode: IssuanceMode?,
    ) = RelayFallbackDetection.decide(FallbackInput(posted, relay, mode))

    @Test
    fun `INV-AC-1332 posted true relay false RELAY is a MODE_RELAY fallback and repeats equal`() {
        val expected = Fallback(FallbackReason.MODE_RELAY, checks, FindingSeverity.WARN)
        decide(true, false, IssuanceMode.RELAY) shouldBe expected
        decide(true, false, IssuanceMode.RELAY) shouldBe expected
    }

    @Test
    fun `INV-AC-1332 posted true relay false null mode is a MODE_UNKNOWN fallback`() {
        decide(true, false, null) shouldBe Fallback(FallbackReason.MODE_UNKNOWN, checks, FindingSeverity.WARN)
    }

    @Test
    fun `INV-AC-1332 table of all 16 combinations`() {
        val relay = Fallback(FallbackReason.MODE_RELAY, checks, FindingSeverity.WARN)
        val unknown = Fallback(FallbackReason.MODE_UNKNOWN, checks, FindingSeverity.WARN)
        val notRelay = NotFallback(NotFallbackReason.MODE_NOT_RELAY)
        val noNetwork = NotFallback(NotFallbackReason.NO_NETWORK_ISSUANCE)
        val relayPath = NotFallback(NotFallbackReason.RELAY_PATH)
        val refused = Refused(RefusalReason.CONTRADICTORY_MARKERS)
        val modes = listOf(IssuanceMode.RELAY, IssuanceMode.OBSERVE, IssuanceMode.NONE, null)
        val table: Map<Triple<Boolean, Boolean, IssuanceMode?>, FallbackDecision> =
            mapOf(
                Triple(false, false, IssuanceMode.RELAY) to noNetwork,
                Triple(false, false, IssuanceMode.OBSERVE) to noNetwork,
                Triple(false, false, IssuanceMode.NONE) to noNetwork,
                Triple(false, false, null) to noNetwork,
                Triple(false, true, IssuanceMode.RELAY) to relayPath,
                Triple(false, true, IssuanceMode.OBSERVE) to relayPath,
                Triple(false, true, IssuanceMode.NONE) to relayPath,
                Triple(false, true, null) to relayPath,
                Triple(true, false, IssuanceMode.RELAY) to relay,
                Triple(true, false, IssuanceMode.OBSERVE) to notRelay,
                Triple(true, false, IssuanceMode.NONE) to notRelay,
                Triple(true, false, null) to unknown,
                Triple(true, true, IssuanceMode.RELAY) to refused,
                Triple(true, true, IssuanceMode.OBSERVE) to refused,
                Triple(true, true, IssuanceMode.NONE) to refused,
                Triple(true, true, null) to refused,
            )
        for (posted in listOf(false, true)) {
            for (relayFlag in listOf(false, true)) {
                for (mode in modes) {
                    val key = Triple(posted, relayFlag, mode)
                    val expected = table[key] ?: error("combination missing from the table: $key")
                    decide(posted, relayFlag, mode) shouldBe expected
                }
            }
        }
        table.size shouldBe 16
    }

    @Test
    fun `INV-AC-1332 both markers true is Refused never Fallback for every mode`() {
        for (mode in listOf(IssuanceMode.RELAY, IssuanceMode.OBSERVE, IssuanceMode.NONE, null)) {
            decide(true, true, mode) shouldBe Refused(RefusalReason.CONTRADICTORY_MARKERS)
        }
    }

    @Test
    fun `INV-AC-1332 OBSERVE and NONE are not a fallback`() {
        decide(true, false, IssuanceMode.OBSERVE) shouldBe NotFallback(NotFallbackReason.MODE_NOT_RELAY)
        decide(true, false, IssuanceMode.NONE) shouldBe NotFallback(NotFallbackReason.MODE_NOT_RELAY)
    }

    @Test
    fun `INV-AC-1332 no network issuance marker is not a fallback even in RELAY`() {
        decide(false, false, IssuanceMode.RELAY) shouldBe NotFallback(NotFallbackReason.NO_NETWORK_ISSUANCE)
    }

    @Test
    fun `INV-AC-1332 relay path posting is not a fallback`() {
        decide(false, true, IssuanceMode.RELAY) shouldBe NotFallback(NotFallbackReason.RELAY_PATH)
    }

    @Test
    fun `INV-AC-1332 severity of every Fallback is WARN and the enum has one member`() {
        val modes = listOf(IssuanceMode.RELAY, null)
        for (mode in modes) {
            decide(true, false, mode).shouldBeInstanceOf<Fallback>().severity shouldBe FindingSeverity.WARN
        }
        FindingSeverity.entries.map { it.name } shouldBe listOf("WARN")
    }

    @Test
    fun `INV-AC-1332 IssuanceMode pins the three tenancy contract names`() {
        IssuanceMode.entries.map { it.name } shouldBe listOf("RELAY", "OBSERVE", "NONE")
    }

    @Test
    fun `INV-AC-1332 decide takes exactly one FallbackInput parameter`() {
        val methods = RelayFallbackDetection::class.java.declaredMethods.filter { it.name == "decide" }
        methods.size shouldBe 1
        methods.single().parameterTypes.toList() shouldBe listOf(FallbackInput::class.java)
    }
}
