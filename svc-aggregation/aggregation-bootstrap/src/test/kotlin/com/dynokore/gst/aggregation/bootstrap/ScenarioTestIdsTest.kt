package com.dynokore.gst.aggregation.bootstrap

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * INV-T-24158: the six scenario tests the certification kit references have space-free ids
 * `<fully qualified class>#<method>` that start with their criterion key and a comma.
 */
class ScenarioTestIdsTest {
    private data class Tuple(
        val criterion: String,
        val className: String,
        val method: String,
    )

    private val pkg = "com.dynokore.gst.aggregation.bootstrap"

    private val tuples =
        listOf(
            Tuple(
                "INV-AC-448",
                "$pkg.DrillDownBoundaryTest",
                "INV-AC-448,a,transmitted,aggregate,lists,all,its,underlying,transactions,,counted,as,declared",
            ),
            Tuple(
                "INV-AC-454",
                "$pkg.AggregateHandOffBoundaryTest",
                "INV-AC-454,a,POS,day,with,SR,and,ZR,sales,gives,one,aggregate,with,two,lines",
            ),
            Tuple(
                "INV-AC-455",
                "$pkg.AggregateContentBoundaryTest",
                "INV-AC-455,sales,and,refunds,of,one,window,give,two,aggregates,and,the,sales,are,not,netted",
            ),
            Tuple(
                "INV-AC-456",
                "$pkg.AggregateHandOffBoundaryTest",
                "INV-AC-456,INV-REQ-434,late,POS,lines,after,transmission,give,a,supplementary,aggregate",
            ),
            Tuple(
                "INV-AC-457",
                "$pkg.TransactionIntakeBoundaryTest",
                "INV-AC-457,a,weekly,window,from,28,Mar,to,3,Apr,is,split,into,two,aggregates,at,31,Mar",
            ),
            Tuple(
                "INV-AC-458",
                "$pkg.ReplacedReceiptBoundaryTest",
                "INV-AC-458,a,replaced,receipt,is,excluded,,not,aggregated,,or,refused,with,ING-014,once,sent",
            ),
        )

    /** Violations of one (criterion, class, method) tuple; empty when the id is accepted. */
    private fun violations(
        criterion: String,
        className: String,
        method: String,
    ): List<String> {
        val type = Class.forName(className)
        val found = type.declaredMethods.filter { it.name == method }
        if (found.isEmpty()) return listOf("no declared method $method in $className")
        val out = mutableListOf<String>()
        if (found.none { it.isAnnotationPresent(Test::class.java) }) out += "method $method has no @Test"
        if (method.any { it.isWhitespace() }) out += "method name holds whitespace"
        if (!method.startsWith("$criterion,")) out += "method name does not start with $criterion,"
        return out
    }

    private fun check(index: Int) {
        val t = tuples[index]
        violations(t.criterion, t.className, t.method) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24157 designated method 1 has a space-free id`() = check(0)

    @Test
    fun `INV-AC-24157 designated method 2 has a space-free id`() = check(1)

    @Test
    fun `INV-AC-24157 designated method 3 has a space-free id`() = check(2)

    @Test
    fun `INV-AC-24157 designated method 4 has a space-free id`() = check(3)

    @Test
    fun `INV-AC-24157 designated method 5 has a space-free id`() = check(4)

    @Test
    fun `INV-AC-24157 designated method 6 has a space-free id`() = check(5)

    @Disabled("fixture for the probes, never executed")
    class SpaceFixture {
        @Test
        fun `INV-AC-454, with a space`() = Unit
    }

    @Disabled("fixture for the probes, never executed")
    class NoTestFixture {
        fun `INV-AC-454,without,the,annotation`() = Unit
    }

    @Test
    fun `INV-AC-24157 probe a method name holding a space fails`() {
        violations("INV-AC-454", "$pkg.ScenarioTestIdsTest\$SpaceFixture", "INV-AC-454, with a space") shouldBe
            listOf("method name holds whitespace")
    }

    @Test
    fun `INV-AC-24157 probe the hyphen form of a name fails as absent`() {
        val method = tuples[1].method.replace(',', '-')
        violations("INV-AC-454", tuples[1].className, method) shouldBe
            listOf("no declared method $method in ${tuples[1].className}")
    }

    @Test
    fun `INV-AC-24157 probe the underscore form of a name fails as absent`() {
        val method = tuples[1].method.replace(',', '_')
        violations("INV-AC-454", tuples[1].className, method) shouldBe
            listOf("no declared method $method in ${tuples[1].className}")
    }

    @Test
    fun `INV-AC-24157 probe the same method name in another class fails as absent`() {
        val other = tuples[0].className
        violations("INV-AC-455", other, tuples[2].method) shouldBe
            listOf("no declared method ${tuples[2].method} in $other")
    }

    @Test
    fun `INV-AC-24157 probe a tuple naming an absent method fails`() {
        violations("INV-AC-454", tuples[1].className, "INV-AC-454,absent") shouldBe
            listOf("no declared method INV-AC-454,absent in ${tuples[1].className}")
    }

    @Test
    fun `INV-AC-24157 probe a prefix look-alike criterion fails`() {
        violations("INV-AC-45", tuples[1].className, tuples[1].method) shouldBe
            listOf("method name does not start with INV-AC-45,")
    }

    @Test
    fun `INV-AC-24157 probe a method without the Test annotation fails`() {
        violations(
            "INV-AC-454",
            "$pkg.ScenarioTestIdsTest\$NoTestFixture",
            "INV-AC-454,without,the,annotation",
        ) shouldBe
            listOf("method INV-AC-454,without,the,annotation has no @Test")
    }
}
