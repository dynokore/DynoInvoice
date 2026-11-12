package com.dynokore.gst.evidence.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * INV-T-20714, INV-AC-20713, INV-REQ-942: the platform-contracts bundle this service pins declares
 * `getDecisionTrace` and `generateSubjectAccessExport`, and `operations.tsv` names both as UNASSIGNED.
 */
class ContractPinTest {
    private val rows: List<List<String>> by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "operations.tsv").isFile) dir = dir.parentFile
        checkNotNull(dir) { "operations.tsv not found above ${File("").absoluteFile}" }
        File(dir, "operations.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
    }

    private fun operationRows(id: String) = rows.filter { it.size == 3 && it[0] == "operation" && it[1] == id }

    @Test
    @DisplayName("INV-AC-20713 the generated LookupApi has getDecisionTrace")
    fun lookupApiHasGetDecisionTrace() {
        // Compile-time function reference: the build fails if the pinned bundle drops the operation.
        val reference = com.dynokore.gst.evidence.contract.api.LookupApi::getDecisionTrace
        reference.name shouldBe "getDecisionTrace"
    }

    @Test
    @DisplayName("INV-AC-20713 a generated evidence API interface has generateSubjectAccessExport")
    fun evidenceApiHasGenerateSubjectAccessExport() {
        val reference = com.dynokore.gst.evidence.contract.api.PrivacyApi::generateSubjectAccessExport
        reference.name shouldBe "generateSubjectAccessExport"
    }

    @Test
    @DisplayName("INV-AC-20713 operations.tsv has one UNASSIGNED row each for the two operations")
    fun operationsTsvNamesBoth() {
        operationRows("getDecisionTrace") shouldBe listOf(listOf("operation", "getDecisionTrace", "UNASSIGNED"))
        operationRows("generateSubjectAccessExport") shouldBe
            listOf(listOf("operation", "generateSubjectAccessExport", "UNASSIGNED"))
    }

    @Test
    @DisplayName("INV-AC-20713 the look-alike ids have no row")
    fun lookAlikesHaveNoRow() {
        operationRows("getDecisionTraces") shouldBe emptyList()
        operationRows("generateSubjectAccessExports") shouldBe emptyList()
    }
}
