package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.DefaultResourceLoader
import java.nio.file.Files
import java.nio.file.Path

/** The service composed as TPL-18 writes it, over a TPL-16 configuration (application.yaml plus these overrides). */
object EvidenceContext {
    private fun secrets(mountRoot: Path) {
        Files.createDirectories(mountRoot.resolve("db"))
        Files.createDirectories(mountRoot.resolve("tenancy"))
        Files.writeString(mountRoot.resolve("db/evidence_runtime"), EvidenceDb.RUNTIME_PASSWORD)
        Files.writeString(mountRoot.resolve("tenancy/binding-key-current"), EvidenceDb.BINDING_SECRET)
    }

    /** No connection is opened at start: the pool is lazy (TPL-16). */
    fun offline(mountRoot: Path): ApplicationContextRunner {
        secrets(mountRoot)
        return base(mountRoot)
    }

    private fun base(mountRoot: Path): ApplicationContextRunner =
        ApplicationContextRunner()
            // the real application.yaml (TPL-16), then the overrides below
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(EvidenceApplication::class.java)
            .withPropertyValues(*overrides(mountRoot))

    private fun overrides(mountRoot: Path): Array<String> =
        arrayOf(
            "spring.datasource.url=jdbc:postgresql://localhost:5432/evidence",
            "spring.datasource.username=evidence_runtime",
            "service-runtime.workload-identity.audience=evidence-svc",
            "gst.service=evidence-svc",
            "gst.instance-id=evidence-svc-0",
            "service-runtime.pool.profile=minimal",
            "service-runtime.modules.schema=evidence",
            "service-runtime.idempotency.schema=evidence",
            "service-runtime.datasource.credential=db/evidence_runtime",
            "gst.bindings.secret-store.mount-root=$mountRoot",
        )

    /**
     * As [online], started by SpringApplication in an embedded servlet container on a free port, for a test that
     * calls the service over HTTP. The caller closes the context. [loader] is the class loader of the context.
     */
    fun startServlet(
        mountRoot: Path,
        configurations: List<Class<*>>,
        properties: Array<String>,
        loader: ClassLoader,
    ): ConfigurableApplicationContext {
        secrets(mountRoot)
        return SpringApplicationBuilder(EvidenceApplication::class.java, *configurations.toTypedArray())
            .web(WebApplicationType.SERVLET)
            .resourceLoader(DefaultResourceLoader(loader))
            .properties(
                *overrides(mountRoot),
                "spring.datasource.url=${EvidenceDb.jdbcUrl}",
                "server.port=0",
                *properties,
            ).run()
    }

    /** Over the Testcontainers database; the login password and the binding key come from a mounted secret store. */
    fun online(
        mountRoot: Path,
        version: EvidenceDb.Version = EvidenceDb.Version.PG18,
    ): ApplicationContextRunner {
        secrets(mountRoot)
        return base(mountRoot).withPropertyValues("spring.datasource.url=${version.jdbcUrl}")
    }
}
