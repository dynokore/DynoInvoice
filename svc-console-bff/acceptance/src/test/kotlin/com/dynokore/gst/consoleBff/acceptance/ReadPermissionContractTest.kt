package com.dynokore.gst.consoleBff.acceptance

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.consoleBff.adapters.authz.CatalogueReadPermissions
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** INV-AC-20321: the read-permission table covers every GET operation of the pinned contract (OWASP ASVS V4.1.1). */
class ReadPermissionContractTest {
    private data class Op(
        val operationId: String,
        val path: String,
    )

    private val openapi = File(File(System.getProperty("gst.contract.dir")), "openapi.yaml").readText()

    /** The GET operations of an OpenAPI text by operationId. */
    private fun getOperations(spec: String): List<Op> {
        val paths = (Yaml().load<Map<String, Any?>>(spec)["paths"] as? Map<*, *>).orEmpty()
        return paths.entries.flatMap { (path, item) ->
            (item as? Map<*, *>)
                .orEmpty()
                .entries
                .filter { (it.key as? String)?.lowercase() == "get" }
                .mapNotNull {
                    ((it.value as? Map<*, *>)?.get("operationId") as? String)?.let { id ->
                        Op(id, path as String)
                    }
                }
        }
    }

    private fun allKeys(): Set<String> = DeploymentForm.entries.flatMap { OperationRoles.keys(it) }.toSet()

    /** Every offence of the criterion, each naming the offender; empty when the table covers the contract. */
    private fun findings(
        contract: List<Op>,
        table: List<CatalogueReadPermissions.Entry>,
        unmapped: Set<String>,
        keys: Set<String>,
    ): List<String> {
        val out = mutableListOf<String>()
        val contractIds = contract.map { it.operationId }.toSet()
        val tableIds = table.map { it.operationId }.toSet()
        (contractIds - tableIds - unmapped).sorted().forEach {
            out +=
                "GET operation not in the table or the unmapped list: $it"
        }
        (tableIds - contractIds).sorted().forEach { out += "table entry is not a GET operation of the contract: $it" }
        (unmapped - contractIds).sorted().forEach {
            out += "unmapped entry is not a GET operation of the contract: $it"
        }
        (unmapped.intersect(tableIds)).sorted().forEach { out += "operation both in the table and unmapped: $it" }
        table.forEach { e ->
            val op = contract.firstOrNull { it.operationId == e.operationId }
            if (op != null && (e.method != "GET" || e.pathTemplate != op.path)) {
                out += "table entry ${e.operationId} is ${e.method} ${e.pathTemplate}, contract has GET ${op.path}"
            }
            if (e.permissionKey !in
                keys
            ) {
                out += "table key of ${e.operationId} is not listed by the generated map: ${e.permissionKey}"
            }
        }
        return out
    }

    private fun real() =
        findings(getOperations(openapi), CatalogueReadPermissions.ENTRIES, EXPECTED_UNMAPPED, allKeys())

    @Test
    fun `INV-AC-20321 the table covers every GET operation of the pinned contract with listed keys`() {
        val offences = real()
        check(offences.isEmpty()) { offences.joinToString("; ") }
        check(getOperations(openapi).isNotEmpty()) { "the pinned contract lists no GET operation" }
    }

    @Test
    fun `INV-AC-20321 a contract GET operation absent from the table and the unmapped list fails`() {
        val extra =
            openapi.replaceFirst(
                "paths:",
                "paths:\n  /v1/probe:\n    get:\n      operationId: probeOperation\n" +
                    "      responses:\n        '200':\n          description: ok",
            )
        val offences = findings(getOperations(extra), CatalogueReadPermissions.ENTRIES, EXPECTED_UNMAPPED, allKeys())
        check(offences == listOf("GET operation not in the table or the unmapped list: probeOperation")) { "$offences" }
    }

    @Test
    fun `INV-AC-20321 a table entry that is not a GET operation of the contract fails`() {
        val post = CatalogueReadPermissions.Entry("GET", "/v1/exceptions", "overrideException", "exception.view")
        val offences =
            findings(getOperations(openapi), CatalogueReadPermissions.ENTRIES + post, EXPECTED_UNMAPPED, allKeys())
        check(offences.any { it.contains("not a GET operation of the contract: overrideException") }) { "$offences" }
    }

    @Test
    fun `INV-AC-20321 a path template different from the contract fails`() {
        val moved = CatalogueReadPermissions.ENTRIES.map { it.copy(pathTemplate = it.pathTemplate + "/") }
        val offences = findings(getOperations(openapi), moved, EXPECTED_UNMAPPED, allKeys())
        check(offences.any { it.contains("contract has GET") }) { "$offences" }
    }

    @Test
    fun `INV-AC-20321 a key one character off or absent from the generated map fails`() {
        val off = CatalogueReadPermissions.ENTRIES.map { it.copy(permissionKey = it.permissionKey + "s") }
        val offences = findings(getOperations(openapi), off, EXPECTED_UNMAPPED, allKeys())
        check(offences.any { it.contains("exception.views") }) { "$offences" }
    }

    @Test
    fun `INV-AC-26281 GET v1 portfolio maps to period-readiness view and is no longer unmapped`() {
        val portfolio =
            CatalogueReadPermissions.ENTRIES.filter {
                it.operationId == "getPortfolio" || it.pathTemplate == "/v1/portfolio"
            }
        check(portfolio.size == 1) { "expected exactly one portfolio entry: $portfolio" }
        val e = portfolio.single()
        check(e.method == "GET") { "method: ${e.method}" }
        check(e.pathTemplate == "/v1/portfolio") { "path template: ${e.pathTemplate}" }
        check(e.operationId == "getPortfolio") { "operation id: ${e.operationId}" }
        check(e.permissionKey == "period-readiness.view") { "key: ${e.permissionKey}" }
        check("period-readiness.view" in allKeys()) { "key not in the generated map of the pinned catalogue" }
        check("getPortfolio" !in EXPECTED_UNMAPPED) { "getPortfolio is still listed as unmapped" }
    }

    private companion object {
        /** GET operations of the contract that have no permission decision yet (the filter refuses their routes). */
        val EXPECTED_UNMAPPED: Set<String> =
            setOf(
                "getExceptionDecisionTrace",
                "getOverridePreview",
                "searchDocuments",
                "compareDocumentVersions",
                "previewConfigurationPromotion",
            )
    }
}
