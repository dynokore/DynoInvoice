package com.dynokore.gst.controlPlane.adapters.contract

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import java.io.File

/** What the pinned control-plane contract says about the region and the plan reference (INV-Q-8683 A, INV-Q-8691 A). */
class ProvisionTenantContractTest {
    private val root: Map<String, Any?> =
        Yaml().load(File(System.getProperty("gst.contracts.dir"), "openapi.yaml").readText())
    private val schemas = (root["components"] as Map<*, *>)["schemas"] as Map<*, *>
    private val json = JsonMapper.builder().build()

    private fun requestSchemaName(operation: Map<*, *>): String? {
        val content = ((operation["requestBody"] as? Map<*, *>)?.get("content") as? Map<*, *>) ?: return null
        val ref = ((content["application/json"] as? Map<*, *>)?.get("schema") as? Map<*, *>)?.get("\$ref") as? String
        return ref?.substringAfterLast('/')
    }

    @Test
    fun `INV-AC-2293 INV-AC-8571 the operations whose request carries dataRegion are exactly provisionTenant`() {
        val withRegion = HashSet<String>()
        (root["paths"] as Map<*, *>).values.filterIsInstance<Map<*, *>>().forEach { item ->
            item.values.filterIsInstance<Map<*, *>>().forEach { operation ->
                val schema = requestSchemaName(operation)?.let { schemas[it] as? Map<*, *> }
                if ((schema?.get("properties") as? Map<*, *>)?.containsKey("dataRegion") == true) {
                    withRegion += operation["operationId"] as String
                }
            }
        }
        withRegion shouldBe setOf("provisionTenant")
    }

    private fun errors(payload: String): Int {
        val schema = json.writeValueAsString(schemas["ProvisionTenantRequest"])
        return SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(schema)
            .validate(payload, InputFormat.JSON)
            .size
    }

    private fun request(
        planRef: String = "PLAN-STANDARD",
        region: String? = "\"SG\"",
    ): String {
        val regionMember = region?.let { ""","dataRegion":$it""" }.orEmpty()
        return """{"contractReference":"CTR-1","legalName":"Acme","planRef":"$planRef",""" +
            """"firstAdministratorEmail":"a@b.example"$regionMember}"""
    }

    @Test
    fun `INV-AC-8570 INV-AC-2293 a request without dataRegion fails the schema and one with SG passes`() {
        errors(request()) shouldBe 0
        (errors(request(region = null)) > 0) shouldBe true
    }

    @Test
    fun `INV-Q-8691 a planRef of 64 characters passes the schema and one of 65 does not`() {
        errors(request(planRef = "P".repeat(64))) shouldBe 0
        (errors(request(planRef = "P".repeat(65))) > 0) shouldBe true
    }
}
