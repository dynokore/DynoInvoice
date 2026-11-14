package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.syncedge.CallerChecks
import com.dynokore.gst.controlPlane.syncedge.ContractDocument
import com.dynokore.gst.controlPlane.syncedge.Mapped
import com.dynokore.gst.controlPlane.syncedge.Operation
import com.dynokore.gst.controlPlane.syncedge.SourceScan
import com.dynokore.gst.controlPlane.syncedge.TestIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.ClassPathResource
import java.nio.file.Path

/**
 * INV-AC-8455 (API-25; owner ruling 2026-10-22): the service refuses, with 403 SRT-004 and nothing stored, a verified
 * caller that the `x-sync-edge` of the called operation does not name. The allowed callers are read from the contract
 * document the service ships; the enforcement is `service-runtime`'s, from the generated `sync-edge-callers.tsv`.
 * A request that reaches the handler is recorded once by the interceptor of [TestIdentity], which answers 202.
 */
class SyncEdgeCallerTest {
    private val loader = javaClass.classLoader

    private fun operations(): List<Operation> = ContractDocument.operations(ContractDocument.text(loader))

    private fun namedCallers(operations: List<Operation>): List<String> =
        operations
            .flatMap { it.callerFields }
            .map { it.removePrefix(CallerChecks.EXCEPT) }
            .filter { it != "any" }
            .distinct()
            .sorted()

    private fun generatedLines(): Map<String, String> =
        loader
            .getResources("META-INF/gst/sync-edge-callers.tsv")
            .toList()
            .flatMap { url ->
                url
                    .readText()
                    .lines()
                    .filter { it.isNotEmpty() }
                    .map { it.split('\t') }
            }.associate { (it[0] + " " + it[1]) to it[2] }

    /** `service-runtime.workload-identity.callers`: each service is the subject `sa-<service>`. */
    private fun properties(services: List<String>): Array<String> =
        services
            .flatMapIndexed { index, service ->
                listOf(
                    "service-runtime.workload-identity.callers[$index].service=$service",
                    "service-runtime.workload-identity.callers[$index].subject=sa-$service",
                )
            }.toTypedArray()

    private fun serve(
        dir: Path,
        services: List<String>,
        classes: ClassLoader,
        block: (ConfigurableApplicationContext) -> Unit,
    ) {
        ServletFixture
            .start(dir, listOf(TestIdentity::class.java), properties(services), classes)
            .use(block)
    }

    @Test
    fun `INV-AC-8455 the service refuses a verified caller its x-sync-edge does not name`(
        @TempDir dir: Path,
    ) {
        val operations = operations()
        val services = namedCallers(operations)
        // "-two", not "-2": SERVICE_NAME of WorkloadIdentityProperties, ^[a-z][a-z-]{0,62}$, stops start-up on a digit.
        val lookAlikes = services.map { "$it-two" }
        val problems = mutableListOf<String>()
        run {
            serve(dir, services + lookAlikes, loader) { context ->
                val checks = CallerChecks(context, services, lookAlikes)
                val byLabel = operations.associateBy { it.label }
                val lines = generatedLines()
                val handlers = Mapped.handlers(context)
                assertThat(handlers).isNotEmpty()
                handlers.forEach { handler ->
                    val operation = byLabel[handler.label]
                    when {
                        operation == null -> {
                            problems += "${handler.label}: mapped, but the contract document has no such operation"
                        }

                        operation.edge.isEmpty() -> {
                            if (handler.label in
                                lines
                            ) {
                                problems += "${handler.label}: a generated line, but no x-sync-edge"
                            }
                        }

                        else -> {
                            problems += checks.problems(operation, lines[operation.label])
                        }
                    }
                }
            }
        }
        assertThat(problems).isEmpty()
    }

    @Test
    fun `INV-AC-8455 positive control one direct getConnection call raises the count by exactly 1`(
        @TempDir dir: Path,
    ) {
        serve(dir, namedCallers(operations()), loader) { context ->
            val checks = CallerChecks(context, emptyList(), emptyList())
            val before = checks.connectionsHandedOut()
            runCatching { context.getBean(javax.sql.DataSource::class.java).connection.close() }
            assertThat(checks.connectionsHandedOut()).isEqualTo(before + 1)
        }
    }

    @Test
    fun `INV-AC-8455 with the generated resource off the classpath every internal operation answers 403 SRT-004`(
        @TempDir dir: Path,
    ) {
        val operations = operations()
        val services = namedCallers(operations)
        val without = FilteredClassLoader(ClassPathResource("META-INF/gst/sync-edge-callers.tsv"))
        val problems = mutableListOf<String>()
        run {
            serve(dir, services, without) { context ->
                val checks = CallerChecks(context, services, emptyList())
                val handlers = Mapped.handlers(context).filter { it.pattern.startsWith(INTERNAL) }
                assertThat(handlers).isNotEmpty()
                handlers.forEach { handler ->
                    val operation = operations.first { it.label == handler.label }
                    val answer =
                        checks.send(
                            operation,
                            mapOf(
                                CallerChecks.TOKEN to "token-${services.first()}",
                                CallerChecks.AUTH to CallerChecks.END_USER,
                            ),
                        )
                    if (answer.statusCode() != 403 || "SRT-004" !in answer.body()) {
                        problems += "${handler.label}: ${answer.statusCode()} with the generated resource missing"
                    }
                }
            }
        }
        assertThat(problems).isEmpty()
    }

    @Test
    fun `INV-AC-8455 the main source sets hold no literal caller name passed to a caller check`() {
        val root = Path.of(System.getProperty("user.dir")).parent
        assertThat(SourceScan.findings(root, namedCallers(operations()).toSet())).isEmpty()
    }

    private companion object {
        const val INTERNAL = "/internal/v1/"
    }
}
