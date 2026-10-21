package com.dynokore.gst.transport.acceptance

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The schema test over the contract (INV-AC-2031, INV-AC-7857): no AP-specific field in `submitPayload`. The probes
 * run against a conforming baseline written here, so they hold whatever state the real contract is in.
 */
class SubmitPayloadContractTest {
    private val real = File(System.getProperty("gst.contract.dir"), "openapi.yaml").readText()

    private fun spec(
        requestExtra: String = "# no extra property",
        responseExtra: String = "# no extra property",
        outcomes: String = "[ACCEPTED, REJECTED, TRANSIENT_FAILURE]",
    ) = BASELINE
        .replace("%REQUEST_EXTRA%", requestExtra)
        .replace("%RESPONSE_EXTRA%", responseExtra)
        .replace("%OUTCOMES%", outcomes)

    @Test
    fun `INV-AC-2031 the submit definition of the real contract holds no AP-specific field`() {
        SubmitPayloadContract.findings(real).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-7857 the schema test passes on the contract and fails on a copy with an AP field`() {
        SubmitPayloadContract.findings(real).shouldBeEmpty()
        SubmitPayloadContract.findings(spec()).shouldBeEmpty()
        SubmitPayloadContract
            .findings(spec(requestExtra = "apCredential: { type: string }"))
            .joinToString() shouldContain "apCredential"
        SubmitPayloadContract
            .findings(spec(responseExtra = "apErrorCode: { type: string }"))
            .joinToString() shouldContain "apErrorCode"
    }

    @Test
    fun `INV-AC-7857 an AP-specific name is refused whatever it is called and wherever it is nested`() {
        listOf("envelope", "batchId", "peppolMessageId", "adapterRef", "payloadRef", "AP_CODE").forEach { name ->
            SubmitPayloadContract
                .findings(spec(requestExtra = "$name: { type: string }"))
                .joinToString() shouldContain "'$name'"
        }
        SubmitPayloadContract
            .findings(
                spec(outcomes = "[ACCEPTED, REJECTED, TRANSIENT_FAILURE, AP_TIMEOUT]"),
            ).shouldNotBeEmpty()
        SubmitPayloadContract.findings(spec(outcomes = "[ACCEPTED, REJECTED]")).shouldNotBeEmpty()
    }

    @Test
    fun `the check fails closed when the operation or a body is missing`() {
        SubmitPayloadContract.findings("openapi: 3.1.0\npaths: {}\n").shouldNotBeEmpty()
        SubmitPayloadContract.findings(spec().replace("requestBody:", "noRequestBody:")).shouldNotBeEmpty()
        SubmitPayloadContract.findings(spec().replace("'202':", "'200':")).shouldNotBeEmpty()
    }

    private companion object {
        /** A conforming `submitPayload` definition; the placeholders are the mutations of the probes. */
        val BASELINE =
            """
            openapi: 3.1.0
            paths:
              /internal/v1/transport/payloads:
                post:
                  operationId: submitPayload
                  requestBody:
                    content:
                      application/json:
                        schema:
                          ${'$'}ref: '#/components/schemas/Request'
                  responses:
                    '202':
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/Outcome'
            components:
              schemas:
                Request:
                  type: object
                  properties:
                    docUuid: { type: string }
                    version: { type: integer }
                    payload: { type: string }
                    %REQUEST_EXTRA%
                Outcome:
                  type: object
                  properties:
                    docUuid: { type: string }
                    version: { type: integer }
                    outcome: { type: string, enum: %OUTCOMES% }
                    reasons:
                      type: array
                      items:
                        type: object
                        properties:
                          code: { type: string }
                          message: { type: string }
                          %RESPONSE_EXTRA%
            """.trimIndent()
    }
}
