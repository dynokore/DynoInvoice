package com.dynokore.gst.ingestion.acceptance

import com.dynokore.gst.ingestion.domain.recording.RecordingMapping
import com.dynokore.gst.ingestion.domain.recording.RecordingOutcome
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/**
 * The codes the mapping relays (INV-Q-11018) are members of `x-error-codes` of `submitTransaction`, read from the
 * resource `gst/contracts/ingestion/openapi.yaml` of ingestion-contract; ING-002 (stale) must be a member too.
 */
class RelayCodesContractTest {
    private fun errorCodes(openapi: String): List<String> {
        val paths = Yaml().load<Map<String, Any?>>(openapi)["paths"] as Map<*, *>
        val operation =
            paths.values
                .filterIsInstance<Map<*, *>>()
                .flatMap { it.values }
                .filterIsInstance<Map<*, *>>()
                .single { it["operationId"] == "submitTransaction" }
        return (operation["x-error-codes"] as List<*>).map { it as String }
    }

    private val contractCodes =
        errorCodes(
            checkNotNull(javaClass.getResource("/gst/contracts/ingestion/openapi.yaml")) {
                "resource gst/contracts/ingestion/openapi.yaml is missing from ingestion-contract"
            }.readText(),
        )

    @Test
    fun `the relay codes and ING-002 are x-error-codes of submitTransaction`() {
        contractCodes shouldContainAll RecordingMapping.RELAY_CODES + RecordingOutcome.Stale.ERROR_CODE
    }

    @Test
    fun `a relay set holding ING-013 is not covered by the contract`() {
        contractCodes shouldNotContain "ING-013"
        (contractCodes.containsAll(RecordingMapping.RELAY_CODES + "ING-013")) shouldBe false
    }
}
