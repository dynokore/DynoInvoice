package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.syncedge.CallerChecks
import com.dynokore.gst.tenancy.syncedge.ContractDocument
import com.dynokore.gst.tenancy.syncedge.Doubles
import com.dynokore.gst.tenancy.syncedge.Mapped
import com.dynokore.gst.tenancy.syncedge.Operation
import com.dynokore.gst.tenancy.syncedge.TestIdentity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ConfigurableApplicationContext
import java.nio.file.Path

/**
 * INV-AC-8260 (INV-REQ-1202 behavior 4; owner answer INV-Q-8201): the marker of the contract document decides whether
 * the request filter of `service-runtime` runs. Class (a) is an operation whose `x-principal` is `workload` or whose
 * `x-caller-auth` is `signed-link` or `service-job`: it is called without a token, the resolver is not called and
 * nothing is bound. Class (b) is every other operation: 401 SRT-003 without a token and with a refused token, and
 * with the token of `user-a` the handler is reached with the tenant and the actor bound. A request reaches the
 * handler when the interceptor of [TestIdentity] records it, and a request that the filter refuses leaves that record
 * unchanged.
 */
class EndUserFilterTest {
    private val loader = javaClass.classLoader

    private fun serve(
        dir: Path,
        services: List<String>,
        classes: ClassLoader,
        block: (ConfigurableApplicationContext) -> Unit,
    ) {
        val properties =
            services
                .flatMapIndexed { index, service ->
                    listOf(
                        "service-runtime.workload-identity.callers[$index].service=$service",
                        "service-runtime.workload-identity.callers[$index].subject=sa-$service",
                    )
                }.toTypedArray()
        BoundaryFixture.startServlet(dir, listOf(TestIdentity::class.java), properties, classes).use(block)
    }

    /**
     * Every request carries a verified workload token (the filter of service-runtime refuses one without it with 401
     * before the end-user filter runs): the token of a caller the edge of the operation
     * names, else of any mapped service.
     */
    private fun withWorkload(
        operation: Operation,
        services: List<String>,
        headers: Map<String, String>,
    ): Map<String, String> {
        val caller =
            operation.callerFields.firstOrNull { it != "any" && !it.startsWith(CallerChecks.EXCEPT) }
                ?: services.first { "${CallerChecks.EXCEPT}$it" !in operation.callerFields }
        return headers + (CallerChecks.TOKEN to "token-$caller")
    }

    private fun untouchedProblems(
        checks: CallerChecks,
        operation: Operation,
        services: List<String>,
    ): List<String> {
        val resolver = Doubles.resolverCalls.get()
        val before = Doubles.reached.size
        val answer = checks.send(operation, withWorkload(operation, services, emptyMap()))
        val seen = Doubles.reached.drop(before)
        return listOfNotNull(
            "the resolver was called".takeIf { Doubles.resolverCalls.get() != resolver },
            "refused 401 SRT-003".takeIf { answer.statusCode() == 401 && "SRT-003" in answer.body() },
            "something is bound".takeIf { seen.any { reached -> reached.tenant != null || reached.actor != null } },
        ).map { "${operation.label}: $it" }
    }

    private fun filteredProblems(
        checks: CallerChecks,
        operation: Operation,
        services: List<String>,
    ): List<String> {
        val found = mutableListOf<String>()

        fun call(headers: Map<String, String>) = checks.send(operation, withWorkload(operation, services, headers))

        fun refused(
            what: String,
            headers: Map<String, String>,
        ) {
            val before = Doubles.reached.size
            val answer = call(headers)
            if (answer.statusCode() != 401 || "SRT-003" !in answer.body() || Doubles.reached.size != before) {
                found += "${operation.label} ($what): ${answer.statusCode()} ${answer.body().take(BODY_SHOWN)}"
            }
        }
        refused("no token", emptyMap())
        refused("refused token", mapOf(CallerChecks.AUTH to "Bearer refused"))
        val before = Doubles.reached.size
        val answer = call(mapOf(CallerChecks.AUTH to CallerChecks.END_USER))
        val seen = Doubles.reached.drop(before)
        val bound = seen.singleOrNull()
        if (bound == null || bound.tenant != TestIdentity.TENANT_A || bound.actor != TestIdentity.ACTOR) {
            found += "${operation.label} (user-a): ${answer.statusCode()} reached $seen"
        }
        return found
    }

    @Test
    fun `INV-AC-8260 the marker of the operation decides whether the filter runs`(
        @TempDir dir: Path,
    ) {
        val operations = ContractDocument.operations(ContractDocument.text(loader))
        val services =
            operations
                .flatMap { it.callerFields }
                .map { it.removePrefix(CallerChecks.EXCEPT) }
                .filter { it != "any" }
                .distinct()
                .sorted()
        val problems = mutableListOf<String>()
        BoundaryFixture.asSuperuser { connection ->
            serve(dir, services, loader) { context ->
                val checks = CallerChecks(context, connection, services, emptyList())
                val byLabel = operations.associateBy { it.label }
                val handlers = Mapped.handlers(context)
                assertThat(handlers).isNotEmpty()
                handlers.forEach { handler ->
                    val operation = byLabel[handler.label]
                    if (operation == null) {
                        problems += "${handler.label}: mapped, but the contract document has no such operation"
                    } else {
                        val kind = if (operation.untouched) 'a' else 'b'
                        println("INV-AC-8260 mapped ${handler.label} ${handler.type} class ($kind)")
                        problems +=
                            if (operation.untouched) {
                                untouchedProblems(checks, operation, services)
                            } else {
                                filteredProblems(checks, operation, services)
                            }
                    }
                }
            }
        }
        assertThat(problems).isEmpty()
    }

    private companion object {
        const val BODY_SHOWN = 120
    }
}
