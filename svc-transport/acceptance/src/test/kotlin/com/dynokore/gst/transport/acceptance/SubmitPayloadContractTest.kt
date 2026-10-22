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

    /** The baseline with each slot at its conforming value, except those named in [slots]. */
    private fun spec(vararg slots: Pair<String, String>): String =
        (CONFORMING + slots.toMap()).entries.fold(BASELINE) { text, (slot, value) -> text.replace(slot, value) }

    @Test
    fun `INV-AC-2031 the submit definition of the real contract holds no AP-specific field`() {
        SubmitPayloadContract.findings(real).shouldBeEmpty()
    }

    @Test
    fun `INV-AC-7857 the schema test passes on the contract and fails on a copy with an AP field`() {
        SubmitPayloadContract.findings(real).shouldBeEmpty()
        SubmitPayloadContract.findings(spec()).shouldBeEmpty()
        SubmitPayloadContract
            .findings(spec("%REQUEST_EXTRA%" to "apCredential: { type: string }"))
            .joinToString() shouldContain "apCredential"
        SubmitPayloadContract
            .findings(spec("%RESPONSE_EXTRA%" to "apErrorCode: { type: string }"))
            .joinToString() shouldContain "apErrorCode"
    }

    @Test
    fun `INV-AC-7857 an AP-specific name is refused whatever it is called and wherever it is nested`() {
        listOf("envelope", "batchId", "peppolMessageId", "adapterRef", "payloadRef", "AP_CODE").forEach { name ->
            SubmitPayloadContract
                .findings(spec("%REQUEST_EXTRA%" to "$name: { type: string }"))
                .joinToString() shouldContain "'$name'"
        }
        SubmitPayloadContract
            .findings(
                spec("%OUTCOMES%" to "[ACCEPTED, REJECTED, TRANSIENT_FAILURE, AP_TIMEOUT]"),
            ).shouldNotBeEmpty()
        SubmitPayloadContract.findings(spec("%OUTCOMES%" to "[ACCEPTED, REJECTED]")).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-7857 the key sets of the three schemas are compared by equality`() {
        // the acknowledgement schema, a request without the closed-object marker, and a dropped property each fail
        SubmitPayloadContract
            .findings(
                spec("%ACK_EXTRA%" to "apCredential: { type: string }"),
            ).joinToString() shouldContain
            "Acknowledgement"
        SubmitPayloadContract.findings(spec("%ADDITIONAL%" to "# open object")).joinToString() shouldContain
            "additionalProperties"
        SubmitPayloadContract.findings(spec().replace("payload: { type: string }", "")).joinToString() shouldContain
            "SubmitPayloadRequest"
        SubmitPayloadContract.findings(spec().replace("receivedAt: { type: string }", "")).joinToString() shouldContain
            "Acknowledgement"
        // the entity of the document (INV-Q-8172): dropped, optional, or beside a look-alike, each is a finding
        SubmitPayloadContract.findings(spec().replace("entityRef: { type: string }", "")).joinToString() shouldContain
            "SubmitPayloadRequest"
        SubmitPayloadContract
            .findings(spec("%REQUIRED%" to "required: [docUuid, version, payload]"))
            .joinToString() shouldContain "entityRef must be required"
        SubmitPayloadContract
            .findings(spec("%REQUIRED%" to "# no required list"))
            .joinToString() shouldContain "entityRef must be required"
        listOf("entityRefs", "entityref", "EntityRef", "entityRef ").forEach { name ->
            SubmitPayloadContract.findings(spec("%REQUEST_EXTRA%" to "'$name': { type: string }")).shouldNotBeEmpty()
        }
        SubmitPayloadContract.findings(spec().replace("Acknowledgement:", "Receipt:")).shouldNotBeEmpty()
        listOf(
            "apCredential",
            "apErrorCode",
            "envelope",
            "batchId",
            "peppolMessageId",
            "adapterRef",
            "payloadRef",
            "AP_CODE",
        ).forEach { name ->
            SubmitPayloadContract.findings(spec("%ACK_EXTRA%" to "$name: { type: string }")).shouldNotBeEmpty()
            SubmitPayloadContract.findings(spec("%REQUEST_EXTRA%" to "$name: { type: string }")).shouldNotBeEmpty()
        }
    }

    @Test
    fun `the check fails closed when the operation or a body is missing`() {
        SubmitPayloadContract.findings("openapi: 3.1.0\npaths: {}\n").shouldNotBeEmpty()
        SubmitPayloadContract.findings(spec().replace("requestBody:", "noRequestBody:")).shouldNotBeEmpty()
        SubmitPayloadContract.findings(spec().replace("'202':", "'200':")).shouldNotBeEmpty()
    }

    private companion object {
        const val NO_EXTRA = "# no extra property"

        val CONFORMING =
            mapOf(
                "%REQUEST_EXTRA%" to NO_EXTRA,
                "%RESPONSE_EXTRA%" to NO_EXTRA,
                "%OUTCOMES%" to "[ACCEPTED, REJECTED, TRANSIENT_FAILURE]",
                "%ADDITIONAL%" to "additionalProperties: false",
                "%REQUIRED%" to "required: [docUuid, version, payload, entityRef]",
                "%ACK_EXTRA%" to NO_EXTRA,
            )

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
                          ${'$'}ref: '#/components/schemas/SubmitPayloadRequest'
                  responses:
                    '202':
                      content:
                        application/json:
                          schema:
                            ${'$'}ref: '#/components/schemas/TransportOutcome'
            components:
              schemas:
                SubmitPayloadRequest:
                  type: object
                  %ADDITIONAL%
                  %REQUIRED%
                  properties:
                    docUuid: { type: string }
                    version: { type: integer }
                    payload: { type: string }
                    entityRef: { type: string }
                    %REQUEST_EXTRA%
                TransportOutcome:
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
                    acknowledgement:
                      ${'$'}ref: '#/components/schemas/Acknowledgement'
                Acknowledgement:
                  type: object
                  properties:
                    reference: { type: string }
                    receivedAt: { type: string }
                    %ACK_EXTRA%
            """.trimIndent()
    }
}
