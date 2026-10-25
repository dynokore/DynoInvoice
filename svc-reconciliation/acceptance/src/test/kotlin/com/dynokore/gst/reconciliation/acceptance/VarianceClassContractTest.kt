package com.dynokore.gst.reconciliation.acceptance

import com.dynokore.gst.reconciliation.domain.variance.VarianceClass
import com.dynokore.gst.reconciliation.domain.variance.VarianceClasses
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** INV-Q-7237 A: the domain vocabulary equals the contract schema `VarianceClass`, read from the staged bundle. */
class VarianceClassContractTest {
    private val openapi: Map<*, *> =
        Yaml().load(File(System.getProperty("gst.contract.dir"), "openapi.yaml").readText())

    private fun map(
        node: Any?,
        key: String,
    ): Map<*, *> = (node as Map<*, *>)[key] as Map<*, *>

    @Test
    fun `the values of VarianceClass equal the enum of the contract schema both ways`() {
        val schema = map(map(openapi, "components"), "schemas")["VarianceClass"] as Map<*, *>
        val contract = (schema["enum"] as List<*>).map { it as String }
        contract.toSet() shouldBe VarianceClass.entries.map { it.value }.toSet()
        contract.size shouldBe VarianceClass.entries.size
    }

    @Test
    fun `the five assignable values are named in the description of AnnotateVarianceRequest varianceClass`() {
        val schemas = map(map(openapi, "components"), "schemas")
        val description =
            map(
                map(schemas["AnnotateVarianceRequest"], "properties"),
                "varianceClass",
            )["description"] as String
        VarianceClasses.assignable.size shouldBe 5
        VarianceClasses.assignable.filterNot {
            Regex(
                "(?<![A-Z_])${it.value}(?![A-Z_])",
            ).containsMatchIn(description)
        } shouldBe
            emptyList()
    }
}
