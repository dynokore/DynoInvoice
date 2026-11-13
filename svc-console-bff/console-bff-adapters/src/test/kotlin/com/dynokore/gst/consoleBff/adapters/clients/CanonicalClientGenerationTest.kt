package com.dynokore.gst.consoleBff.adapters.clients

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.web.client.RestClient
import java.lang.reflect.Modifier

/** INV-AC-21697: the canonical-svc client is generated from the pinned contract staged as a classpath resource. */
class CanonicalClientGenerationTest {
    private val apiPackage = "com.dynokore.gst.consoleBff.adapters.clients.api"
    private val resource = "gst/contracts/canonical/openapi.yaml"

    private fun operationIds(): Set<String> {
        val text =
            checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) { "missing classpath resource $resource" }
                .use { it.readBytes().toString(Charsets.UTF_8) }
        return Regex("""^\s*operationId:\s*['"]?([A-Za-z0-9_]+)['"]?\s*$""", RegexOption.MULTILINE)
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun apiClasses(): List<Class<*>> {
        val pattern = "classpath*:" + apiPackage.replace('.', '/') + "/*.class"
        val names =
            PathMatchingResourcePatternResolver(javaClass.classLoader)
                .getResources(pattern)
                .mapNotNull { it.filename }
                .filter { !it.contains('$') }
                .map { it.removeSuffix(".class") }
        return names.map { Class.forName("$apiPackage.$it") }
    }

    @Test
    @DisplayName("INV-AC-21697 (a) every operationId of the canonical contract is a public method of a generated api class")
    fun everyOperationIdIsAMethod() {
        val ids = operationIds()
        assertTrue(
            ids.containsAll(
                listOf("getDocument", "getDocumentVersion", "listPreparedReassignments", "confirmPeriodReassignment"),
            ),
            "operationIds read: $ids",
        )
        val methods =
            apiClasses()
                .flatMap { c -> c.methods.filter { Modifier.isPublic(it.modifiers) && it.declaringClass == c } }
                .map { it.name }
                .toSet()
        val missing = ids - methods
        assertEquals(emptySet<String>(), missing, "operationIds without a public api method")
    }

    @Test
    @DisplayName("INV-AC-21697 (b) every generated Api class has one public constructor taking a RestClient")
    fun everyApiClassTakesARestClient() {
        val apis = apiClasses().filter { it.simpleName.endsWith("Api") }
        assertTrue(apis.isNotEmpty(), "no Api class in $apiPackage")
        for (c in apis) {
            val ctor =
                c.constructors.filter { Modifier.isPublic(it.modifiers) && it.parameterCount == 1 }
            assertTrue(
                ctor.any { it.parameterTypes[0] == RestClient::class.java },
                "${c.name} has no public constructor (RestClient)",
            )
        }
    }
}
