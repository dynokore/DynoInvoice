package com.dynokore.gst.canonical.acceptance

import com.dynokore.gst.canonical.domain.document.DocUuidSource
import com.dynokore.gst.canonical.domain.document.DocumentFunction
import com.dynokore.gst.canonical.domain.inbound.BuyerDisposition
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/** The enums of the inbound decisions equal the `enum` lists of the pinned `canonical/openapi.yaml` (INV-T-6698). */
class InboundEnumContractTest {
    private val contractDir = File(System.getProperty("gst.contract.dir"))
    private val schemas: Map<*, *> =
        (
            Yaml().load<Map<String, Any?>>(
                File(contractDir, "openapi.yaml").readText(),
            )["components"] as Map<*, *>
        )["schemas"] as Map<*, *>

    /** The schema (of components.schemas) that holds a property [property] with an enum, found by name. */
    private fun enumsNamed(property: String): List<Set<String>> =
        schemas.values.mapNotNull { schema ->
            val props = (schema as? Map<*, *>)?.get("properties") as? Map<*, *> ?: return@mapNotNull null
            (props[property] as? Map<*, *>)?.get("enum")?.let { l -> (l as List<*>).map { it.toString() }.toSet() }
        }

    @Test
    fun `buyerDisposition enum of networkReceipt equals BuyerDisposition`() {
        val nested =
            schemas.values
                .mapNotNull { s ->
                    (((s as? Map<*, *>)?.get("properties") as? Map<*, *>)?.get("networkReceipt") as? Map<*, *>)
                        ?.get("properties") as? Map<*, *>
                }.mapNotNull { (it["buyerDisposition"] as? Map<*, *>)?.get("enum") as? List<*> }
        nested.map { l -> l.map { it.toString() }.toSet() } shouldBe
            listOf(BuyerDisposition.entries.map { it.name }.toSet())
    }

    @Test
    fun `every documentFunction enum equals DocumentFunction`() {
        val names = DocumentFunction.entries.map { it.name }.toSet()
        enumsNamed("documentFunction").also { it.isNotEmpty() shouldBe true }.forEach { it shouldBe names }
    }

    @Test
    fun `every docUuidSource enum equals DocUuidSource`() {
        val names = DocUuidSource.entries.map { it.name }.toSet()
        enumsNamed("docUuidSource").also { it.isNotEmpty() shouldBe true }.forEach { it shouldBe names }
    }
}
