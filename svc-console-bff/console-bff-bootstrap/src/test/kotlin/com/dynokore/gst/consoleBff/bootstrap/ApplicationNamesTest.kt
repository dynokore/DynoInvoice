package com.dynokore.gst.consoleBff.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import java.nio.file.Path

/** The schema and role names of the real application.yaml are the provisioner's (INV-AC-21019). */
class ApplicationNamesTest {
    @Test
    fun `INV-AC-21019 the eight name properties equal the provisioner names`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            val env: Environment = context.environment
            env.getProperty("spring.datasource.username") shouldBe "console_bff_runtime"
            env.getProperty("spring.flyway.schemas") shouldBe "console_bff"
            env.getProperty("spring.flyway.default-schema") shouldBe "console_bff"
            env.getProperty("spring.flyway.user") shouldBe "console_bff_migrate"
            env.getProperty("service-runtime.datasource.credential") shouldBe "db/console_bff_runtime"
            env.getProperty("service-runtime.modules.schema") shouldBe "console_bff"
            env.getProperty("service-runtime.idempotency.schema") shouldBe "console_bff"
            Binder
                .get(env)
                .bind("inv.tenancy.platform-scoped-tables", Bindable.listOf(String::class.java))
                .get() shouldBe listOf("console_bff.console_session", "console_bff.claim_due")
        }
    }
}
