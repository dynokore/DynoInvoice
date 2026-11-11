package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.nio.file.Files
import java.nio.file.Path

/**
 * One handler per route (INV-T-14463): a web context that scans both `IntakeController` and
 * `TransactionIntakeController` maps `/internal/v1/aggregation/batches` and `/internal/v1/aggregation/transactions`
 * to exactly one handler method each, and starts. `IntakeController` carries no stereotype, so only its subclass is a bean.
 */
class IntakeRouteMappingTest {
    private fun runner(mountRoot: Path): WebApplicationContextRunner {
        Files.createDirectories(mountRoot.resolve("db"))
        Files.createDirectories(mountRoot.resolve("tenancy"))
        Files.writeString(mountRoot.resolve("db/aggregation_runtime"), AggregationDb.RUNTIME_PASSWORD)
        Files.writeString(mountRoot.resolve("tenancy/binding-key-current"), AggregationDb.BINDING_SECRET)
        return WebApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AggregationApplication::class.java)
            .withPropertyValues(
                "spring.datasource.url=jdbc:postgresql://localhost:5432/aggregation",
                "spring.datasource.username=aggregation_runtime",
                "service-runtime.workload-identity.audience=aggregation-svc",
                "gst.service=aggregation-svc",
                "gst.instance-id=aggregation-svc-0",
                "service-runtime.pool.profile=minimal",
                "service-runtime.modules.schema=aggregation",
                "service-runtime.idempotency.schema=aggregation",
                "service-runtime.datasource.credential=db/aggregation_runtime",
                "gst.bindings.secret-store.mount-root=$mountRoot",
                "gst.bindings.telemetry-export=bundled-otel",
                "gst.bindings.telemetry-export-otlp-endpoint=http://localhost:4318",
            )
    }

    private fun handlers(
        context: AssertableWebApplicationContext,
        path: String,
    ): List<RequestMappingInfo> =
        context
            .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping::class.java)
            .handlerMethods
            .keys
            .filter { path in it.directPaths }

    @Test
    fun `each intake route has exactly one handler method and the web context starts`(
        @TempDir dir: Path,
    ) {
        runner(dir).run { context ->
            (context.startupFailure == null) shouldBe true
            ROUTES.forEach { handlers(context, it) shouldHaveSize 1 }
        }
    }

    private companion object {
        val ROUTES =
            listOf("/internal/v1/aggregation/batches", "/internal/v1/aggregation/transactions")
    }
}
