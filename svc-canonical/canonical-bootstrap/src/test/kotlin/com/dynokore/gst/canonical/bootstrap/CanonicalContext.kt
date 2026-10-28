package com.dynokore.gst.canonical.bootstrap

import com.dynokore.gst.canonical.adapters.CanonicalDb
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path

/** The service composed as TPL-18 writes it, over a TPL-16 configuration (application.yaml plus these overrides). */
object CanonicalContext {
    private fun secrets(mountRoot: Path) {
        Files.createDirectories(mountRoot.resolve("db"))
        Files.createDirectories(mountRoot.resolve("tenancy"))
        Files.writeString(mountRoot.resolve("db/canonical_runtime"), CanonicalDb.RUNTIME_PASSWORD)
        Files.writeString(mountRoot.resolve("tenancy/binding-key-current"), CanonicalDb.BINDING_SECRET)
    }

    /** No connection is opened at start: the pool is lazy (TPL-16). */
    fun offline(mountRoot: Path): ApplicationContextRunner {
        secrets(mountRoot)
        return base(mountRoot)
    }

    /** The service over the Testcontainers database as a servlet context, for the tests that call the controllers. */
    fun web(mountRoot: Path): WebApplicationContextRunner {
        secrets(mountRoot)
        return WebApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(CanonicalApplication::class.java)
            .withPropertyValues(*properties(mountRoot).toTypedArray(), "spring.datasource.url=${CanonicalDb.jdbcUrl}")
    }

    private fun properties(mountRoot: Path): List<String> =
        listOf(
            "spring.datasource.username=canonical_runtime",
            "service-runtime.workload-identity.audience=canonical-svc",
            "gst.service=canonical-svc",
            "gst.instance-id=canonical-svc-0",
            "service-runtime.pool.profile=minimal",
            "service-runtime.modules.schema=canonical",
            "service-runtime.idempotency.schema=canonical",
            "service-runtime.datasource.credential=db/canonical_runtime",
            "gst.bindings.secret-store.mount-root=$mountRoot",
        )

    private fun base(mountRoot: Path): ApplicationContextRunner =
        ApplicationContextRunner()
            // the real application.yaml (TPL-16), then the overrides below
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(CanonicalApplication::class.java)
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://localhost:5432/canonical",
                *properties(mountRoot).toTypedArray(),
            )

    /** Over the Testcontainers database; the login password and the binding key come from a mounted secret store. */
    fun online(mountRoot: Path): ApplicationContextRunner {
        secrets(mountRoot)
        return base(mountRoot).withPropertyValues("spring.datasource.url=${CanonicalDb.jdbcUrl}")
    }
}
