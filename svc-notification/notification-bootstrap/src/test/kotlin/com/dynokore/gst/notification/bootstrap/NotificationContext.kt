package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.NotificationDb
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path

/** The service composed as TPL-18 writes it, over a TPL-16 configuration (application.yaml plus these overrides). */
object NotificationContext {
    private fun secrets(mountRoot: Path) {
        Files.createDirectories(mountRoot.resolve("db"))
        Files.createDirectories(mountRoot.resolve("tenancy"))
        Files.writeString(mountRoot.resolve("db/notification_runtime"), NotificationDb.RUNTIME_PASSWORD)
        Files.writeString(mountRoot.resolve("tenancy/binding-key-current"), NotificationDb.BINDING_SECRET)
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
            .withUserConfiguration(NotificationApplication::class.java)
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://localhost:5432/notification",
                "spring.datasource.username=notification_runtime",
                "service-runtime.workload-identity.audience=notification-svc",
                "gst.service=notification-svc",
                "gst.instance-id=notification-svc-0",
                "service-runtime.pool.profile=minimal",
                "service-runtime.modules.schema=notification",
                "service-runtime.idempotency.schema=notification",
                "service-runtime.datasource.credential=db/notification_runtime",
                "gst.bindings.secret-store.mount-root=$mountRoot",
            )

    /** Over the Testcontainers database; the login password and the binding key come from a mounted secret store. */
    fun online(mountRoot: Path): ApplicationContextRunner {
        secrets(mountRoot)
        return base(mountRoot).withPropertyValues("spring.datasource.url=${NotificationDb.jdbcUrl}")
    }
}
