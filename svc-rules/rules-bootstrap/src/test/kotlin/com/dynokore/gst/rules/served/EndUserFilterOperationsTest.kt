package com.dynokore.gst.rules.served

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The request filter of service-runtime on every operation rules-svc serves, driven by the contract marker
 * (INV-REQ-1202 behavior 4; owner answer INV-Q-8201; INV-AC-8260). The service is the real composition (TPL-18) as a
 * servlet application; the end-user resolver is a counting double, and the handler boundary is recorded by a filter
 * behind the binding filter.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndUserFilterOperationsTest {
    private val document = ContractDocument.read()
    private val callers = namedCallers(document)
    private val service = ServedService(callers)

    @AfterAll
    fun stop() = service.close()

    @BeforeEach
    fun reset() = Doubles.reset()

    private fun leftAlone(
        op: ContractOperation,
        caller: String,
    ) {
        Doubles.reset()
        val bare = service.call(op, workloadToken(caller))
        Doubles.resolverCalls.get() shouldBe 0
        withClue(
            "${op.key} as $caller answered ${bare.statusCode()} ${bare.body()}",
        ) { Doubles.reached.shouldHaveSize(1) }
        with(Doubles.reached.single()) {
            tenant shouldBe null
            actor shouldBe null
        }
        (bare.statusCode() != 401) shouldBe true
    }

    private fun answers401Without(
        op: ContractOperation,
        caller: String,
        authorization: String?,
    ) {
        Doubles.reset()
        val answer = service.call(op, workloadToken(caller), authorization)
        withClue("${op.key} with ${authorization ?: "no token"}") {
            answer.statusCode() shouldBe 401
            answer.body() shouldContain "SRT-003"
            Doubles.reached.shouldBeEmpty()
        }
    }

    private fun bindsUserA(
        op: ContractOperation,
        caller: String,
    ) {
        Doubles.reset()
        service.call(op, workloadToken(caller), USER_A_TOKEN)
        withClue("${op.key} with the token of user-a") {
            with(Doubles.reached.single()) {
                tenant shouldBe TENANT_A.toString()
                actor shouldBe "user-a"
            }
        }
    }

    @Test
    fun `INV-AC-8260 the marker decides whether the filter runs on every mapped operation`() {
        val served = servedOperations(service, document)
        served.isNotEmpty() shouldBe true
        println("INV-AC-8260 mapped operations (class (a) = filter leaves it alone, (b) = end-user token required):")
        served.forEach { (mapped, op) ->
            val cls = if (op.untouched) "(a)" else "(b)"
            println("  $cls ${mapped.key} ${mapped.handler.beanType.simpleName} ${op.operationId}")
        }
        served.forEach { (_, op) ->
            val caller = allowedCaller(op, callers)
            if (op.untouched) {
                leftAlone(op, caller)
            } else {
                answers401Without(op, caller, null)
                answers401Without(op, caller, REFUSED_TOKEN)
                bindsUserA(op, caller)
            }
        }
    }

    @Test
    fun `INV-AC-8260 a mapped handler with no operation in the document fails the test`() {
        val withoutOne = document.drop(1)
        val result = runCatching { servedOperations(service, withoutOne) }
        result.exceptionOrNull() shouldNotBe null
        result.exceptionOrNull()!!.message!! shouldContain "has no operation in the contract document"
    }
}
