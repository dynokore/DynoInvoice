package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.TransportDb
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path

/** The service composed as TPL-18 writes it, over a TPL-16 configuration (application.yaml plus these overrides). */
object TransportContext {
    fun secrets(mountRoot: Path) {
        Files.createDirectories(mountRoot.resolve("db"))
        Files.createDirectories(mountRoot.resolve("tenancy"))
        Files.writeString(mountRoot.resolve("db/transport_runtime"), TransportDb.RUNTIME_PASSWORD)
        Files.writeString(mountRoot.resolve("tenancy/binding-key-current"), TransportDb.BINDING_SECRET)
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
            .withUserConfiguration(TransportApplication::class.java)
            .withPropertyValues(*propertyValues(mountRoot, "jdbc:postgresql://localhost:5432/transport").toTypedArray())

    /** The overrides of the test configuration; the database url is the caller's. */
    fun propertyValues(
        mountRoot: Path,
        jdbcUrl: String,
    ): List<String> =
        listOf(
            "spring.datasource.url=$jdbcUrl",
            "spring.datasource.username=transport_runtime",
            "service-runtime.workload-identity.audience=transport-svc",
            "gst.service=transport-svc",
            "gst.instance-id=transport-svc-0",
            "service-runtime.pool.profile=minimal",
            "service-runtime.modules.schema=transport",
            "service-runtime.idempotency.schema=transport",
            "service-runtime.datasource.credential=db/transport_runtime",
            "gst.bindings.secret-store.mount-root=$mountRoot",
            // the adapter configuration has no default (ApProperties): a sandbox with one adapter, never called
            "transport.ap.environment=SANDBOX",
            "transport.ap.sandbox-endpoint=http://localhost:1",
            "transport.ap.adapters[0].id=primary",
            "transport.ap.adapters[0].path=primary",
        )

    /** Over the Testcontainers database; the login password and the binding key come from a mounted secret store. */
    fun online(
        mountRoot: Path,
        vararg overrides: String,
    ): ApplicationContextRunner {
        secrets(mountRoot)
        return base(mountRoot).withPropertyValues("spring.datasource.url=${TransportDb.jdbcUrl}", *overrides)
    }
}
