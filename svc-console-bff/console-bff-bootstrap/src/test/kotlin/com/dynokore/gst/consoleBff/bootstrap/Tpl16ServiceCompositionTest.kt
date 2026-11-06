package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.sql.DataSource

/**
 * TPL-16 and TPL-18 (P109): the service built the way TPL-18 writes it. Every port the adapters inject is a bean over
 * the one DataSource from `spring.datasource.*`; there is no role DataSource.
 */
class Tpl16ServiceCompositionTest {
    @Test
    fun `INV-AC-14435 the application starts on the service-runtime composition root over the one DataSource`(
        @TempDir dir: Path,
    ) {
        ConsoleContext.offline(dir).run { context ->
            context.startupFailure.shouldBeNull()
            listOf(TransactionRunner::class, CurrentConnection::class).forEach { context.getBean(it.java) }
            context.getBeanNamesForType(DataSource::class.java).toList() shouldBe listOf("dataSource")
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
