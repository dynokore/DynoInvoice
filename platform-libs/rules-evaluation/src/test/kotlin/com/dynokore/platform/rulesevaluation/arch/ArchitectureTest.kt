package com.dynokore.platform.rulesevaluation.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Layer and purity rule of rules-evaluation (INV-REQ-775, STK-60, ARC-16): evaluation reads no clock, store, network
 * or Spring type. Deny by default: the imports a file may have are its own package, the platform modules this module
 * is declared to use, gst-catalogue, Jackson (the decoders), `java.time.LocalDate` and `java.time.format`
 * (`DateTimeParseException`), `java.math.BigDecimal` (the classification) and Kotlin; every other import is a finding.
 */
class ArchitectureTest {
    private val ownPackage = "com.dynokore.platform.rulesevaluation"

    private val allowedPrefixes =
        listOf(
            "$ownPackage.",
            "com.dynokore.platform.rulepackkernel.",
            "com.dynokore.platform.tenancykernel.",
            "com.dynokore.gst.catalogue.",
            "tools.jackson.",
            "kotlin.",
            "java.time.format.",
        )

    // each exact import is a pure value type: LocalDate (dates), BigDecimal (the sign of a line amount, classification)
    private val allowedExact = setOf("java.time.LocalDate", "java.math.BigDecimal")

    private val testOnlyPrefixes =
        listOf(
            "com.lemonappdev.konsist.",
            "com.networknt.schema.",
            "io.kotest.",
            "org.junit.",
            "java.io.",
        )

    private fun allowed(
        import: String,
        test: Boolean,
    ) = allowedExact.contains(import) ||
        allowedPrefixes.any { import.startsWith(it) } ||
        (test && testOnlyPrefixes.any { import.startsWith(it) })

    private fun assertDenyByDefault(
        scope: KoScope,
        test: Boolean,
    ) = scope.files.assertTrue { file -> file.imports.all { allowed(it.name, test) } }

    @Test
    fun `rules-evaluation main imports only its own package, its declared modules and Jackson`() {
        assertDenyByDefault(Konsist.scopeFromProduction("rules-evaluation"), test = false)
    }

    @Test
    fun `rules-evaluation tests import nothing outside the allowlist either`() {
        assertDenyByDefault(Konsist.scopeFromTest("rules-evaluation"), test = true)
    }

    @Test
    fun `probe - the rule fails on a Spring, clock, network, store or service import`(
        @TempDir dir: File,
    ) {
        listOf(
            "org.springframework.stereotype.Component",
            "java.time.Clock",
            "java.time.Instant",
            "java.net.URI",
            "java.math.BigDecimalX",
            "java.math.BigInteger",
            "java.math.MathContext",
            "java.net.http.HttpClient",
            "javax.sql.DataSource",
            "org.jooq.DSLContext",
            "com.dynokore.platform.serviceruntime.Stub",
            "com.dynokore.platform.rulesevaluationx.Stub",
            "com.dynokore.platform.rulesevaluation.coding.Ok",
        ).forEachIndexed { i, import ->
            val only = File(dir, "only$i").also { it.mkdirs() }
            File(only, "Offender$i.kt").writeText("package $ownPackage\nimport $import\nclass Offender$i\n")
            if (import.endsWith(".Ok")) {
                assertDenyByDefault(Konsist.scopeFromExternalDirectory(only.absolutePath), test = false)
            } else {
                assertThrows(
                    Throwable::class.java,
                    { assertDenyByDefault(Konsist.scopeFromExternalDirectory(only.absolutePath), test = false) },
                    import,
                )
            }
        }
    }
}
