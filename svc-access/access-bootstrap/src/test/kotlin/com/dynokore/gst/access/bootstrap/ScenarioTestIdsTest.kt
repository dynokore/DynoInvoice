package com.dynokore.gst.access.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * INV-T-24194: the ten scenario tests the certification kit references have space-free
 * `<fully qualified class>#<method>` ids, and each holds its criterion as a whole token
 * (the key at the start or directly after a comma, directly followed by a comma or the end).
 */
class ScenarioTestIdsTest {
    private data class Scenario(
        val criterion: String,
        val className: String,
        val method: String,
    )

    private fun scenario(
        criterion: String,
        simpleClass: String,
        method: String,
    ) = Scenario(criterion, "com.dynokore.gst.access.bootstrap.$simpleClass", method)

    private val designated =
        listOf(
            scenario(
                "INV-AC-920",
                "MachinePermissionTest",
                "INV-AC-920,and,INV-AC-8615,the,set,test,is,the,replica's,,a,foreign,type's,key,is,not,refused,here",
            ),
            scenario(
                "INV-AC-922",
                "OperatorSessionTest",
                "INV-AC-922,content,mode,is,MASKED,without,an,elevation,and,ELEVATED,during,one,,only,for,its,holder",
            ),
            scenario(
                "INV-AC-925",
                "AcceptanceCriteriaTest",
                "INV-AC-925,a,deployment,operator,is,refused,invoice,content,but,may,export,the,support,bundle",
            ),
            scenario(
                "INV-AC-933",
                "ConsentAcceptanceTest",
                "INV-AC-933,after,FILE_GST_RETURN,is,withdrawn,the,next,filing,operation,is,refused",
            ),
            scenario(
                "INV-AC-1047",
                "CatalogueAcceptanceTest",
                "INV-AC-1047,INV-AC-7955,private,tax,approver,arms,,deployment,operator,imports,only",
            ),
            scenario(
                "INV-AC-1048",
                "ApprovalInstallationFactTest",
                "INV-AC-1048,the,installer,records,the,fact,,a,later,value,updates,it,and,a,read,answers,it",
            ),
            scenario(
                "INV-AC-1049",
                "ApprovalPackDecisionTest",
                "INV-AC-2246,INV-AC-1908,INV-AC-1049,the,default,refuses,the,author," +
                    "and,allows,a,different,staff,approver",
            ),
            scenario(
                "INV-AC-1050",
                "ApprovalPackDecisionTest",
                "INV-AC-2248,INV-AC-1050,INV-AC-9557,the,agent,default,needs,a,second,agent,person,and,the,client",
            ),
            scenario(
                "INV-AC-1063",
                "ApprovalInstallationFactTest",
                "INV-AC-9553,INV-AC-1063,a,deployment,without,a,distinct,approver,reads,false,and,one,with,reads,true",
            ),
            scenario(
                "INV-AC-2765",
                "SignedLinkVerifyTest",
                "INV-AC-2765,the,fifth,wrong,passcode,invalidates,the,link,and,raises,ACC-004",
            ),
        )

    /** Returns the violations of one tuple; empty when the tuple is accepted. */
    private fun violations(s: Scenario): List<String> {
        val cls = Class.forName(s.className)
        val method =
            cls.declaredMethods.firstOrNull { it.name == s.method }
                ?: return listOf("method absent")
        val found = mutableListOf<String>()
        if (!method.isAnnotationPresent(Test::class.java)) found += "no @Test"
        if (s.method.any { it.isWhitespace() }) found += "whitespace in name"
        if (!holdsWholeToken(s.method, s.criterion)) found += "criterion not a whole token"
        return found
    }

    private fun holdsWholeToken(
        name: String,
        key: String,
    ): Boolean {
        var from = name.indexOf(key)
        while (from >= 0) {
            val end = from + key.length
            val before = from == 0 || name[from - 1] == ','
            val after = end == name.length || name[end] == ','
            if (before && after) return true
            from = name.indexOf(key, from + 1)
        }
        return false
    }

    private val pkg = "com.dynokore.gst.access.bootstrap"
    private val fixture = ScenarioIdsFixture::class.java.name
    private val machine = designated.first { it.criterion == "INV-AC-920" }

    @Test
    fun `INV-AC-24193 the ten designated scenario methods are space-free tests holding their key as a whole token`() {
        assertThat(designated).hasSize(10)
        designated.forEach { assertThat(violations(it)).describedAs(it.method).isEmpty() }
    }

    @Test
    fun `INV-AC-24193 probe a method name holding a space is refused`() {
        val s = Scenario("INV-AC-920", fixture, "INV-AC-920,and spaces")
        assertThat(violations(s)).containsExactly("whitespace in name")
    }

    @Test
    fun `INV-AC-24193 probe the hyphen form of a name is refused`() {
        val s = Scenario("KEY-920", fixture, "KEY-920-and-hyphens")
        assertThat(violations(s)).containsExactly("criterion not a whole token")
    }

    @Test
    fun `INV-AC-24193 probe the underscore form of a name is refused`() {
        val s = Scenario("KEY-920", fixture, "KEY-920_and_underscores")
        assertThat(violations(s)).containsExactly("criterion not a whole token")
    }

    @Test
    fun `INV-AC-24193 probe the same method name in another class is absent there`() {
        val s = machine.copy(className = "$pkg.OperatorSessionTest")
        assertThat(violations(s)).containsExactly("method absent")
    }

    @Test
    fun `INV-AC-24193 probe a tuple naming an absent method is refused`() {
        assertThat(violations(machine.copy(method = machine.method + ",missing"))).containsExactly("method absent")
    }

    @Test
    fun `INV-AC-24193 probe a prefix look-alike criterion is refused`() {
        assertThat(violations(machine.copy(criterion = "INV-AC-92"))).containsExactly("criterion not a whole token")
    }

    @Test
    fun `INV-AC-24193 probe a trailing-digit look-alike criterion is refused`() {
        assertThat(violations(machine.copy(criterion = "INV-AC-9200"))).containsExactly("criterion not a whole token")
    }

    @Test
    fun `INV-AC-24193 probe a method without the test annotation is refused`() {
        val s = Scenario("INV-AC-920", fixture, "INV-AC-920,not,annotated")
        assertThat(violations(s)).containsExactly("no @Test")
    }
}

/** Fixture of the probes above; private, so JUnit does not run it. */
private class ScenarioIdsFixture {
    @Test
    fun `INV-AC-920,and spaces`() = Unit

    @Test
    fun `KEY-920-and-hyphens`() = Unit

    @Test
    fun `KEY-920_and_underscores`() = Unit

    fun `INV-AC-920,not,annotated`() = Unit
}
