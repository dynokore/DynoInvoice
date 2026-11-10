package com.dynokore.gst.consoleBff.bootstrap

import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path

/** The service composed as TPL-18 writes it, over a TPL-16 configuration (application.yaml plus these overrides). */
object ConsoleContext {
    /** The override properties; [extra] come last and win. */
    fun overrides(
        mountRoot: Path,
        vararg extra: String,
    ): Array<String> =
        arrayOf(
            "service-runtime.workload-identity.audience=console-bff",
            "gst.service=console-bff",
            "gst.instance-id=console-bff-0",
            "gst.bindings.secret-store.mount-root=$mountRoot",
            "gst.bindings.telemetry-export=bundled-otel",
            "gst.bindings.telemetry-export-otlp-endpoint=http://localhost:4318",
            *extra,
        )

    /** The application with the real application.yaml (TPL-16), then the overrides. */
    fun offline(
        mountRoot: Path,
        vararg extra: String,
    ): ApplicationContextRunner {
        Files.createDirectories(mountRoot)
        return ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ConsoleBffApplication::class.java)
            .withPropertyValues(*overrides(mountRoot, *extra))
    }
}
