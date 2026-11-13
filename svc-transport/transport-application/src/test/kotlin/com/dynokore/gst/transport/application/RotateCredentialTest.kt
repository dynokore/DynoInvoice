package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AdapterId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** INV-AC-18674: rotation with an overlap window; the vault is a test double that records every read. */
class RotateCredentialTest {
    private val a = AdapterId("A")
    private val c1 = CredentialRef("C1")
    private val c2 = CredentialRef("C2")
    private val t0 = Instant.parse("2026-11-13T00:00:00Z")
    private val window = Duration.ofMinutes(10)

    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant() = now
    }

    private class Vault(
        val held: Set<String>,
    ) : CredentialVault {
        val reads = mutableListOf<String>()

        override fun holds(ref: CredentialRef) = ref.value in held

        override fun read(ref: CredentialRef): Secret? {
            reads += ref.value
            return if (holds(ref)) Secret("secret-of-${ref.value}".toCharArray()) else null
        }
    }

    private class States(
        var state: AdapterCredentialState?,
    ) : AdapterCredentialStates {
        var saves = 0

        override fun load(adapter: AdapterId) = state

        override fun save(
            adapter: AdapterId,
            state: AdapterCredentialState,
        ) {
            saves++
            this.state = state
        }
    }

    private class Log : CredentialRotationLog {
        val audits = mutableListOf<CredentialRotationAudit>()

        override fun find(
            adapter: AdapterId,
            credentialId: CredentialRef,
        ) = audits.map { it.outcome }.firstOrNull { it.adapter == adapter && it.credentialId == credentialId }

        override fun record(audit: CredentialRotationAudit) {
            audits += audit
        }
    }

    private val clock = MutableClock(t0)
    private val vault = Vault(setOf("C1", "C2"))
    private val states = States(AdapterCredentialState(c1))
    private val log = Log()
    private val rotate = RotateCredential(states, vault, log, clock)
    private val inUse = CredentialInUse(states, vault, clock)

    @Test
    fun `INV-AC-18674 in-flight calls finish on C1, new calls use C2, C1 is refused after the overlap end`() {
        val inFlight = inUse.begin(a)
        val result = rotate.rotate(a, c2, window, "user-7")
        val rotated = result.shouldBeInstanceOf<RotateResult.Rotated>()
        rotated.repeat shouldBe false
        rotated.outcome.overlapEnd shouldBe t0.plus(window)

        clock.now = t0.plusSeconds(60)
        val inside = inUse.begin(a)
        inside.ref shouldBe c2
        inUse.secretFor(inside).toString() shouldBe "Secret(***)"
        String(inUse.secretFor(inFlight).reveal()) shouldBe "secret-of-C1"
        vault.reads shouldBe listOf("C2", "C1")

        clock.now = t0.plus(window)
        shouldThrow<CredentialRefused> { inUse.secretFor(CallCredential(a, c1)) }
        states.state shouldBe AdapterCredentialState(c2)
        log.audits.single().actingUserId shouldBe "user-7"
        log.audits
            .single()
            .toString()
            .contains("secret-of") shouldBe false
    }

    @Test
    fun `INV-AC-18674 a repeat of the adapter and C2 returns the original outcome and starts no second window`() {
        val first = rotate.rotate(a, c2, window, "user-7").shouldBeInstanceOf<RotateResult.Rotated>()
        clock.now = t0.plusSeconds(30)
        val saves = states.saves
        val again = rotate.rotate(a, c2, Duration.ofHours(5), "user-8").shouldBeInstanceOf<RotateResult.Rotated>()
        again.repeat shouldBe true
        again.outcome shouldBe first.outcome
        states.saves shouldBe saves
        log.audits.size shouldBe 1
    }

    @Test
    fun `INV-AC-18674 with no window supplied the use case refuses and nothing changes`() {
        for (missing in listOf(null, Duration.ZERO, Duration.ofSeconds(-1))) {
            rotate.rotate(a, c2, missing, "user-7") shouldBe RotateResult.Refused(RotationRefusal.NO_WINDOW)
        }
        states.state shouldBe AdapterCredentialState(c1)
        states.saves shouldBe 0
        log.audits.size shouldBe 0
    }

    @Test
    fun `INV-AC-18674 an unknown vault reference is refused and nothing changes`() {
        val refused = rotate.rotate(a, CredentialRef("C9"), window, "user-7")
        refused shouldBe RotateResult.Refused(RotationRefusal.UNKNOWN_REFERENCE)
        states.state shouldBe AdapterCredentialState(c1)
        log.audits.size shouldBe 0
        vault.reads shouldBe emptyList()
    }

    /** The look-alike that must fail: a rotation that withdraws C1 at once would fail the in-flight call. */
    @Test
    fun `INV-AC-18674 the previous reference is not withdrawn at once but kept until the overlap end`() {
        val inFlight = inUse.begin(a)
        rotate.rotate(a, c2, window, "user-7")
        states.state shouldBe AdapterCredentialState(c2, c1, t0.plus(window))
        String(inUse.secretFor(inFlight).reveal()) shouldBe "secret-of-C1"
    }

    @Test
    fun `INV-AC-18674 a rotation to the current reference or during an open window is refused`() {
        rotate.rotate(a, c1, window, "user-7") shouldBe RotateResult.Refused(RotationRefusal.SAME_AS_CURRENT)
        rotate.rotate(a, c2, window, "user-7")
        val during = RotateCredential(states, Vault(setOf("C1", "C2", "C3")), log, clock)
        val busy = during.rotate(a, CredentialRef("C3"), window, "u")
        busy shouldBe RotateResult.Refused(RotationRefusal.ROTATION_IN_PROGRESS)
    }
}
