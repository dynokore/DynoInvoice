package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.persistence.SupportWindowRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.TrainRepositoryJooq
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.Gate
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.GateRun
import com.dynokore.gst.controlPlane.domain.release.ReleaseRecord
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.SupportWindow
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Release trains, the support window and the release records, as the runtime login (CS-19). */
class ReleasePersistenceIT {
    private val clock = Clock.fixed(Instant.parse("2026-10-12T08:45:00Z"), ZoneOffset.UTC)
    private val dsl = PostgresDb.dsl()
    private val probe = PostgresDb.probe

    private fun has(
        table: String,
        privilege: String,
    ) = probe.fetchValue("select has_table_privilege(current_user, ?, ?)", "controlplane.$table", privilege) == true

    @Test
    fun `a train round-trips with both scan results, the classification and the accreditation check`() {
        val version = TrainVersion("t" + UUID.randomUUID().toString().take(8))
        val run = GateRun(Gate.SECURITY, GateOutcome.FAILED, setOf("CVE-1"), GateOutcome.FAILED, setOf("CVE-1"))
        val train =
            ReleaseTrain(
                version,
                TrainKind.PATCH,
                true,
                ReleaseRecord(
                    listOf(GateRun(Gate.CERTIFICATION, GateOutcome.PASSED), run),
                    true,
                    ActorId("rita"),
                    clock.instant(),
                ),
                AccreditationCheck(true, "ALERT-1", "TASK-1"),
            )
        val repository = TrainRepositoryJooq(dsl)
        repository.save(train)
        repository.find(version) shouldBe train
        repository.save(train.copy(kind = TrainKind.TRAIN)) // a published train is final: the repeat changes nothing
        repository.find(version) shouldBe train
    }

    @Test
    fun `a support window round-trips and a repeat keeps the original`() {
        val windowVersion = "w" + UUID.randomUUID().toString().take(8)
        val window = SupportWindow(windowVersion, listOf(TrainVersion("2026.9.0"), TrainVersion("2026.10.0")))
        val repository = SupportWindowRepositoryJooq(dsl, clock)
        repository.save(window)
        repository.find(windowVersion) shouldBe window
        repository.save(window.copy(trainVersions = emptyList()))
        repository.find(windowVersion) shouldBe window
    }

    @Test
    fun `the runtime login holds no DELETE on the release tables and no UPDATE on trains and windows`() {
        listOf("release_train", "support_window", "pack_release_record").forEach { has(it, "DELETE") shouldBe false }
        listOf("release_train", "support_window").forEach { has(it, "UPDATE") shouldBe false }
    }

    @Test
    fun `the release record of a pack keeps the corpus run and takes the staging run at publication`() {
        val flow = PackReleaseFlow()
        val ref = flow.author("2026.3")
        flow.sign(ref) shouldBe null
        flow.records
            .find(ref)!!
            .runs
            .map { it.gate } shouldBe listOf(Gate.CORPUS)
        flow.records.find(ref)!!.classifiedBy shouldBe ActorId("bob")
    }
}
