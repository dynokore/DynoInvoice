package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.application.pack.AuthorRegulatoryPack
import com.dynokore.gst.controlPlane.application.pack.PackSigningKeys
import com.dynokore.gst.controlPlane.application.pack.PublishPack
import com.dynokore.gst.controlPlane.application.pack.SignPack
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
    ): ApplicationContextRunner {
        val secrets = mount.resolve("tenancy").also { Files.createDirectories(it) }
        Files.writeString(secrets.resolve("binding-key-current"), "test-installation-binding-key")
        return ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            // the deployment's IdentityProvider binding builds the real resolver; the composition needs only the port
            .withBean(
                EndUserPrincipalResolver::class.java,
                {
                    object : EndUserPrincipalResolver {
                        override fun resolve(authorization: String?): EndUserPrincipal? = null
                    }
                },
            )
            .withUserConfiguration(ControlPlaneApplication::class.java)
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://localhost:5432/controlplane",
                "spring.datasource.username=controlplane_runtime",
                "service-runtime.workload-identity.audience=control-plane-svc",
                "gst.service=control-plane-svc",
                "gst.instance-id=control-plane-svc-0",
                "service-runtime.pool.profile=minimal",
                "service-runtime.modules.schema=controlplane",
                "service-runtime.idempotency.schema=controlplane",
                "gst.bindings.secret-store.mount-root=$mount",
                "control-plane.signing.ref=KEY-REG-01",
                "control-plane.signing.alias=pack-signing",
                "control-plane.signing.pin-secret=signing/pin",
                "control-plane.signing.certificate-secret=signing/certificate",
                *extra,
            )
    }

    @Test
    fun `every use case and the signing port of the service are beans over the one application pool`(
        @TempDir mount: Path,
    ) {
        runner(mount).run { context ->
            context.startupFailure shouldBe null
            listOf(
                AuthorRegulatoryPack::class,
                SignPack::class,
                PublishPack::class,
                PackSigningKeys::class,
                TransactionRunner::class,
            ).forEach { context.getBean(it.java).shouldNotBeNull() }
            context.getBeanNamesForType(DataSource::class.java).toList() shouldBe listOf("dataSource")
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
    fun `the management port and the health groups of TPL-16 are configured`() {
        val yaml = checkNotNull(javaClass.getResourceAsStream("/application.yaml")).readBytes().toString(Charsets.UTF_8)
        yaml shouldContain "port: 8081"
        yaml shouldContain "ready.include: readinessState,vault,db,eventLog"
    }
}
