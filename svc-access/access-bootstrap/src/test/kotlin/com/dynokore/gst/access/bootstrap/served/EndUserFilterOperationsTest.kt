package com.dynokore.gst.access.bootstrap.served

import com.dynokore.gst.access.bootstrap.BoundaryFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val ANY_EXCEPT = "any-except:"

/** The services the document names as callers, `any-except:<svc>` contributing `<svc>`. */
fun namedCallers(document: List<ContractOperation>): Set<String> =
    document
        .flatMap { op ->
            op.edgeCallers.flatMap { element ->
                when {
                    element == "any" -> {
                        error(
                            "${op.key} (${op.operationId}): an `any` edge needs the service list of the model",
                        )
                    }

                    element.startsWith(ANY_EXCEPT) -> {
                        listOf(element.removePrefix(ANY_EXCEPT))
                    }

                    else -> {
                        listOf(element)
                    }
                }
            }
        }.toSet()

/** The callers an operation names plainly, and the service its `any-except:<svc>` element excludes, if any. */
fun plainCallers(op: ContractOperation): List<String> =
    op.edgeCallers.filter {
        it != "any" &&
            !it.startsWith(ANY_EXCEPT)
    }

fun exceptedCallers(op: ContractOperation): List<String> =
    op.edgeCallers.filter { it.startsWith(ANY_EXCEPT) }.map { it.removePrefix(ANY_EXCEPT) }

/** The callers the operation allows among the [mapped] services: the named ones, or all but the excluded one. */
fun allowedCallers(
    op: ContractOperation,
    mapped: Set<String>,
): List<String> =
    (
        plainCallers(
            op,
        ) + if (exceptedCallers(op).isNotEmpty()) mapped - exceptedCallers(op).toSet() else emptySet()
    ).distinct().sorted()

/**
 * INV-AC-8260: the contract marker decides whether the end-user filter of service-runtime runs. Every operation the
 * application context maps is called with the doubles of [ServedDoubles]: class (a) is an operation whose `x-principal`
 * is `workload` or whose `x-caller-auth` is `signed-link` or `service-job`; class (b) is every other one.
 */
class EndUserFilterOperationsTest {
    private val document = ContractDocument.read()
    private val named = namedCallers(document)

    @BeforeEach
    fun reset() = Doubles.reset()

    @Test
    fun `INV-AC-8260 the marker decides which mapped operations the end-user filter runs on`() =
        withServedService(named) { served ->
            val operations = servedOperations(served, document)
            assertThat(operations).describedAs("mapped operations").isNotEmpty()
            println("INV-AC-8260 mapped operations and their class:")
            operations.forEach { (mapped, op) ->
                println(
                    "  ${mapped.key} (${mapped.handler.beanType.simpleName}): ${if (op.untouched) "(a)" else "(b)"}",
                )
            }
            operations.forEach { (_, op) ->
                // a caller the edge allows, so that only the marker decides what the end-user filter does
                val caller = workloadToken(allowedCallers(op, named).first())
                if (op.untouched) assertUntouched(served, op, caller) else assertFiltered(served, op, caller)
            }
        }

    private fun assertUntouched(
        served: ServedContext,
        op: ContractOperation,
        caller: String,
    ) {
        Doubles.reset()
        val answer = served.call(op, caller)
        assertThat(answer.status).describedAs("${op.key} (a) without a token").isNotIn(401, 403)
        assertThat(Doubles.resolverCalls.get()).describedAs("${op.key} resolver calls").isZero()
        assertThat(Doubles.reached).describedAs("${op.key} reached").hasSize(1)
        assertThat(Doubles.reached.single().tenant).describedAs("${op.key} tenant bound").isNull()
        assertThat(Doubles.reached.single().actor).describedAs("${op.key} actor bound").isNull()
    }

    private fun assertFiltered(
        served: ServedContext,
        op: ContractOperation,
        caller: String,
    ) {
        Doubles.reset()
        val before = served.stored()
        val none = served.call(op, caller)
        assertThat(none.status).describedAs("${op.key} (b) without a token").isEqualTo(401)
        assertThat(none.contentAsString).describedAs("${op.key} (b) without a token").contains("SRT-003")
        assertThat(Doubles.reached).describedAs("${op.key} double after no token").isEmpty()
        Doubles.reset()
        val refused = served.call(op, caller, REFUSED_TOKEN)
        assertThat(refused.status).describedAs("${op.key} (b) refused token").isEqualTo(401)
        assertThat(refused.contentAsString).describedAs("${op.key} (b) refused token").contains("SRT-003")
        assertThat(Doubles.reached).describedAs("${op.key} double after refused token").isEmpty()
        assertThat(served.stored()).describedAs("${op.key} (b) refused sweep stored").isEqualTo(before)
        Doubles.reset()
        served.call(op, caller, USER_A_TOKEN)
        assertThat(Doubles.reached).describedAs("${op.key} double after the token of user-a").hasSize(1)
        assertThat(
            Doubles.reached.single().tenant,
        ).describedAs("${op.key} tenant").isEqualTo(TENANT_A.toString())
        assertThat(Doubles.reached.single().actor).describedAs("${op.key} actor").isEqualTo("user-a")
    }

    @Test
    fun `INV-AC-8260 the content comparison sees an update that keeps every row count and a delete plus insert`() =
        withServedService(named) { served ->
            val head = "00000000-0000-4000-8000-0000000000f1"

            fun write(sql: String) = BoundaryFixture.asSuperuser { c -> c.createStatement().use { it.execute(sql) } }
            try {
                val empty = served.stored()
                write("INSERT INTO access.revocation_head (tenant_id, version) VALUES ('$head', 1)")
                val inserted = served.stored()
                assertThat(inserted).describedAs("a real insert").isNotEqualTo(empty)
                write("UPDATE access.revocation_head SET version = 2 WHERE tenant_id = '$head'")
                assertThat(served.stored()).describedAs("an update-only write").isNotEqualTo(inserted)
                write("UPDATE access.revocation_head SET version = 1 WHERE tenant_id = '$head'")
                assertThat(served.stored()).describedAs("the same content again").isEqualTo(inserted)
                write("DELETE FROM access.revocation_head WHERE tenant_id = '$head'")
                write("INSERT INTO access.revocation_head (tenant_id, version) VALUES ('$head', 3)")
                assertThat(served.stored()).describedAs("delete plus insert").isNotEqualTo(inserted)
            } finally {
                write("DELETE FROM access.revocation_head WHERE tenant_id = '$head'")
            }
        }

    @Test
    fun `INV-AC-8260 a look-alike marker value is class (b)`() {
        val operation = document.first { !it.untouched }
        listOf("Signed-Link", "signed-link ", "SERVICE-JOB", "service_job", "").forEach {
            assertThat(operation.copy(callerAuth = it).untouched).describedAs("x-caller-auth '$it'").isFalse()
        }
        assertThat(operation.copy(callerAuth = "signed-link").untouched).isTrue()
        assertThat(operation.copy(callerAuth = "service-job").untouched).isTrue()
        assertThat(operation.copy(principal = "workload").untouched).isTrue()
        assertThat(operation.copy(principal = "Workload").untouched).isFalse()
    }
}
