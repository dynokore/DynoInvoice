package com.dynokore.gst.controlPlane.acceptance.architecture

import com.lemonappdev.konsist.api.Konsist
import gst.build.konsist.ArchitectureRules
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The probes of the layer rule (INV-T-4984, owner answer INV-Q-5331 (D)): an import added alone to a domain class. A
 * Spring or jOOQ import makes the rule fail; a `java.sql` import passes (data types and exceptions are not restricted).
 * The same probes were also run once against the real `control-plane-domain` and shown in the merge request.
 */
class DomainLayerProbeTest {
    private fun importOf(
        layer: String,
        domainImport: String?,
    ) = if (layer == "domain" && domainImport != null) "import $domainImport\n" else ""

    private fun layers(domainImport: String?): Boolean {
        val id = System.nanoTime()
        val base = File(Repository.root, "acceptance/build/probes/$id")

        fun put(
            layer: String,
            body: String,
        ) = File(base, "$layer/X.kt")
            .also {
                it.parentFile.mkdirs()
            }.writeText(
                "package $SERVICE_PACKAGE.$layer\n${importOf(layer, domainImport)}$body\n",
            )
        listOf("domain", "application", "adapters", "bootstrap", "contract").forEach { put(it, "class X") }
        val scope = Konsist.scopeFromDirectory("acceptance/build/probes/$id")
        return runCatching { ArchitectureRules.assertServiceModuleDirection(SERVICE_PACKAGE, scope) }.isSuccess
    }

    @Test
    fun `a clean domain passes the layer rule`() {
        layers(null) shouldBe true
    }

    @Test
    fun `a jOOQ import in the domain fails the layer rule`() {
        layers("org.jooq.DSLContext") shouldBe false
    }

    @Test
    fun `a Spring import in the domain fails the layer rule`() {
        layers("org.springframework.stereotype.Component") shouldBe false
    }

    @Test
    fun `a java dot sql Timestamp import in the domain passes the layer rule`() {
        layers("java.sql.Timestamp") shouldBe true
    }

    @Test
    fun `a java dot sql SQLException import in the domain passes the layer rule`() {
        layers("java.sql.SQLException") shouldBe true
    }

    @Test
    fun `Jackson, servlet and JPA imports in the domain still fail the layer rule`() {
        layers("tools.jackson.databind.ObjectMapper") shouldBe false
        layers("com.fasterxml.jackson.annotation.JsonProperty") shouldBe false
        layers("jakarta.servlet.http.HttpServletRequest") shouldBe false
        layers("jakarta.persistence.Entity") shouldBe false
    }

    @Test
    fun `a domain class that depends on another layer fails`() {
        shouldThrow<Throwable> {
            val id = System.nanoTime()
            val base = File(Repository.root, "acceptance/build/probes/$id")
            listOf(
                "domain" to "import $SERVICE_PACKAGE.adapters.X\nclass X",
                "application" to "class X",
                "adapters" to "class X",
                "bootstrap" to "class X",
                "contract" to "class X",
            ).forEach { (layer, body) ->
                File(
                    base,
                    "$layer/X.kt",
                ).also { it.parentFile.mkdirs() }.writeText("package $SERVICE_PACKAGE.$layer\n$body\n")
            }
            ArchitectureRules.assertServiceModuleDirection(
                SERVICE_PACKAGE,
                Konsist.scopeFromDirectory("acceptance/build/probes/$id"),
            )
        }
    }
}
