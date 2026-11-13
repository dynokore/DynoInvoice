package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path

/** The service composed as TPL-18 writes it, over a TPL-16 configuration (application.yaml plus these overrides). */
object ConsoleContext {
    const val BINDING_SECRET = "console-test-binding-key-0001"
    private const val OFFLINE_URL = "jdbc:postgresql://localhost:5432/console"

    /** The override properties; [extra] come last and win. */
    fun overrides(
        mountRoot: Path,
        vararg extra: String,
    ): Array<String> {
        secrets(mountRoot)
        return arrayOf(
            "service-runtime.workload-identity.audience=console-bff",
            "gst.service=console-bff",
            "gst.instance-id=console-bff-0",
            "service-runtime.pool.profile=minimal",
            "gst.bindings.secret-store.mount-root=$mountRoot",
            "gst.bindings.telemetry-export=bundled-otel",
            "gst.bindings.telemetry-export-otlp-endpoint=http://localhost:4318",
            "spring.datasource.url=$OFFLINE_URL",
            "console.canonical-svc-url=http://localhost:8082", // CanonicalEdgeClient requires its base URL (TPL-16)
            *extra,
        )
    }

    private fun secrets(mountRoot: Path) {
        Files.createDirectories(mountRoot.resolve("db"))
        Files.writeString(mountRoot.resolve("db/console_bff_runtime"), ConsoleDb.RUNTIME_PASSWORD)
        Files.createDirectories(mountRoot.resolve("tenancy"))
        Files.writeString(mountRoot.resolve("tenancy/binding-key-current"), BINDING_SECRET)
    }

    /** The application with the real application.yaml (TPL-16), then the overrides; no connection at start. */
    fun offline(
        mountRoot: Path,
        vararg extra: String,
    ): ApplicationContextRunner =
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ConsoleBffApplication::class.java)
            .withPropertyValues(*overrides(mountRoot, *extra))

    /** As [offline], over the Flyway-built Testcontainers database. */
    fun online(
        mountRoot: Path,
        vararg extra: String,
    ): ApplicationContextRunner = offline(mountRoot, "spring.datasource.url=${ConsoleDb.jdbcUrl}", *extra)
}
