package com.dynokore.platform.rulepackkernel.fence

import com.dynokore.platform.rulepackkernel.BouncyCastlePackVerifier
import com.dynokore.platform.rulepackkernel.InMemoryPackRegistry
import com.dynokore.platform.rulepackkernel.JacksonPackContentValidator
import com.dynokore.platform.rulepackkernel.PackFixtures
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.TrustAnchor
import com.dynokore.platform.rulepackkernel.TrustAnchors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * INV-REQ-1166, INV-REQ-1009 (INV-AC-1235): the two-phase fence, with an in-memory coordinator and an
 * in-memory acknowledgement bus. The pack-holding services do not exist yet, so each participant here is a
 * fixture instance named after one.
 */
class FenceTest {
    private class MutableClock(private var now: Instant = Instant.parse("2026-10-11T00:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now

        fun advance(by: Duration) {
            now = now.plus(by)
        }
    }

    private val clock = MutableClock()
    private val keyPair = PackFixtures.signingKeyPair()
    private val certificate = PackFixtures.selfSignedCertificate(keyPair, "train-primary")
    private val anchors = TrustAnchors(listOf(TrustAnchor("primary", certificate.encoded)))
    private val artefact = PackFixtures.artefact(keyPair = keyPair, certificate = certificate)
    private val packId = PackId("code-catalogue-sg")
    private val version = PackVersionLabel("2026.10.1")

    /** The in-memory bus: every acknowledgement a participant emits is delivered to the fence. */
    private class Bus {
        val sent = mutableListOf<PackReadinessAcknowledgement>()
        var fence: ActivationFence? = null
        var failNext = false

        fun deliver(ack: PackReadinessAcknowledgement) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("outbox unavailable")
            }
            sent += ack
            fence?.record(ack)
        }
    }

    private val bus = Bus()
    private val registries = mutableMapOf<String, InMemoryPackRegistry>()
    private val armedCalls = mutableListOf<Pair<PackId, PackVersionLabel>>()

    private fun participant(
        instance: String,
        compiler: PackCompiler<String> = PackCompiler { m, _ -> CompileOutcome.Compiled("compiled-${m.version}") },
    ): FenceParticipant<String> {
        val registry = InMemoryPackRegistry().also { registries[instance] = it }
        return FenceParticipant(
            instance,
            BouncyCastlePackVerifier(),
            JacksonPackContentValidator(),
            compiler,
            anchors,
            bus::deliver,
            registry,
        )
    }

    private fun fence(vararg expected: String): ActivationFence {
        val arming = PackArming { id, v -> armedCalls += id to v }
        val fence = ActivationFence(packId, version, expected.toSet(), Duration.ofMinutes(5), clock, arming)
        bus.fence = fence
        return fence
    }

    private fun resolves(instance: String): Boolean {
        val onDate = LocalDate.parse("2026-10-02")
        return registries.getValue(instance).resolve(PackType("code-catalogue"), null, onDate) != null
    }

    private fun failure(ack: PackReadinessAcknowledgement): ReadinessFailure {
        val failed = assertInstanceOf(ReadinessOutcome.Failed::class.java, ack.outcome)
        return failed.reason
    }

    private val failingCompiler = PackCompiler<String> { _, _ -> CompileOutcome.Failed("schematron does not compile") }

    @Test
    fun `every holder loads and acknowledges, the fence arms, and only then is the pack usable`() {
        val holders = listOf("payload-svc", "tenancy-svc", "reconciliation-svc").map(::participant)
        val fence = fence("rules-svc", "payload-svc", "tenancy-svc", "reconciliation-svc")
        holders.forEach { it.onStaged(artefact) }

        holders.forEach {
            assertTrue(it.isHolding(packId, version))
            assertNull(it.armed(packId, version), "held, not in use")
        }
        assertEquals(setOf("rules-svc"), assertInstanceOf(FenceOutcome.Pending::class.java, fence.evaluate()).missing)
        assertTrue(holders.none { resolves(it.instance) })

        bus.deliver(PackReadinessAcknowledgement(packId, version, "rules-svc", ReadinessOutcome.Loaded))
        assertEquals(FenceOutcome.Armed, fence.evaluate())
        assertEquals(listOf(packId to version), armedCalls)
        holders.forEach { assertTrue(it.onArmed(packId, version)) }
        holders.forEach {
            assertEquals("compiled-2026.10.1", it.armed(packId, version))
            assertTrue(resolves(it.instance))
        }
    }

    @Test
    fun `one holder failing to load aborts the whole activation and nothing arms`() {
        val good = listOf("payload-svc", "tenancy-svc").map(::participant)
        val bad = participant("reconciliation-svc", failingCompiler)
        val fence = fence("payload-svc", "tenancy-svc", "reconciliation-svc")
        (good + bad).forEach { it.onStaged(artefact) }

        val aborted = assertInstanceOf(FenceOutcome.Aborted::class.java, fence.evaluate())
        assertEquals(ReadinessFailure.COMPILE_FAILED, aborted.failed.getValue("reconciliation-svc").reason)
        assertEquals("RUL-005", aborted.errorCode)
        assertTrue(armedCalls.isEmpty(), "no instance arms")
        (good + bad).forEach { it.onAborted(packId, version) }
        (good + bad).forEach {
            assertFalse(it.isHolding(packId, version))
            assertNull(it.armed(packId, version))
            assertFalse(resolves(it.instance))
        }
        assertEquals(aborted, fence.evaluate(), "the outcome is final")
    }

    @Test
    fun `an instance that never acknowledges aborts at the end of the window with nothing armed`() {
        val a = participant("payload-svc")
        val fence = fence("payload-svc", "tenancy-svc")
        a.onStaged(artefact)

        assertInstanceOf(FenceOutcome.Pending::class.java, fence.evaluate())
        clock.advance(Duration.ofMinutes(5))
        val aborted = assertInstanceOf(FenceOutcome.Aborted::class.java, fence.evaluate())
        assertEquals(setOf("tenancy-svc"), aborted.missing)
        assertTrue(armedCalls.isEmpty())
        assertNull(a.armed(packId, version))
    }

    @Test
    fun `an interrupted arming aborts entirely`() {
        val a = participant("payload-svc")
        val fence =
            ActivationFence(packId, version, setOf("payload-svc"), Duration.ofMinutes(5), clock) { _, _ ->
                throw IllegalStateException("registry write interrupted")
            }.also { bus.fence = it }
        a.onStaged(artefact)

        val aborted = assertInstanceOf(FenceOutcome.Aborted::class.java, fence.evaluate())
        assertTrue(aborted.armingFailed)
        assertNull(a.armed(packId, version))
    }

    @Test
    fun `a tampered artefact is not loaded and is acknowledged as failed`() {
        val holder = participant("payload-svc")
        val tampered =
            com.dynokore.platform.rulepackkernel.PackArtefact(
                artefact.manifest,
                artefact.checksums,
                artefact.signature,
                """{"rates":[{"code":"SR","rate":99}]}""".toByteArray(),
            )
        val ack = holder.onStaged(tampered)

        assertEquals(ReadinessFailure.VERIFICATION_FAILED, failure(ack))
        assertFalse(holder.isHolding(packId, version))
    }

    @Test
    fun `a pack carrying executable material is refused after verification`() {
        val holder = participant("payload-svc")
        val contents = """{"script":"x"}""".toByteArray()
        val executable = PackFixtures.artefact(contents = contents, keyPair = keyPair, certificate = certificate)
        val ack = holder.onStaged(executable)

        assertEquals(ReadinessFailure.CONTENT_REFUSED, failure(ack))
        assertEquals(packId, ack.packId)
        assertFalse(holder.isHolding(packId, version))
    }

    @Test
    fun `a compiler that throws is a failed acknowledgement, not an exception`() {
        val holder = participant("payload-svc") { _, _ -> throw IllegalStateException("boom") }
        val ack = holder.onStaged(artefact)
        assertEquals(ReadinessFailure.COMPILE_FAILED, failure(ack))
    }

    @Test
    fun `redelivery re-acknowledges without change, a different pack under the same identity conflicts`() {
        val holder = participant("payload-svc")
        assertEquals(ReadinessOutcome.Loaded, holder.onStaged(artefact).outcome)
        assertEquals(ReadinessOutcome.Loaded, holder.onStaged(artefact).outcome)
        assertEquals(2, bus.sent.size)

        val manifest = PackFixtures.manifestJson(effectiveFrom = "2026-11-01")
        val other = PackFixtures.artefact(manifest = manifest, keyPair = keyPair, certificate = certificate)
        assertEquals(ReadinessFailure.CONFLICTING_PACK, failure(holder.onStaged(other)))
        assertTrue(holder.isHolding(packId, version))
    }

    @Test
    fun `if the acknowledgement cannot be handed over the hold is withdrawn`() {
        val holder = participant("payload-svc")
        bus.failNext = true
        assertThrows(IllegalStateException::class.java) { holder.onStaged(artefact) }
        assertFalse(holder.isHolding(packId, version))
        assertNotNull(holder.onStaged(artefact))
        assertTrue(holder.isHolding(packId, version))
    }

    @Test
    fun `acknowledgements for another pack or an unexpected instance are ignored`() {
        val fence = fence("payload-svc")
        fence.record(PackReadinessAcknowledgement(PackId("other"), version, "payload-svc", ReadinessOutcome.Loaded))
        fence.record(PackReadinessAcknowledgement(packId, version, "stranger", ReadinessOutcome.Loaded))
        assertEquals(setOf("payload-svc"), assertInstanceOf(FenceOutcome.Pending::class.java, fence.evaluate()).missing)
    }
}
