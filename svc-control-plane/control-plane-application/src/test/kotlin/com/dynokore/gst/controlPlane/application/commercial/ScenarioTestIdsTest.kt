package com.dynokore.gst.controlPlane.application.commercial

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
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

    /** A method without @Test, for the probe. */
    @Suppress("unused")
    fun helperWithoutTest() = Unit

    @Test
    fun `INV-AC-24140 both designated scenario methods exist, carry @Test, hold no whitespace and hold their key`() {
        DESIGNATED.forEach { problems(it).shouldBeEmpty() }
    }

    @Test
    fun `INV-AC-24140 probe a method name holding a space fails`() {
        problems(Tuple("INV-AC-935", SCENARIO_CLASS, M935.replace('_', ' '))).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-24140 probe the hyphen form of the INV-AC-935 name fails`() {
        problems(Tuple("INV-AC-935", SCENARIO_CLASS, M935.replace('_', '-'))).shouldNotBeEmpty()
        holdsKey("INV-AC-935-the-first-invitation", "INV-AC-935").shouldBeFalse()
    }

    @Test
    fun `INV-AC-24140 probe the hyphen form of the INV-AC-1331 name fails`() {
        problems(Tuple("INV-AC-1331", SCENARIO_CLASS, M1331.replace('_', '-'))).shouldNotBeEmpty()
        holdsKey("INV-AC-1331-a-Singapore-request", "INV-AC-1331").shouldBeFalse()
    }

    @Test
    fun `INV-AC-24140 probe the same method name in another class of the package fails`() {
        problems(Tuple("INV-AC-935", "$PKG.LicenceTest", M935)).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-24140 probe a tuple naming an absent method fails`() {
        problems(Tuple("INV-AC-935", SCENARIO_CLASS, "INV-AC-935_no_such_method")).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-24140 probe a prefix look-alike of the INV-AC-935 criterion fails`() {
        problems(Tuple("INV-AC-93", SCENARIO_CLASS, M935)).shouldNotBeEmpty()
    }

    @Test
    fun `INV-AC-24140 probe a method without @Test fails`() {
        val t = Tuple("INV-AC-24140", "$PKG.ScenarioTestIdsTest", "helperWithoutTest")
        problems(t).shouldNotBeEmpty()
    }
}
