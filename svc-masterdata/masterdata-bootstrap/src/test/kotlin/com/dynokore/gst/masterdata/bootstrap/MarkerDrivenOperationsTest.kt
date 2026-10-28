package com.dynokore.gst.masterdata.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The class of a mapped operation by its contract markers, compared by equality: (a) left alone, (b) end-user. */
internal fun isLeftAlone(operation: DocumentOperation): Boolean =
    operation.principal == "workload" || operation.callerAuth == "signed-link" || operation.callerAuth == "service-job"

/** The caller names the contract documents name, for the workload-token double. */
internal fun callersOf(operations: List<DocumentOperation>): List<String> =
    operations
        .flatMap { it.edges }
        .flatMap { edge -> edge.substringBefore("->").split(',') }
        .map { it.trim().removePrefix("any-except:") }
        .filter { it != "any" }
        .distinct()
        .sorted()

/** The callers an `x-sync-edge` of the operation allows, the text before `->` of each element. */
internal fun allowedCallers(
    operation: DocumentOperation,
    universe: List<String>,
): Set<String> =
    operation.edges
        .map { it.substringBefore("->").trim() }
        .flatMap { element ->
            when {
                element == "any" -> universe
                element.startsWith("any-except:") -> universe - element.removePrefix("any-except:")
                else -> listOf(element)
            }
        }.toSet()

/**
 * INV-AC-8260: the contract marker decides whether the request filter of service-runtime runs, for every handler the
 * application context maps (INV-REQ-1202 behavior 4; INV-Q-8201 A).
 */
class MarkerDrivenOperationsTest {
    private companion object {
        const val END_USER_OPERATION = "resolveCounterparty"
    }

    private val document = ContractDocument.text()

    private fun workload(operations: List<DocumentOperation>) = WorkloadDoubles(callersOf(operations))

    /** Runs the criterion over every mapped handler against [text] as the contract document; prints the classes. */
    private fun assertMarkers(
        text: String,
        removed: List<String> = emptyList(),
    ) {
        val operations = ContractDocument.operations(text)
        val doubles = workload(ContractDocument.operations(document))
        ServedService.run(doubles, removed) { service ->
            assertThat(service.handlers).describedAs("mapped handlers").isNotEmpty()
            val report = mutableListOf<String>()
            service.handlers.forEach { handler ->
                val operation = ContractDocument.find(operations, handler)
                assertThat(
                    operation,
                ).describedAs("no operation of the document for ${handler.method} ${handler.path}").isNotNull()
                operation!!
                val caller = doubles.tokenOf(allowedCallers(operation, doubles.services).first())
                report +=
                    "${operation.id} ${handler.method} ${handler.path} class " +
                    if (isLeftAlone(operation)) "(a)" else "(b)"
                if (isLeftAlone(
                        operation,
                    )
                ) {
                    assertLeftAlone(
                        service,
                        handler,
                        operation,
                        caller,
                    )
                } else {
                    assertEndUser(service, handler, operation, caller)
                }
            }
            println("INV-AC-8260 mapped operations:\n" + report.joinToString("\n"))
        }
    }

    private fun assertLeftAlone(
        service: ServedService,
        handler: MappedHandler,
        operation: DocumentOperation,
        caller: String,
    ) {
        val calls = EndUserDoubles.calls.get()
        val before = service.counter.reaches.size
        val answer = service.call(handler, caller)
        val label = "${operation.id}: ${answer.status} ${answer.body}"
        assertThat(EndUserDoubles.calls.get()).describedAs("resolver calls, $label").isEqualTo(calls)
        assertThat(answer.status == 401 && answer.body.contains("SRT-003")).describedAs(label).isFalse()
        assertThat(service.counter.reaches.drop(before)).describedAs("bound, $label").allMatch {
            it.tenant == null &&
                it.actor == null
        }
        assertThat(service.nothingBound()).isTrue()
    }

    private fun assertEndUser(
        service: ServedService,
        handler: MappedHandler,
        operation: DocumentOperation,
        caller: String,
    ) {
        val id = operation.id
        listOf<String?>(null, EndUserDoubles.REFUSED).forEach { token ->
            val before = service.counter.reaches.size
            val answer = service.call(handler, caller, token)
            assertThat(answer.status).describedAs("$id with token $token: ${answer.body}").isEqualTo(401)
            assertThat(answer.body).describedAs("$id with token $token").contains("SRT-003")
            assertThat(
                service.counter.reaches.size,
            ).describedAs("$id use case calls with token $token").isEqualTo(before)
        }
        val before = service.counter.reaches.size
        service.call(handler, caller, EndUserDoubles.USER_A)
        assertThat(service.counter.reaches.size).describedAs("$id use case calls with user-a").isEqualTo(before + 1)
        val reach = service.counter.reaches.last()
        assertThat(reach.tenant).describedAs("$id tenant").isEqualTo(EndUserDoubles.tenantA)
        assertThat(reach.actor).describedAs("$id actor").isEqualTo("user-a")
        assertThat(service.nothingBound()).describedAs("$id bound after the response").isTrue()
    }

    private fun flipped(
        id: String,
        to: String,
    ): String {
        val start = document.indexOf("operationId: $id\n")
        val at = document.indexOf("x-caller-auth: ", start)
        val end = document.indexOf('\n', at)
        return document.substring(0, at) + "x-caller-auth: $to" + document.substring(end)
    }

    @Test
    fun `INV-AC-8260 the marker decides which mapped operations meet the filter`() = assertMarkers(document)

    @Test
    fun `INV-AC-8260 probe an end-user operation changed to signed-link fails the test`() {
        val copy = flipped(END_USER_OPERATION, "signed-link")
        assertThatThrownBy {
            assertMarkers(copy)
        }.isInstanceOf(AssertionError::class.java).hasMessageContaining(END_USER_OPERATION)
    }

    @Test
    fun `INV-AC-8260 probe the look-alike Signed-Link is class (b) and the 401 assertion holds`() {
        val copy = flipped(END_USER_OPERATION, "Signed-Link")
        assertMarkers(copy)
    }

    @Test
    fun `INV-AC-8260 probe the two marker resources removed from the classpath fail the test`() {
        val copy = flipped(END_USER_OPERATION, "signed-link")
        val removed = listOf("META-INF/gst/workload-only-operations.tsv", "META-INF/gst/caller-auth-operations.tsv")
        assertThatThrownBy {
            assertMarkers(copy, removed)
        }.isInstanceOf(AssertionError::class.java).hasMessageContaining("SRT-003")
    }

    @Test
    fun `INV-AC-8260 probe a mapped handler with no operation in the document fails the test`() {
        val copy =
            document.replace(
                "/internal/v1/masterdata/counterparties/{counterpartyRef}/history:",
                "/internal/v1/masterdata/other/{counterpartyRef}:",
            )
        assertThatThrownBy {
            assertMarkers(copy)
        }.isInstanceOf(AssertionError::class.java).hasMessageContaining("no operation of the document")
    }
}
