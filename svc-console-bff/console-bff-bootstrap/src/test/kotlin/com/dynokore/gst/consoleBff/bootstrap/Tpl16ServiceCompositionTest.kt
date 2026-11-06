package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.sql.DataSource

/**
 * TPL-16 and TPL-18 (P109): the service built the way TPL-18 writes it. This slice has no database (INV-Q-17448 C): no
 * DataSource and no TransactionRunner bean, so the schema-supply check of INV-Q-4815 does not apply.
 */
class Tpl16ServiceCompositionTest {
    @Test
    fun `INV-AC-14435 the application starts on the service-runtime composition root without a database`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure.shouldBeNull()
            context.getBeanNamesForType(DataSource::class.java).toList() shouldBe emptyList()
            context.getBeanNamesForType(TransactionRunner::class.java).toList() shouldBe emptyList()
            context.environment.getProperty("gst.service") shouldBe "console-bff"
        }
    }

    @Test
    fun `INV-AC-14435 the slice registers no controller and no console request filter`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure.shouldBeNull()
            context
                .getBeansWithAnnotation(org.springframework.web.bind.annotation.RestController::class.java)
                .keys
                .toList() shouldBe emptyList()
            context
                .getBeansWithAnnotation(org.springframework.stereotype.Controller::class.java)
                .keys
                .toList() shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-14435 the management port and the health groups are configured`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            val environment = context.environment
            environment.getProperty("management.server.port") shouldBe "8081"
            environment.getProperty("management.endpoint.health.group.live.include") shouldBe "livenessState"
            environment.getProperty("management.endpoints.web.exposure.include") shouldBe
                "health,prometheus,diagnostics"
        }
    }
}
