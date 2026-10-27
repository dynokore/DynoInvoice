package com.dynokore.gst.notification.bootstrap

import com.dynokore.gst.notification.adapters.NotificationDb
import com.dynokore.gst.notification.syncedge.CallerChecks
import com.dynokore.gst.notification.syncedge.ContractDocument
import com.dynokore.gst.notification.syncedge.Operation
import com.dynokore.gst.notification.syncedge.SourceScan
import com.dynokore.gst.notification.syncedge.TestWorkloadVerifier
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.ClassPathResource
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.nio.file.Path

/**
 * INV-AC-8455 (API-25; owner ruling 2026-10-22): the service refuses, with 403 SRT-004 and nothing stored, a verified
 * caller that the `x-sync-edge` of the called operation does not name. The allowed callers are read from the contract
 * document the service ships; the enforcement is `service-runtime`'s, from the generated `sync-edge-callers.tsv`.
 * The controllers are stubs (501) until the tasks of `operations.tsv` build them, so a request that reaches the
 * handler answers 501 and no use case is called.
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

    private fun mapped(context: ApplicationContext): List<Pair<String, String>> =
        context
            .getBean(RequestMappingHandlerMapping::class.java)
            .handlerMethods.keys
            .flatMap { info ->
                val templates = info.pathPatternsCondition?.patternValues.orEmpty()
                templates.filter { it.startsWith(INTERNAL) }.flatMap { path ->
                    info.methodsCondition.methods.map {
                        it.name to
                            path
                    }
                }
            }.sortedBy { it.second + it.first }

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
        NotificationContext
            .startServlet(
                dir,
                listOf(TestWorkloadVerifier::class.java),
                properties(services),
                classes,
            ).use(block)
    }

    @Test
    fun `INV-AC-8455 the service refuses a verified caller its x-sync-edge does not name`(
        @TempDir dir: Path,
    ) {
        val operations = operations()
        val services = namedCallers(operations)
        val lookAlikes = services.map { "$it-two" }
        val problems = mutableListOf<String>()
        NotificationDb.superuser().use { connection ->
            serve(dir, services + lookAlikes, loader) { context ->
                val checks = CallerChecks(context, connection, services, lookAlikes)
                val byLabel = operations.associateBy { it.label }
                val lines = generatedLines()
                val handlers = mapped(context)
                (handlers.isNotEmpty()) shouldBe true
                handlers.forEach { (method, path) ->
                    val operation = byLabel["$method $path"]
                    problems +=
                        if (operation == null) {
                            listOf("$method $path: mapped, but the contract document has no such operation")
                        } else {
                            checks.problems(operation, lines[operation.label])
                        }
                }
            }
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-8455 with the generated resource off the classpath every internal operation answers 403 SRT-004`(
        @TempDir dir: Path,
    ) {
        val services = namedCallers(operations())
        val without = FilteredClassLoader(ClassPathResource("META-INF/gst/sync-edge-callers.tsv"))
        val problems = mutableListOf<String>()
        NotificationDb.superuser().use { connection ->
            serve(dir, services, without) { context ->
                val checks = CallerChecks(context, connection, services, emptyList())
                val handlers = mapped(context)
                (handlers.isNotEmpty()) shouldBe true
                handlers.forEach { (method, path) ->
                    val operation = operations().first { it.label == "$method $path" }
                    val answer =
                        checks.send(
                            operation,
                            mapOf(
                                CallerChecks.TOKEN to "token-${services.first()}",
                                CallerChecks.AUTH to CallerChecks.END_USER,
                            ),
                        )
                    if (answer.statusCode() != 403 || "SRT-004" !in answer.body()) {
                        problems += "$method $path: ${answer.statusCode()} with the generated resource missing"
                    }
                }
            }
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-8455 the main source sets hold no literal caller name passed to a caller check`() {
        val root = Path.of(System.getProperty("user.dir")).parent
        SourceScan.findings(root, namedCallers(operations()).toSet()).shouldBeEmpty()
    }

    private companion object {
        const val INTERNAL = "/internal/v1/"
    }
}
