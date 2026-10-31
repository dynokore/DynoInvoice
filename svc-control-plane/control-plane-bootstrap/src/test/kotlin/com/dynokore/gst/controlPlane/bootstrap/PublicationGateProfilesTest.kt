package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.stage.PackPipeline
import com.dynokore.gst.controlPlane.adapters.stage.PackStage
import com.dynokore.gst.controlPlane.adapters.stage.UnboundPackPipeline
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.PackRepository
import com.dynokore.gst.controlPlane.application.pack.PublishPack
import com.dynokore.gst.controlPlane.application.pack.PublishPackCommand
import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.pack.StagingEvidenceReader
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.pack.MinEngineVersion
import com.dynokore.gst.controlPlane.domain.pack.PackContents
import com.dynokore.gst.controlPlane.domain.pack.PackRef
import com.dynokore.gst.controlPlane.domain.pack.PackType
import com.dynokore.gst.controlPlane.domain.pack.PackVersion
import com.dynokore.gst.controlPlane.domain.pack.RegulatoryPack
import com.dynokore.gst.controlPlane.domain.pack.SignOutcome
import com.dynokore.gst.controlPlane.domain.pack.StagingEvidence
import com.dynokore.gst.controlPlane.domain.pack.StagingResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate

/**
 * The staging gate of publishPack over the composed application (coordinator ruling INV-Q-4878), in the profiles
 * `test`, `saas` and `private`. The beans are the ones `main` composes; only the database-backed ports are replaced by
 * memory, so the gate runs without a database. Each probe is a separate test per profile; none is waived by a profile
 * or a role.
 */
class PublicationGateProfilesTest {
    @TempDir
    lateinit var mount: Path

    private val request = RequestContext("corr", "cause")
    private val records = ArrayList<StagingEvidence>()
    private val store = LinkedHashMap<PackRef, RegulatoryPack>()

    private fun signed(
        version: String,
        contents: String = """{"rules":[]}""",
    ) = (
        RegulatoryPack
            .draft(
                PackType("SCOPE"),
                PackVersion(version),
                listOf("IRAS e-Tax Guide"),
                ActorId("alice"),
                LocalDate.parse("2026-11-01"),
                PackContents(contents),
            ).sign(
                ActorId("bob"),
                "KEY-REG-01",
                MinEngineVersion("2026.10.0"),
                false,
                Instant.parse("2026-10-12T08:30:00Z"),
            )
            as SignOutcome.Signed
    ).pack.also { store[it.ref] = it }

    private fun everyProfile(probe: (AssertableApplicationContext, String) -> Unit): List<DynamicTest> =
        listOf("test", "saas", "private").map { profile ->
            DynamicTest.dynamicTest(profile) {
                val repository =
                    object : PackRepository {
                        override fun find(ref: PackRef) = store[ref]

                        override fun save(pack: RegulatoryPack) {
                            store[pack.ref] = pack
                        }

                        override fun latestPublished(type: PackType): PackRef? = null
                    }
                controlPlaneRunner(mount, "spring.profiles.active=$profile")
                    .withAllowBeanDefinitionOverriding(true)
                    .withBean("packRepository", PackRepository::class.java, { repository })
                    .withBean("stagingEvidenceReader", StagingEvidenceReader::class.java, {
                        StagingEvidenceReader { ref -> records.filter { it.packRef == ref } }
                    })
                    .run { context ->
                        context.startupFailure shouldBe null
                        probe(context, profile)
                    }
            }
        }

    private fun refusalOf(
        context: AssertableApplicationContext,
        ref: PackRef,
    ): String? {
        val publish = context.getBean(PublishPack::class.java)
        val command = PublishPackCommand(ActorId("bob"), ref, request)
        return publish.unmetPrecondition(command, publish.load(command))
    }

    private fun hashOf(
        context: AssertableApplicationContext,
        pack: RegulatoryPack,
    ) = context.getBean(PublishPorts::class.java).hasher.hash(pack)

