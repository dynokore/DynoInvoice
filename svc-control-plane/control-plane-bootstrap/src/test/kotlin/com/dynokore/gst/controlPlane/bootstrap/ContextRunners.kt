package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path

/** The real application class composed the way `main` composes it, over a mounted-file SecretStore (TPL-18). */
fun controlPlaneRunner(
    mount: Path,
    vararg extra: String,
): ApplicationContextRunner {
    val secrets = mount.resolve("tenancy").also { Files.createDirectories(it) }
    Files.writeString(secrets.resolve("binding-key-current"), "test-installation-binding-key")
    return ApplicationContextRunner()
        .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
        // the deployment's IdentityProvider binding builds the real resolver; the composition needs only the port
        .withBean(EndUserPrincipalResolver::class.java, { EndUserPrincipalResolver { null } })
        // application.yaml is the service's own configuration (platform-scoped tables, management port)
        .withInitializer(ConfigDataApplicationContextInitializer())
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
            "control-plane.tenancy.base-url=http://tenancy-svc.invalid",
            "control-plane.signing.ref=KEY-REG-01",
            "control-plane.signing.alias=pack-signing",
            "control-plane.signing.pin-secret=signing/pin",
            "control-plane.signing.certificate-secret=signing/certificate",
            *extra,
        )
}
