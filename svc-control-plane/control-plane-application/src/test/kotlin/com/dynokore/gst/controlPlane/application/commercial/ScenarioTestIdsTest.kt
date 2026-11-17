package com.dynokore.gst.controlPlane.application.commercial

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/** The kit references two scenario tests by `<class>#<method>`; the id must hold no whitespace. */
class ScenarioTestIdsTest {
    private data class Tuple(
        val criterion: String,
        val className: String,
        val method: String,
    )

    private companion object {
        const val PKG = "com.dynokore.gst.controlPlane.application.commercial"
        const val SCENARIO_CLASS = "$PKG.ProvisionTenantTest"
        const val M935 = "INV-AC-935_the_first_invitation_is_signed,_expires_after_the_call_and_is_messaged"
        const val M1331 =
            "INV-AC-8570_INV-AC-8572_INV-AC-1331_a_Singapore_request_is_recorded_and_every_resource_carries_SG"

        val DESIGNATED =
            listOf(
                Tuple("INV-AC-935", SCENARIO_CLASS, M935),
                Tuple("INV-AC-1331", SCENARIO_CLASS, M1331),
            )

        // Whole-token rule copied from AcKeyGate.holdsKey (acceptance gates, acKeyCoverage).
        fun holdsKey(
            name: String,
            key: String,
        ): Boolean {
            val rule = """(?<![A-Za-z0-9-])${Regex.escape(key)}(?![A-Za-z0-9]|-[A-Za-z0-9])"""
            return Regex(rule).containsMatchIn(name)
        }

        fun problems(t: Tuple): List<String> {
            val cls = runCatching { Class.forName(t.className) }.getOrNull()
            val m = cls?.declaredMethods?.firstOrNull { it.name == t.method }
            return when {
                cls == null -> {
                    listOf("class ${t.className} absent")
                }

                m == null -> {
                    listOf("method ${t.method} absent from ${t.className}")
                }

                else -> {
                    listOfNotNull(
                        "no @Test on ${t.method}".takeUnless { m.isAnnotationPresent(Test::class.java) },
                        "whitespace in ${t.method}".takeIf { t.method.any { c -> c.isWhitespace() } },
                        "${t.criterion} is not a whole token of ${t.method}"
                            .takeUnless { holdsKey(t.method, t.criterion) },
                    )
                }
            }
        }
    }

    /** Fixture methods, each breaking exactly one rule of `problems`; disabled so JUnit never runs them. */
    @Disabled("probe fixture, inspected by reflection only")
    class ProbeFixtures {
        @Test
        fun `INV-AC-935 a name holding spaces`() = Unit

        @Test
        fun `INV-AC-935-the-first-invitation-is-signed,-expires-after-the-call-and-is-messaged`() = Unit

        @Test
        fun `INV-AC-8570-INV-AC-8572-INV-AC-1331-a-Singapore-request-is-recorded-and-every-resource-carries-SG`() = Unit

        @Suppress("unused")
        fun `INV-AC-935_without_the_test_annotation`() = Unit
    }

    private fun probe(
        criterion: String,
        method: String,
    ) = problems(Tuple(criterion, "$PKG.ScenarioTestIdsTest\$ProbeFixtures", method))

    @Test
    fun `INV-AC-24140 both designated scenario methods exist, carry @Test, hold no whitespace and hold their key`() {
        DESIGNATED.forEach { problems(it).shouldBeEmpty() }
    }

    @Test
    fun `INV-AC-24140 probe a method name holding a space fails on the whitespace check only`() {
        val name = "INV-AC-935 a name holding spaces"
        probe("INV-AC-935", name) shouldBe listOf("whitespace in $name")
    }

    @Test
    fun `INV-AC-24140 probe the hyphen form of the INV-AC-935 name fails on the whole-token check only`() {
        val name = M935.replace('_', '-')
        probe("INV-AC-935", name) shouldBe listOf("INV-AC-935 is not a whole token of $name")
    }

    @Test
    fun `INV-AC-24140 probe the hyphen form of the INV-AC-1331 name fails on the whole-token check only`() {
        val name = M1331.replace('_', '-')
        probe("INV-AC-1331", name) shouldBe listOf("INV-AC-1331 is not a whole token of $name")
    }

    @Test
    fun `INV-AC-24140 probe the same method name in another class of the package fails as absent`() {
        problems(Tuple("INV-AC-935", "$PKG.LicenceTest", M935)) shouldBe
            listOf("method $M935 absent from $PKG.LicenceTest")
    }

    @Test
    fun `INV-AC-24140 probe a tuple naming an absent method fails as absent`() {
        val name = "INV-AC-935_no_such_method"
        problems(Tuple("INV-AC-935", SCENARIO_CLASS, name)) shouldBe listOf("method $name absent from $SCENARIO_CLASS")
    }

    @Test
    fun `INV-AC-24140 probe a prefix look-alike of the INV-AC-935 criterion fails on the whole-token check only`() {
        problems(Tuple("INV-AC-93", SCENARIO_CLASS, M935)) shouldBe listOf("INV-AC-93 is not a whole token of $M935")
    }

    @Test
    fun `INV-AC-24140 probe a method without the test annotation fails on the annotation check only`() {
        val name = "INV-AC-935_without_the_test_annotation"
        probe("INV-AC-935", name) shouldBe listOf("no @Test on $name")
    }
}
