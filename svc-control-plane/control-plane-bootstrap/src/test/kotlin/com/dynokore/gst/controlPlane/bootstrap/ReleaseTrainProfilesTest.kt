package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.application.release.PublishTrain
import com.dynokore.gst.controlPlane.application.release.PublishTrainCommand
import com.dynokore.gst.controlPlane.application.release.ReleasePermission
import com.dynokore.gst.controlPlane.application.release.SetSupportWindow
import com.dynokore.gst.controlPlane.application.release.SetSupportWindowCommand
import com.dynokore.gst.controlPlane.application.release.TrainEvidenceSource
import com.dynokore.gst.controlPlane.application.release.TrainRepository
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.Certification
import com.dynokore.gst.controlPlane.domain.release.GateOutcome
import com.dynokore.gst.controlPlane.domain.release.Observation
import com.dynokore.gst.controlPlane.domain.release.ReferenceEnvironment
import com.dynokore.gst.controlPlane.domain.release.RegenerationObservation
import com.dynokore.gst.controlPlane.domain.release.ReleaseTrain
import com.dynokore.gst.controlPlane.domain.release.SupportWindow
import com.dynokore.gst.controlPlane.domain.release.TrainEvidence
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import java.nio.file.Path

/**
 * The train gate of publishTrain over the composed application, in the profiles `test`, `saas` and `private`
 * (INV-AC-1943). The beans are the ones `main` composes; the train repository is memory and the stubs stand in for the
 * ports whose production bindings are other tasks. Each probe is a separate test per profile.
 */
class ReleaseTrainProfilesTest {
    @TempDir
    lateinit var mount: Path

    private val request = RequestContext("corr", "cause")
    private val version = TrainVersion("2026.10.0")
    private val command = PublishTrainCommand(ActorId("rita"), version, TrainKind.TRAIN, false, false, request)
    private val corpus =
        mapOf(
            com.dynokore.gst.controlPlane.domain.release
                .DocumentId("D1") to "SR",
        )

    private fun evidenceOf(vararg passed: ReferenceEnvironment) =
        TrainEvidence(
            Observation(GateOutcome.PASSED),
            Observation(GateOutcome.PASSED),
            RegenerationObservation(corpus, corpus),
            Certification(passed.associateWith { GateOutcome.PASSED }),
        )

    private fun everyProfile(
        evidence: TrainEvidence?,
        permissions: OperatorPermissions?,
        probe: (AssertableApplicationContext) -> Unit,
    ): List<DynamicTest> =
        listOf("test", "saas", "private").map { profile ->
            DynamicTest.dynamicTest(profile) {
                val saved = LinkedHashMap<TrainVersion, ReleaseTrain>()
                val repository =
                    object : TrainRepository {
                        override fun find(version: TrainVersion) = saved[version]

                        override fun save(train: ReleaseTrain) {
                            saved[train.version] = train
                        }
                    }
                var runner =
                    controlPlaneRunner(mount, "spring.profiles.active=$profile")
                        .withAllowBeanDefinitionOverriding(true)
                        .withBean("trainRepository", TrainRepository::class.java, { repository })
                if (evidence != null) {
                    runner =
                        runner.withBean("trainEvidenceSource", TrainEvidenceSource::class.java, {
                            TrainEvidenceSource { evidence }
                        })
                }
                if (permissions != null) {
                    runner = runner.withBean("operatorPermissions", OperatorPermissions::class.java, { permissions })
                }
                runner.run { context ->
                    context.startupFailure shouldBe null
                    probe(context)
                }
            }
        }

    private val holdsPublish = OperatorPermissions { actor, key -> actor == "rita" && key == ReleasePermission.PUBLISH }

    private fun refusalOf(context: AssertableApplicationContext): String? {
        val publish = context.getBean(PublishTrain::class.java)
        return publish.unmetPrecondition(command, publish.load(command))
    }

    @TestFactory
    fun `INV-AC-1943 the default binding refuses a train that a stub would certify`() =
        everyProfile(evidence = null, permissions = holdsPublish) { context ->
            Refusal.parse(refusalOf(context).shouldNotBeNull()).code shouldBe Refusal.NO_GATE_EVIDENCE
        }

    @TestFactory
    fun `INV-AC-1943 a stub with SAAS passed and PRIVATE_MINIMAL absent refuses`() =
        everyProfile(evidenceOf(ReferenceEnvironment.SAAS), holdsPublish) { context ->
            Refusal.parse(refusalOf(context).shouldNotBeNull()).code shouldBe Refusal.GATE_FAILED
        }

    @TestFactory
    fun `INV-AC-1943 a stub with both environments passed publishes`() =
        everyProfile(evidenceOf(*ReferenceEnvironment.entries.toTypedArray()), holdsPublish) { context ->
            val publish = context.getBean(PublishTrain::class.java)
            publish.permits("rita", command) shouldBe true
            refusalOf(context) shouldBe null
            publish
                .perform(command, null)
                .record.classifiedBy.value shouldBe "rita"
        }

    @TestFactory
    fun `the default binding of the permission port gives no permission for both release operations`() =
        everyProfile(evidence = null, permissions = null) { context ->
            context.getBean(PublishTrain::class.java).permits("rita", command) shouldBe false
            val window = SetSupportWindowCommand(ActorId("rita"), SupportWindow("2026.2", listOf(version)), request)
            context.getBean(SetSupportWindow::class.java).permits("rita", window) shouldBe false
        }
}
