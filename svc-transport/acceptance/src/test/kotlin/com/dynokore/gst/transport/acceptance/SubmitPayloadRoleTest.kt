package com.dynokore.gst.transport.acceptance

import com.dynokore.gst.transport.adapters.http.DespatchController
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

class SubmitPayloadRoleTest {
    private fun contractRole(): Any? {
        val openapi =
            Yaml().load<Map<String, Any?>>(
                File(File(System.getProperty("gst.contract.dir")), "openapi.yaml").readText(),
            )
        val operations =
            (openapi["paths"] as Map<*, *>)
                .values
                .flatMap { (it as Map<*, *>).values }
                .filterIsInstance<Map<*, *>>()
                .filter { it["operationId"] == "submitPayload" }
        operations.size shouldBe 1
        return (operations.single()["x-operation"] as Map<*, *>)["role"]
    }

    @Test
    fun `INV-Q-8335 the action asked of the authority decision equals the x-operation role of submitPayload`() {
        DespatchController.FILING_WORKFLOW shouldBe contractRole()
    }
}
