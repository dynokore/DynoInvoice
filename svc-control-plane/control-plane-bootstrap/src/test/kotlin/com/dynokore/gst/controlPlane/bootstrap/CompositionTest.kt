package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.http.ReleasesController
import com.dynokore.gst.controlPlane.adapters.stage.PackStage
import com.dynokore.gst.controlPlane.application.commercial.GetLicence
import com.dynokore.gst.controlPlane.application.commercial.GetTenantProvisioningRequest
import com.dynokore.gst.controlPlane.application.commercial.IssueLicence
import com.dynokore.gst.controlPlane.application.commercial.ManagePlan
import com.dynokore.gst.controlPlane.application.commercial.ProvisionTenant
import com.dynokore.gst.controlPlane.application.commercial.RecordQualification
import com.dynokore.gst.controlPlane.application.pack.AuthorRegulatoryPack
import com.dynokore.gst.controlPlane.application.pack.PackSigningKeys
import com.dynokore.gst.controlPlane.application.pack.PublishPack
import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.pack.SignPack
import com.dynokore.gst.controlPlane.application.release.AccreditationTasks
import com.dynokore.gst.controlPlane.application.release.PublishTrain
import com.dynokore.gst.controlPlane.application.release.PublishTrainCommand
import com.dynokore.gst.controlPlane.application.release.SetSupportWindow
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.gst.controlPlane.domain.release.TrainKind
import com.dynokore.gst.controlPlane.domain.release.TrainVersion
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * TPL-16 and TPL-18 (P109): the real application class, composed the way `main` composes it (component scan, the two
 * `@Import` roots, auto-configuration), over a mounted-file SecretStore. The pool is lazy, so no database is needed.
 */
class CompositionTest {
    private fun runner(
        mount: Path,
        vararg extra: String,
    ) = controlPlaneRunner(mount, *extra)

    @Test
    fun `every use case and the signing port of the service are beans over the one application pool`(
        @TempDir mount: Path,
    ) {
        runner(mount).run { context ->
            context.startupFailure shouldBe null
            listOf(
                AuthorRegulatoryPack::class,
                SignPack::class,
                ManagePlan::class,
                RecordQualification::class,
                ProvisionTenant::class,
                GetTenantProvisioningRequest::class,
                IssueLicence::class,
                GetLicence::class,
                PublishPack::class,
                PublishTrain::class,
                SetSupportWindow::class,
                PackStage::class,
                ReleasesController::class,
                PackSigningKeys::class,
                TransactionRunner::class,
            ).forEach { context.getBean(it.java).shouldNotBeNull() }
            context.getBeanNamesForType(DataSource::class.java).toList() shouldBe listOf("dataSource")
        }
    }

    @Test
    fun `publishTrain with the gate evidence unbound is refused and the accreditation task is unbound`(
        @TempDir mount: Path,
    ) {
        runner(mount).run { context ->
            context.startupFailure shouldBe null
            val publish = context.getBean(PublishTrain::class.java)
            val command =
                PublishTrainCommand(
                    ActorId("rita"),
                    TrainVersion("2026.10.0"),
                    TrainKind.TRAIN,
                    false,
                    true,
                    RequestContext("corr", "cause"),
                )
            Refusal.parse(checkNotNull(publish.unmetPrecondition(command, null))).code shouldBe Refusal.NO_GATE_EVIDENCE
            context.getBean(AccreditationTasks::class.java).open(TrainVersion("2026.10.0")) shouldBe
                AccreditationCheck(true, null, null)
        }
    }

    @Test
    fun `a software key store outside the profile test stops the start-up and names the setting`(
        @TempDir mount: Path,
    ) {
        runner(mount, "control-plane.signing.key-store-type=PKCS12").run { context ->
            val failure = generateSequence<Throwable>(context.startupFailure.shouldNotBeNull()) { it.cause }
            failure.joinToString { it.message.orEmpty() } shouldContain "control-plane.signing.key-store-type"
        }
    }

    @Test
    fun `the management port of TPL-16 is configured`() {
        val yaml = checkNotNull(javaClass.getResourceAsStream("/application.yaml")).readBytes().toString(Charsets.UTF_8)
        yaml shouldContain "port: 8081"
    }

    @Test
    fun `the ready group is the loaded key and reports exactly the components named in it`(
        @TempDir mount: Path,
    ) {
        runner(mount).run { context ->
            context.startupFailure shouldBe null
            val key = context.environment.getProperty(READY_KEY).shouldNotBeNull()
            key shouldBe "readinessState,vault,db,eventLoops"
            val ready = context.getBean(HealthEndpoint::class.java).healthForPath("ready")
            ready.shouldNotBeNull()
            checkNotNull((ready as CompositeHealthDescriptor).components).keys shouldBe key.split(",").toSet()
        }
    }

    private companion object {
        const val READY_KEY = "management.endpoint.health.group.ready.include"
    }

    @Test
    fun `the pack stage job is a bean of the service and registers on the one job runtime`(
        @TempDir mount: Path,
    ) {
        runner(mount).run { context ->
            context.startupFailure shouldBe null
            val job = context.getBean(PackStageJob::class.java)
            job.definition.name shouldBe PackStageJob.NAME
            job.definition.ownerService shouldBe "control-plane-svc"
            context.getBeansOfType(JobRuntime::class.java).size shouldBe 1
            context
                .getBean(JobRuntime::class.java)
                .registered()
                .map { it.name }
                .toSet() shouldBe setOf("idempotency-key-purge", "pack-stage")
        }
    }
}
