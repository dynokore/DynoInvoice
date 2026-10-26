package com.dynokore.gst.reconciliation.acceptance

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.reconciliation.domain.returns.RefusalReason
import com.dynokore.gst.reconciliation.domain.returns.ReturnForm
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** INV-T-7231: the return vocabulary equals the contract, and the refusal code is in the error catalogue. */
class ReturnContractTest {
    private val openapi: Map<*, *> =
        Yaml().load(File(System.getProperty("gst.contract.dir"), "openapi.yaml").readText())

    private fun map(
        node: Any?,
        key: String,
    ): Map<*, *> = (node as Map<*, *>)[key] as Map<*, *>

    @Test
    fun `INV-AC-1715 the values of ReturnForm equal the formType enum of UploadGstReturnRequest both ways`() {
        val schemas = map(map(openapi, "components"), "schemas")
        val contract =
            (map(map(schemas["UploadGstReturnRequest"], "properties"), "formType")["enum"] as List<*>).map {
                it as String
            }
        contract.toSet() shouldBe ReturnForm.entries.map { it.value }.toSet()
        contract.size shouldBe ReturnForm.entries.size
    }

    @Test
    fun `INV-AC-1715 the code of MISSING_FILING_ACKNOWLEDGEMENT is a catalogue error code`() {
        ErrorCode.entries.map { it.code }.contains(RefusalReason.MISSING_FILING_ACKNOWLEDGEMENT.code) shouldBe true
    }
}
