package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
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
            "spring.datasource.url=jdbc:postgresql://localhost:5432/console",
            "spring.datasource.username=console_runtime",
            "service-runtime.workload-identity.audience=console-bff",
            "gst.service=console-bff",
            "gst.instance-id=console-bff-0",
            "service-runtime.pool.profile=minimal",
            "service-runtime.modules.schema=console",
            "service-runtime.idempotency.schema=console",
            "service-runtime.datasource.credential=db/console_runtime",
            "gst.bindings.secret-store.mount-root=$mountRoot",
            *extra,
        )

    /** Writes the mounted secrets the SecretStore reads. */
    fun secrets(mountRoot: Path) {
        Files.createDirectories(mountRoot.resolve("db"))
        Files.createDirectories(mountRoot.resolve("tenancy"))
        Files.writeString(mountRoot.resolve("db/console_runtime"), ConsoleDb.RUNTIME_PASSWORD)
        Files.writeString(mountRoot.resolve("tenancy/binding-key-current"), ConsoleDb.BINDING_SECRET)
    }

    /** No connection is opened at start: the pool is lazy (TPL-16). */
    fun offline(
        mountRoot: Path,
        vararg extra: String,
    ): ApplicationContextRunner {
        secrets(mountRoot)
        return ApplicationContextRunner()
            // the real application.yaml (TPL-16), then the overrides below
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ConsoleBffApplication::class.java)
            .withPropertyValues(*overrides(mountRoot, *extra))
    }
}
