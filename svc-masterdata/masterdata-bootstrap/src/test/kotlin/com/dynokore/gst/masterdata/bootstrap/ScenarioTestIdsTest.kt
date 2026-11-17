package com.dynokore.gst.masterdata.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * INV-T-24134: the seven scenario tests the certification kit references have ids
 * `<fully qualified class>#<method>` that the kit TEST_ID rule and the acKeyCoverage gate accept.
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
                "INV-AC-343,INV-AC-8374,a,customer,with,no,UEN,and,a,scheme,other,than,NONE,resolves,with,MDM-002",
            ),
            Tuple(
                "INV-AC-344",
                "$pkg.CounterpartyResolutionTest",
                "INV-AC-344,INV-AC-8371,an,individual,under,a,personal,name,carries,NA,in,UEN,,name,and,address,and,passes",
            ),
            Tuple(
                "INV-AC-345",
                "$pkg.CounterpartyResolutionTest",
                "INV-AC-345,INV-AC-8371,an,overseas,customer,carries,its,registration,number,as,UEN,and,passes",
            ),
            Tuple(
                "INV-AC-357",
                "$pkg.ImportTest",
                "INV-AC-357,a,5000,row,file,with,40,invalid,UENs,and,12,duplicates,lists,52,rows,and,writes,nothing",
            ),
            Tuple(
                "INV-AC-760",
                "$pkg.CounterpartySamenessTest",
                "INV-AC-1823,INV-AC-760,resolution,sets,sameTenant,and,sameGroup,from,the,structure,events",
            ),
            Tuple(
                "INV-AC-360",
                "$pkg.RegistryNetworkRegistrationTest",
                "INV-AC-360,an,entity,whose,own,UEN,is,not,found,has,the,item,outstanding,and,a,miss,keeps,it,so",
            ),
            Tuple(
                "INV-AC-845",
                "$pkg.ConnectionHealthWiringTest",
                "INV-AC-845,a,credential,expiring,in,10,days,raises,a,reminder,and,rotation,with,overlap,fails,no,call",
            ),
        )

    private val absent = "absent"
    private val noTest = "no @Test"
    private val whitespace = "whitespace"
    private val notToken = "key not a whole token"

    /** Reflection: (method found by name equality, JUnit @Test present). */
    private fun lookup(
        className: String,
        method: String,
    ): Pair<Boolean, Boolean> {
        val m = runCatching { Class.forName(className) }.getOrNull()?.declaredMethods?.firstOrNull { it.name == method }
        return Pair(m != null, m?.isAnnotationPresent(Test::class.java) == true)
    }

    private fun glued(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_' || c == '-')

    /** Same rule as wholeToken of the acKeyCoverage gate. */
    private fun wholeToken(
        name: String,
        criterion: String,
    ): Boolean =
        Regex(Regex.escape(criterion)).findAll(name).any { m ->
            val before = if (m.range.first == 0) null else name[m.range.first - 1]
            val after = if (m.range.last + 1 >= name.length) null else name[m.range.last + 1]
            !glued(before) && !glued(after)
        }

    /** The complete list of violations of one (criterion, class, method); empty when the id is accepted. */
    private fun violations(
        criterion: String,
        method: String,
        found: Boolean,
        hasTest: Boolean,
    ): List<String> =
        if (!found) {
            listOf(absent)
        } else {
            listOfNotNull(
                noTest.takeUnless { hasTest },
                whitespace.takeIf { method.any { ch -> ch.isWhitespace() } },
                notToken.takeUnless { wholeToken(method, criterion) },
            )
        }

    private fun probe(
        method: String,
        hasTest: Boolean = true,
    ) = violations("INV-AC-343", method, found = true, hasTest = hasTest)

    private fun real(t: Tuple): List<String> {
        val (found, hasTest) = lookup(t.className, t.method)
        return violations(t.criterion, t.method, found, hasTest)
    }

    @Test
    fun `INV-AC-24133 each of the seven designated methods has a space-free id and its key as a whole token`() {
        assertThat(tuples).hasSize(7)
        tuples.forEach { tuple -> assertThat(real(tuple)).describedAs(tuple.method).isEmpty() }
    }

    @Test
    fun `INV-AC-24133 probe a method name holding a space gives the whitespace violation only`() {
        assertThat(probe("INV-AC-343 a b")).containsExactly(whitespace)
    }

    @Test
    fun `INV-AC-24133 probe the same method name in another class of the package is absent`() {
        val t = tuples[0].copy(className = "$pkg.CounterpartySamenessTest")
        assertThat(real(t)).containsExactly(absent)
    }

    @Test
    fun `INV-AC-24133 probe a tuple naming an absent method`() {
        assertThat(real(tuples[0].copy(method = tuples[0].method + ",x"))).containsExactly(absent)
    }

    @Test
    fun `INV-AC-24133 probe a prefix look-alike criterion is not a whole token`() {
        assertThat(real(tuples[4].copy(criterion = "INV-AC-76"))).containsExactly(notToken)
    }

    @Test
    fun `INV-AC-24133 probe a hyphen after the criterion key is not a whole token`() {
        assertThat(probe("INV-AC-343-x")).containsExactly(notToken)
    }

    @Test
    fun `INV-AC-24133 probe an underscore after the criterion key is not a whole token`() {
        assertThat(probe("INV-AC-343_x")).containsExactly(notToken)
    }

    @Test
    fun `INV-AC-24133 probe a method without the Test annotation gives the annotation violation only`() {
        assertThat(probe("INV-AC-343,x", hasTest = false)).containsExactly(noTest)
    }
}
