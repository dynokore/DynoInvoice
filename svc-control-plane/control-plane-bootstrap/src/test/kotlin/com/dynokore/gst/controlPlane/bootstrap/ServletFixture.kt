package com.dynokore.gst.controlPlane.bootstrap

import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.DefaultResourceLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real application class started by SpringApplication in an embedded servlet container on a free port, for a test
 * that calls it over HTTP. The pool is lazy and no database is started: the tests refuse or intercept each request
 * before a handler runs, and count the connections the pool hands out (see `TestIdentity`).
 */
object ServletFixture {
    /** [configurations] are added to the application; [loader] is the class loader of the context. The caller closes it. */
    fun start(
        mountRoot: Path,
        configurations: List<Class<*>>,
        properties: Array<String>,
        loader: ClassLoader,
    ): ConfigurableApplicationContext {
        val secrets = mountRoot.resolve("tenancy").also { Files.createDirectories(it) }
        Files.writeString(secrets.resolve("binding-key-current"), "test-installation-binding-key")
        return SpringApplicationBuilder(ControlPlaneApplication::class.java, *configurations.toTypedArray())
            .web(WebApplicationType.SERVLET)
            .resourceLoader(DefaultResourceLoader(loader))
            .properties(
                "spring.datasource.url=jdbc:postgresql://localhost:5432/controlplane",
                "spring.datasource.username=controlplane_runtime",
                "service-runtime.workload-identity.audience=control-plane-svc",
                "gst.service=control-plane-svc",
                "gst.instance-id=control-plane-svc-0",
                "service-runtime.pool.profile=minimal",
                "service-runtime.modules.schema=controlplane",
                "service-runtime.idempotency.schema=controlplane",
                "service-runtime.event-loops.enabled=false",
                "spring.flyway.enabled=false",
                "gst.bindings.secret-store.mount-root=$mountRoot",
                "control-plane.tenancy.base-url=http://tenancy-svc.invalid",
                "control-plane.signing.ref=KEY-REG-01",
                "control-plane.signing.alias=pack-signing",
                "control-plane.signing.pin-secret=signing/pin",
                "control-plane.signing.certificate-secret=signing/certificate",
                "server.port=0",
                *properties,
            ).run()
    }
}
