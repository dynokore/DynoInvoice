package com.dynokore.gst.consoleBff.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// Walking-skeleton check of INV-AC-14431: every operationId of the pinned openapi.yaml is a method of a server
// interface that the generator emitted into the main output of this module.
class GeneratedOperationsTest {
    private val resource = "gst/contracts/console-bff/openapi.yaml"
    private val apiPackage = "com.dynokore.gst.consoleBff.contract.api"

    private val expected =
        setOf(
            "getExceptions",
            "overrideException",
            "createAuditPackExport",
            "correctPersonalData",
            "copyAnonymisedSample",
            "exportSupportBundle",
        )

    private fun pinnedOperationIds(): Set<String> {
        val text =
            checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) { "classpath resource $resource is missing" }
                .use { it.readBytes().toString(Charsets.UTF_8) }
        return Regex("""(?m)^\s*operationId:\s*['"]?([A-Za-z0-9_]+)['"]?\s*$""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun generatedInterfaceMethods(): Set<String> {
        val dirUrl =
            checkNotNull(javaClass.classLoader.getResource(apiPackage.replace('.', '/'))) {
                "no generated package $apiPackage in the main output"
            }
        val dir = File(dirUrl.toURI())
        val classes = dir.listFiles { f -> f.name.endsWith(".class") && !f.name.contains('$') }.orEmpty()
        return classes
            .map { Class.forName(apiPackage + "." + it.name.removeSuffix(".class")) }
            .filter { it.isInterface }
            .flatMap { it.declaredMethods.map { m -> m.name } }
            .toSet()
    }

    @Test
    fun `INV-AC-14431 every operationId of the pinned openapi yaml is a method of a generated server interface`() {
        val ids = pinnedOperationIds()
        assertTrue(ids.containsAll(expected), "the pinned contract lacks ${expected - ids}")
        val methods = generatedInterfaceMethods()
        val missing = ids.filterNot { it in methods }
        assertEquals(emptyList<String>(), missing, "operationIds without a generated interface method")
    }
}
