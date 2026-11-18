package com.dynokore.gst.reconciliation.application.workpaper

import com.dynokore.gst.reconciliation.application.run.RecordedRun
import com.dynokore.gst.reconciliation.application.run.RunBox
import com.dynokore.gst.reconciliation.application.run.RunCursor
import com.dynokore.gst.reconciliation.application.run.RunReader
import com.dynokore.gst.reconciliation.application.run.RunSnapshot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AttestWorkPaperTest {
    private val now = Instant.parse("2026-05-01T08:00:00Z")
    private val completed = Instant.parse("2026-04-02T00:00:00Z")
    private val ack = SoleAttester("U9", LocalDate.of(2026, 3, 1))

    private fun snapshot(
        runId: String,
        residual: String,
    ) = RunSnapshot(
        RecordedRun(runId, "E1", "P1", 2, completed),
        "F5",
        listOf(RunBox("1", BigDecimal("100.00"), BigDecimal("90.00"), BigDecimal("10.00"), null, BigDecimal(residual))),
        BigDecimal(residual),
        emptyList(),
        false,
    )

    private val snapshots = mapOf("R1" to snapshot("R1", "0.00"), "R2" to snapshot("R2", "10.00"))

    private val reader =
        object : RunReader {
            override fun latest(
                tenant: String,
                entityRef: String,
                periodRef: String,
            ): RunSnapshot? = null

            override fun page(
                tenant: String,
                entityRef: String,
                periodRef: String,
                limit: Int,
                after: RunCursor?,
            ): List<RecordedRun> = emptyList()

            override fun find(
                tenant: String,
                runId: String,
            ): RunSnapshot? = if (tenant == "T1") snapshots[runId] else null
        }

    private val noAnnotations =
        object : RunAnnotations {
            override fun latest(
                tenant: String,
                runId: String,
                box: String,
            ): StoredAnnotation? = null

            override fun all(
                tenant: String,
                runId: String,
            ): List<StoredAnnotation> = emptyList()

            override fun insert(
                tenant: String,
                stored: StoredAnnotation,
            ): Boolean = false
        }

    private val inserts = mutableListOf<Triple<String, String, StoredAttestation>>()
    private var loseRace = false
    private var winner: StoredAttestation? = null
    private val attestations =
        RunAttestations { t, r -> inserts.lastOrNull { it.first == t && it.second == r }?.third }
    private val writer =
        RunAttestationWriter { t, r, s ->
            if (loseRace) {
                winner?.let { inserts += Triple(t, r, it) }
                false
            } else {
                inserts += Triple(t, r, s)
                true
            }
        }

    private var preparer: String? = "U1"
    private var acknowledgement: SoleAttester? = null
    private var policyThrows = false
    private val policyCalls = mutableListOf<Triple<String, String, LocalDate>>()

    private val preparers = RunPreparers { _, _ -> preparer }
    private val policy =
        SingleAttestationPolicy { t, e, on ->
            policyCalls += Triple(t, e, on)
            if (policyThrows) error("policy unreadable")
            acknowledgement
        }

    private fun attestWith(clock: Clock) =
        AttestWorkPaper(
            reader,
            GetWorkPaper(reader, noAnnotations, attestations),
            preparers,
            policy,
            attestations,
            writer,
            clock,
        )

    private val attest = attestWith(Clock.fixed(now, ZoneOffset.UTC))

    private fun go(
        run: String = "R1",
        flag: Boolean = false,
        actor: String = "U2",
    ) = attest.execute("T1", run, flag, actor)

    @Test
    fun `INV-AC-24429 attest records the caller as approver with the clock instant once`() {
        val expected = StoredAttestation("U2", now, null)
        go() shouldBe AttestationResult.Attested(expected)
        inserts shouldBe listOf(Triple("T1", "R1", expected))
        policyCalls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24429 a repeat is replayed unchanged without a second insert`() {
        go()
        go(actor = "U3") shouldBe AttestationResult.Replayed(StoredAttestation("U2", now, null))
        inserts.size shouldBe 1
    }

    @Test
    fun `INV-AC-24429 a lost write race replays the stored attestation and none stored throws`() {
        loseRace = true
        shouldThrow<IllegalStateException> { go() }
        winner = StoredAttestation("U5", now, null)
        go() shouldBe AttestationResult.Replayed(StoredAttestation("U5", now, null))
    }

    @Test
    fun `INV-AC-24429 preparer without the flag is refused and nothing is written`() {
        go(actor = "U1") shouldBe AttestationResult.Refused(AttestationRefusal.SEPARATION_OF_DUTIES)
        inserts shouldBe emptyList()
        policyCalls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24429 preparer with the flag and no acknowledgement is refused`() {
        go(flag = true, actor = "U1") shouldBe AttestationResult.Refused(AttestationRefusal.SEPARATION_OF_DUTIES)
        inserts shouldBe emptyList()
        policyCalls shouldBe listOf(Triple("T1", "E1", LocalDate.of(2026, 5, 1)))
    }

    @Test
    fun `INV-AC-24429 the policy date is the date in the clock zone not UTC`() {
        val late = Instant.parse("2026-05-01T20:00:00Z")
        val sg = attestWith(Clock.fixed(late, ZoneOffset.ofHours(8)))
        sg.execute("T1", "R1", true, "U1") shouldBe AttestationResult.Refused(AttestationRefusal.SEPARATION_OF_DUTIES)
        policyCalls shouldBe listOf(Triple("T1", "E1", LocalDate.of(2026, 5, 2)))
    }

    @Test
    fun `INV-AC-24429 preparer with the flag and the acknowledgement attests with the sole attester`() {
        acknowledgement = ack
        val expected = StoredAttestation("U1", now, ack)
        go(flag = true, actor = "U1") shouldBe AttestationResult.Attested(expected)
        inserts shouldBe listOf(Triple("T1", "R1", expected))
    }

    @Test
    fun `INV-AC-24429 the policy is not asked when the actor differs from the preparer even with the flag`() {
        acknowledgement = ack
        go(flag = true, actor = "U2") shouldBe AttestationResult.Attested(StoredAttestation("U2", now, null))
        policyCalls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24429 an unexplained residual is refused before the preparer rule and nothing is written`() {
        go(run = "R2", actor = "U1") shouldBe AttestationResult.Refused(AttestationRefusal.UNEXPLAINED_VARIANCE)
        inserts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24429 an unknown run or another tenant is RunNotFound`() {
        go(run = "R404") shouldBe AttestationResult.RunNotFound
        attest.execute("T2", "R1", false, "U2") shouldBe AttestationResult.RunNotFound
        inserts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24429 a blank actor throws IllegalArgumentException`() {
        shouldThrow<IllegalArgumentException> { go(actor = " ") }
        inserts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24429 an unreadable preparer throws and nothing is written`() {
        preparer = null
        shouldThrow<IllegalStateException> { go() }
        inserts shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24429 a throwing policy propagates and nothing is written`() {
        policyThrows = true
        shouldThrow<IllegalStateException> { go(flag = true, actor = "U1") }
        inserts shouldBe emptyList()
    }
}
