package com.dynokore.gst.masterdata.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * INV-T-24134: the seven scenario tests the certification kit references have ids
 * `<fully qualified class>#<method>` that the kit TEST_ID rule accepts (no whitespace).
 */
class ScenarioTestIdsTest {
    private data class Tuple(
        val criterion: String,
        val className: String,
        val method: String,
    )

    private val pkg = "com.dynokore.gst.masterdata.bootstrap"

    @Suppress("MaxLineLength")
    private val tuples =
        listOf(
            Tuple(
                "INV-AC-343",
                "$pkg.CounterpartyResolutionTest",
                "INV-AC-343-INV-AC-8374-a-customer-with-no-UEN-and-a-scheme-other-than-NONE-resolves-with-MDM-002",
            ),
            Tuple(
                "INV-AC-344",
                "$pkg.CounterpartyResolutionTest",
                "INV-AC-344-INV-AC-8371-an-individual-under-a-personal-name-carries-NA-in-UEN,-name-and-address-and-passes",
            ),
            Tuple(
                "INV-AC-345",
                "$pkg.CounterpartyResolutionTest",
                "INV-AC-345-INV-AC-8371-an-overseas-customer-carries-its-registration-number-as-UEN-and-passes",
            ),
            Tuple(
                "INV-AC-357",
                "$pkg.ImportTest",
                "INV-AC-357-a-5000-row-file-with-40-invalid-UENs-and-12-duplicates-lists-52-rows-and-writes-nothing",
            ),
            Tuple(
                "INV-AC-760",
                "$pkg.CounterpartySamenessTest",
                "INV-AC-1823-INV-AC-760-resolution-sets-sameTenant-and-sameGroup-from-the-structure-events",
            ),
            Tuple(
                "INV-AC-360",
                "$pkg.RegistryNetworkRegistrationTest",
                "INV-AC-360-an-entity-whose-own-UEN-is-not-found-has-the-item-outstanding-and-a-miss-keeps-it-so",
            ),
            Tuple(
                "INV-AC-845",
                "$pkg.ConnectionHealthWiringTest",
                "INV-AC-845-a-credential-expiring-in-10-days-raises-a-reminder-and-rotation-with-overlap-fails-no-call",
            ),
        )

    /** Returns the violations of one tuple; empty when the id is accepted. */
    private fun violations(t: Tuple): List<String> {
        val cls = runCatching { Class.forName(t.className) }.getOrNull()
        val m = cls?.declaredMethods?.firstOrNull { it.name == t.method }
        val key = t.criterion + "-"
        return when {
            cls == null -> {
                listOf("class ${t.className} absent")
            }

            m == null -> {
                listOf("method ${t.method} absent in ${t.className}")
            }

            else -> {
                listOfNotNull(
                    "no @Test on ${t.method}".takeUnless { m.isAnnotationPresent(Test::class.java) },
                    "whitespace in ${t.method}".takeIf { m.name.any { ch -> ch.isWhitespace() } },
                    "${t.method} lacks $key at start or after a hyphen"
                        .takeUnless { m.name.startsWith(key) || m.name.contains("-$key") },
                )
            }
        }
    }

    @Test
    fun `INV-AC-24133 each of the seven designated methods has a space-free id with its criterion key`() {
        assertThat(tuples).hasSize(7)
        tuples.forEach { tuple -> assertThat(violations(tuple)).describedAs(tuple.method).isEmpty() }
    }

    @Test
    fun `INV-AC-24133 probe a method name holding a space fails`() {
        val t = Tuple("INV-AC-1", "$pkg.ScenarioTestIdsTest\$Fixture", "INV-AC-1 with a space")
        assertThat(violations(t)).anyMatch { it.startsWith("whitespace") }
    }

    @Test
    fun `INV-AC-24133 probe the same method name in another class fails`() {
        val t = tuples[0].copy(className = "$pkg.CounterpartySamenessTest")
        assertThat(violations(t)).anyMatch { it.contains("absent") }
    }

    @Test
    fun `INV-AC-24133 probe a tuple naming an absent method fails`() {
        val t = tuples[0].copy(method = tuples[0].method + "-x")
        assertThat(violations(t)).anyMatch { it.contains("absent") }
    }

    @Test
    fun `INV-AC-24133 probe a prefix look-alike criterion INV-AC-76 fails`() {
        val t = tuples[4].copy(criterion = "INV-AC-76")
        assertThat(violations(t)).anyMatch { it.contains("lacks") }
    }

    @Test
    fun `INV-AC-24133 probe a method without Test annotation fails`() {
        val t = Tuple("INV-AC-2", "$pkg.ScenarioTestIdsTest\$Fixture", "INV-AC-2-no-test-annotation")
        assertThat(violations(t)).anyMatch { it.startsWith("no @Test") }
    }

    @Suppress("unused")
    private class Fixture {
        fun `INV-AC-1 with a space`() = Unit

        fun `INV-AC-2-no-test-annotation`() = Unit
    }
}