    @TestFactory
    fun `the stage is unbound so there is no pipeline binding and publishPack is refused`() =
        everyProfile { context, _ ->
            context.getBean(PackPipeline::class.java) shouldBe UnboundPackPipeline
            val pack = signed("2026.3")
            val outcome = context.getBean(PackStage::class.java).run(pack.ref)
            outcome.recorded shouldBe false
            Refusal.parse(refusalOf(context, pack.ref).shouldNotBeNull()).code shouldBe Refusal.NO_STAGING_RECORD
        }

    @TestFactory
    fun `a record with result FAILED is refused`() =
        everyProfile { context, _ ->
            val pack = signed("2026.3")
            records += StagingEvidence(pack.ref, StagingResult.FAILED, hashOf(context, pack))
            Refusal.parse(refusalOf(context, pack.ref).shouldNotBeNull()).code shouldBe Refusal.STAGING_NOT_PASSED
        }

    @TestFactory
    fun `no record for the version is refused`() =
        everyProfile { context, _ ->
            val pack = signed("2026.3")
            Refusal.parse(refusalOf(context, pack.ref).shouldNotBeNull()).code shouldBe Refusal.NO_STAGING_RECORD
        }

    @TestFactory
    fun `a record of an earlier version of the same pack is refused`() =
        everyProfile { context, _ ->
            val earlier = signed("2026.2")
            val pack = signed("2026.3")
            records += StagingEvidence(earlier.ref, StagingResult.PASSED, hashOf(context, earlier))
            Refusal.parse(refusalOf(context, pack.ref).shouldNotBeNull()).code shouldBe Refusal.NO_STAGING_RECORD
        }

    @TestFactory
    fun `the content changed after the stage passed, same version number, is refused with a hash mismatch`() =
        everyProfile { context, _ ->
            val staged = signed("2026.3")
            records += StagingEvidence(staged.ref, StagingResult.PASSED, hashOf(context, staged))
            signed("2026.3", contents = """{"rules":[{"code":"SR"}]}""")
            Refusal.parse(refusalOf(context, staged.ref).shouldNotBeNull()).code shouldBe Refusal.STAGING_HASH_MISMATCH
        }

    @TestFactory
    fun `no profile or role waives the refusal`() =
        listOf(
            "test",
            "saas",
            "private",
            "prod",
            "dev",
            "local",
            "ci",
            "admin",
            "release.manager",
            "regulatory.approver",
        ).let { profiles ->
            val allRoles = OperatorRoles { _, _ -> true }
            profiles.map { profile ->
                DynamicTest.dynamicTest(profile) {
                    val pack = signed("2026.3")
                    controlPlaneRunner(mount, "spring.profiles.active=$profile")
                        .withAllowBeanDefinitionOverriding(true)
                        .withBean("operatorRoles", OperatorRoles::class.java, { allRoles })
                        .withBean("packRepository", PackRepository::class.java, {
                            object : PackRepository {
                                override fun find(ref: PackRef) = store[ref]

                                override fun save(pack: RegulatoryPack) = Unit

                                override fun latestPublished(type: PackType): PackRef? = null
                            }
                        })
                        .withBean("stagingEvidenceReader", StagingEvidenceReader::class.java, {
                            StagingEvidenceReader { emptyList() }
                        })
                        .withPropertyValues(
                            "control-plane.stage.enabled=false",
                            "control-plane.staging.waive=true",
                        ).run { context ->
                            context.startupFailure shouldBe null
                            val publish = context.getBean(PublishPack::class.java)
                            val command = PublishPackCommand(ActorId("bob"), pack.ref, request)
                            publish.permits("bob", command) shouldBe true
                            Refusal.parse(refusalOf(context, pack.ref).shouldNotBeNull()).code shouldBe
                                Refusal.NO_STAGING_RECORD
                        }
                }
            }
        }
}
