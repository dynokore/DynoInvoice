package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.contract.model.AuthoriseResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.lang.reflect.Modifier

/**
 * The grant side of INV-AC-2546 (INV-Q-7451 A): neither the request nor the decision of `authorise` can carry a
 * sole-operator acknowledgement, so the second-confirmation refusal is decided only by masterdata-svc (INV-T-4942).
 */
class AuthoriseShapeTest {
    private val openApi =
        checkNotNull(javaClass.classLoader.getResourceAsStream("gst/contracts/access/openapi.yaml")) {
            "the access contract is not on the classpath"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    @Suppress("UNCHECKED_CAST")
    private fun responseProperties(spec: String): Set<String> {
        val schemas = (Yaml().load<Map<String, Any?>>(spec)["components"] as Map<String, Any?>)["schemas"]
        val response = (schemas as Map<String, Any?>)["AuthoriseResponse"] as Map<String, Any?>
        return (response["properties"] as Map<String, Any?>).keys
    }

    @Suppress("UNCHECKED_CAST")
    private fun queryParameters(spec: String): Set<String> {
        val paths = Yaml().load<Map<String, Any?>>(spec)["paths"] as Map<String, Map<String, Any?>>
        val operation =
            paths.values
                .flatMap { it.values }
                .filterIsInstance<Map<String, Any?>>()
                .single { it["operationId"] == "authorise" }
        val parameters = (operation["parameters"] as List<Map<String, Any?>>).filter { it["in"] == "query" }
        return parameters.map { it["name"] as String }.toSet()
    }

    @Test
    fun `INV-AC-2546 the authorise request and decision carry no sole-operator acknowledgement`() {
        assertThat(responseProperties(openApi)).containsExactlyInAnyOrder("decision", "role", "reasonCode")
        assertThat(queryParameters(openApi)).containsExactlyInAnyOrder("principalRef", "permission", "scope")
        val generated =
            AuthoriseResponse::class.java.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                .map { it.name }
        assertThat(generated).containsExactlyInAnyOrder("decision", "role", "reasonCode")
    }

    @Test
    fun `INV-AC-2546 probe - a contract copy with one more response property or query parameter fails the equality`() {
        val withProperty =
            addAfter(
                openApi,
                "AuthoriseResponse:",
                "properties:",
                "        soleOperatorAcknowledged:\n          type: boolean\n",
            )
        assertThat(responseProperties(withProperty)).contains("soleOperatorAcknowledged")
        assertThat(responseProperties(withProperty)).isNotEqualTo(setOf("decision", "role", "reasonCode"))
    }

    private fun addAfter(
        text: String,
        anchor: String,
        then: String,
        insert: String,
    ): String {
        val start = text.indexOf("\n    $anchor")
        require(start >= 0) { "anchor $anchor not found" }
        val at = text.indexOf("\n      $then\n", start)
        require(at >= 0) { "$then not found after $anchor" }
        val lineEnd = at + 1 + "      $then\n".length
        return text.substring(0, lineEnd) + insert + text.substring(lineEnd)
    }
}
