package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.WriteCatalogue.AC_CONTROL
import com.dynokore.platform.configresolver.WriteCatalogue.AC_PARAMETER
import com.dynokore.platform.configresolver.WriteCatalogue.AC_POLICY
import com.dynokore.platform.configresolver.WriteCatalogue.DUE_DATE
import com.dynokore.platform.configresolver.WriteCatalogue.TENANT_CONTROL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

/**
 * INV-T-10128 (INV-Q-10027, INV-AC-10161, INV-AC-2224): the writer learns the caller's right only through
 * [SetterPermission]; false, an exception and no binding are POL-004 with nothing stored. Cases (a) to (e) per class
 * of row, named by its key.
 */
@Tag("integration")
class SetterPermissionPostgresIT : WritePathBase() {
    private val image = "postgres:17"
    private val v0 = listOf(SelectedOption("LAST_N", "2"))
    private val inBounds = listOf(SelectedOption("LAST_N", "3"))
    private val beyond = listOf(SelectedOption("LAST_N", "5"))
    private val holders =
        mapOf(
            AC_POLICY to "tenant.admin",
            AC_PARAMETER to "security.officer",
            AC_CONTROL to "tax.approver",
        )

    private fun holderOf(key: VariationPointKey) = actor("c1", holders.getValue(key))

    private fun other(key: VariationPointKey) = actor("c3", holders.entries.first { it.key != key }.value)

    private fun Run.stored(key: VariationPointKey) = resolve(key, tenantScope, nextMonth).selected

    /** A run with V0 stored for [key]. */
    private fun seeded(key: VariationPointKey): Run =
        Run(image).also { it.put(set(key, it.tenantScope, ScopeLevel.TENANT, v0, holderOf(key))) }

    private fun at(
        key: VariationPointKey,
        r: Run,
        value: List<SelectedOption>,
        actor: Actor,
    ) = set(key, r.tenantScope, ScopeLevel.TENANT, value, actor, from = nextMonth)

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["VP-91", "VP-92", "VP-93"])
    fun `INV-AC-10161 a in-bounds value from a holder of the key is accepted and stored`(row: String) {
        val key = VariationPointKey(row)
        val r = seeded(key)
        r.put(at(key, r, inBounds, holderOf(key)))
        assertEquals(inBounds, r.stored(key))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["VP-91", "VP-92", "VP-93"])
    fun `INV-AC-10161 a value one step beyond a bound from a holder is refused by the bounds check`(row: String) {
        val key = VariationPointKey(row)
        val r = seeded(key)
        assertEquals(PolicyRefusal.OUT_OF_BOUNDS, r.refusedPut(at(key, r, beyond, holderOf(key))))
        assertEquals(v0, r.stored(key))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["VP-91", "VP-92", "VP-93"])
    fun `INV-AC-10161 a caller holding only a role that is not a holder is refused with POL-004`(row: String) {
        val key = VariationPointKey(row)
        val r = seeded(key)
        assertEquals(
            PolicyRefusal.ROLE_NOT_ALLOWED,
            r.refusedPut(at(key, r, inBounds, actor("c2", "client.signatory"))),
        )
        assertEquals(v0, r.stored(key))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["VP-91", "VP-92", "VP-93"])
    fun `INV-AC-10161 a holder of another row's key is refused with POL-004`(row: String) {
        val key = VariationPointKey(row)
        val r = seeded(key)
        assertEquals(PolicyRefusal.ROLE_NOT_ALLOWED, r.refusedPut(at(key, r, inBounds, other(key))))
        assertEquals(v0, r.stored(key))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["VP-91", "VP-92", "VP-93"])
    fun `INV-AC-10161 an absent value from a holder is refused and the stored value stays`(row: String) {
        val key = VariationPointKey(row)
        val r = seeded(key)
        r.refusedPut(at(key, r, emptyList(), holderOf(key)))
        assertEquals(v0, r.stored(key))
    }

    @Test
    fun `a port answering true is accepted`() {
        val r = Run(image)
        val port = SetterPermission { _, _, _, _, _, key -> key == "vp-91.set" }
        r.put(set(AC_POLICY, r.tenantScope, ScopeLevel.TENANT, v0, actor("x")), r.h.writer(permission = port))
        assertEquals(v0, r.stored(AC_POLICY))
    }

    @Test
    fun `a port answering false is POL-004 and stores nothing`() {
        val r = Run(image)
        val port = SetterPermission { _, _, _, _, _, _ -> false }
        val cmd = set(AC_POLICY, r.tenantScope, ScopeLevel.TENANT, v0, actor("x"))
        assertEquals(PolicyRefusal.ROLE_NOT_ALLOWED, r.refusedPut(cmd, r.h.writer(permission = port)))
        assertEquals(0L, r.settings())
    }

    @Test
    fun `a port that throws is POL-004 and stores nothing`() {
        val r = Run(image)
        val port = SetterPermission { _, _, _, _, _, _ -> error("authority down") }
        val cmd = set(AC_POLICY, r.tenantScope, ScopeLevel.TENANT, v0, actor("x"))
        assertEquals(PolicyRefusal.ROLE_NOT_ALLOWED, r.refusedPut(cmd, r.h.writer(permission = port)))
        assertEquals(0L, r.settings())
    }

    @Test
    fun `an unbound port is POL-004 and stores nothing`() {
        val r = Run(image)
        val cmd = set(AC_POLICY, r.tenantScope, ScopeLevel.TENANT, v0, actor("x"))
        assertEquals(PolicyRefusal.ROLE_NOT_ALLOWED, r.refusedPut(cmd, r.h.writer(permission = null)))
        assertEquals(0L, r.settings())
    }

    @Test
    fun `the port is asked for the row's own key at the call's scope`() {
        val r = Run(image)
        val asked = mutableListOf<List<Any?>>()
        val port =
            SetterPermission { a, tenant, level, scopeRef, entityRef, key ->
                asked += listOf(a.principal, tenant, level, scopeRef, entityRef, key)
                true
            }
        r.put(set(AC_POLICY, r.tenantScope, ScopeLevel.TENANT, v0, actor("x")), r.h.writer(permission = port))
        assertEquals(listOf(listOf("x", r.t, ScopeLevel.TENANT, r.t.toString(), null, "vp-91.set")), asked)
    }

    @Test
    fun `tax approver and a trailing-space key are refused as a setting permission`() {
        listOf("tax.approver", "vp-91.set ", "VP-91.set", "vp-91.acknowledge", "vp-92.set").forEach { offered ->
            assertThrows(IllegalArgumentException::class.java) {
                WriteCatalogue.rows.getValue(AC_POLICY).copy(setPermission = offered)
            }
        }
    }

    @Test
    fun `a person holding only tax approver acknowledges as tax approver but not as client signatory`() {
        val r = Run(image)
        val client = PolicyScope.entity(r.t, "E1", clientEntity = "C1")
        val level = ScopeLevel.CLIENT_ENTITY
        val sel = one("NEXT_WORKING_DAY")
        val person = actor("ta", "tax.approver")
        val first = r.ack(ackCmd(DUE_DATE, client, level, sel, person, "tax.approver"))
        assertEquals(1L, r.acknowledgements())
        val second = ackCmd(DUE_DATE, client, level, sel, person, "client.signatory", first.relaxation)
        assertEquals(PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED, r.refusedAck(second))
        assertEquals(1L, r.acknowledgements())
    }

    @Test
    fun `two distinct holders of tax approver only give one acknowledgement, the second is refused`() {
        val r = Run(image)
        val client = PolicyScope.entity(r.t, "E1", clientEntity = "C1")
        val level = ScopeLevel.CLIENT_ENTITY
        val sel = one("NEXT_WORKING_DAY")
        val first = r.ack(ackCmd(DUE_DATE, client, level, sel, actor("a", "tax.approver"), "tax.approver"))
        val second =
            ackCmd(DUE_DATE, client, level, sel, actor("b", "tax.approver"), "client.signatory", first.relaxation)
        assertEquals(PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED, r.refusedAck(second))
        assertEquals(1L, r.acknowledgements())
    }

    @Test
    fun `an acknowledgement whose port answers false, throws or is unbound is refused`() {
        val ports =
            listOf<SetterPermission?>(
                SetterPermission { _, _, _, _, _, _ -> false },
                SetterPermission { _, _, _, _, _, _ -> error("down") },
                null,
            )
        ports.forEach { port ->
            val r = Run(image)
            val cmd =
                ackCmd(
                    TENANT_CONTROL,
                    r.tenantScope,
                    ScopeLevel.TENANT,
                    one("TENANT_ADMIN_SOLE"),
                    actor("a", "tenant.admin"),
                    "tenant.admin",
                )
            assertEquals(PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED, r.refusedAck(cmd, r.h.writer(permission = port)))
            assertEquals(0L, r.acknowledgements())
        }
    }

    @Test
    fun `an asRole in no place of the option is refused as before`() {
        val r = Run(image)
        val cmd =
            ackCmd(
                TENANT_CONTROL,
                r.tenantScope,
                ScopeLevel.TENANT,
                one("TENANT_ADMIN_SOLE"),
                actor("a", "tenant.admin"),
                "security.officer",
            )
        assertEquals(PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED, r.refusedAck(cmd))
        assertEquals(0L, r.acknowledgements())
    }

    /** A CONTROL row at TENANT level whose looser option OPEN has [places] as its acknowledgers. */
    private fun fixtureCatalogue(
        id: String,
        places: List<Acknowledger>,
        keys: Set<String>,
    ): VariationPointCatalogue {
        val key = VariationPointKey(id)
        val row =
            VariationPointDefinition(
                key,
                VpClass.CONTROL,
                emptySet(),
                VpStatus.AVAILABLE,
                setOf(ScopeLevel.TENANT),
                SetterPermission.settingKey(key),
                listOf(
                    OptionSpec("BASE", Strictness.DEFAULT),
                    OptionSpec("OPEN", Strictness.LOOSER, acknowledgers = places),
                ),
            )
        return object : VariationPointCatalogue {
            override fun find(key: VariationPointKey) = row.takeIf { it.key == key }

            override fun keys() = setOf(key)

            override fun permissionKeys() = keys + SetterPermission.settingKey(key)
        }
    }

    enum class Answer { FALSE, THROWS, UNBOUND }

    /** INV-Q-17475: each of the 13 expected keys, each with `holds` false, throwing and unbound (39 cases). */
    @ParameterizedTest(name = "{displayName}[{0} {1}]")
    @MethodSource("expectedKeysTimesAnswers")
    fun `an acknowledgement is refused for each of the 13 keys when holds is false, throws or is unbound`(
        key: String,
        answer: Answer,
    ) {
        val id = key.substringBefore('.').uppercase()
        val suffix = key.split('.').getOrNull(2)
        val taxApprover = Acknowledger("tax.approver")
        val places = if (suffix == null) listOf(taxApprover) else listOf(taxApprover, Acknowledger("client.signatory"))
        val role = suffix?.replace('-', '.') ?: "tax.approver"
        val catalogue = fixtureCatalogue(id, places, expectedKeys.toSet())
        val asked = mutableListOf<String>()
        val port =
            when (answer) {
                Answer.FALSE -> {
                    SetterPermission { _, _, _, _, _, k -> false.also { asked += k } }
                }

                Answer.THROWS -> {
                    SetterPermission { _, _, _, _, _, k ->
                        asked += k
                        error("authority down")
                    }
                }

                Answer.UNBOUND -> {
                    null
                }
            }
        val r = Run(image)
        val cmd = ackCmd(VariationPointKey(id), r.tenantScope, ScopeLevel.TENANT, one("OPEN"), actor("a", role), role)
        assertEquals(
            PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED,
            r.refusedAck(cmd, r.h.writer(permission = port, catalogue = catalogue)),
        )
        assertEquals(0L, r.acknowledgements())
        if (port != null) assertEquals(listOf(key), asked)
    }

    @Test
    fun `an acknowledged_by entry of two roles gives one acknowledger per role and each needs its own key`() {
        val a = Acknowledger("a.x")
        val b = Acknowledger("b.y")
        val keys = setOf("vp-99.acknowledge.a-x", "vp-99.acknowledge.b-y")
        val catalogue = fixtureCatalogue("VP-99", listOf(a, b), keys)
        val onlyA = SetterPermission { _, _, _, _, _, k -> k == "vp-99.acknowledge.a-x" }
        val writer = { Run(image).let { r -> r to r.h.writer(permission = onlyA, catalogue = catalogue) } }
        val (r, w) = writer()
        val key = VariationPointKey("VP-99")
        val first = r.ack(ackCmd(key, r.tenantScope, ScopeLevel.TENANT, one("OPEN"), actor("p", "a.x"), "a.x"), w)
        assertEquals(1L, r.acknowledgements())
        val asB = ackCmd(key, r.tenantScope, ScopeLevel.TENANT, one("OPEN"), actor("p", "a.x"), "b.y", first.relaxation)
        val second = asB
        assertEquals(PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED, r.refusedAck(second, w))
        assertEquals(1L, r.acknowledgements())
    }

    companion object {
        val expectedKeys =
            listOf("03", "07", "13", "40", "44", "50", "61", "66", "81").map { "vp-$it.acknowledge" } +
                listOf("59", "62").flatMap {
                    listOf("vp-$it.acknowledge.tax-approver", "vp-$it.acknowledge.client-signatory")
                }

        @JvmStatic
        fun expectedKeysTimesAnswers(): Stream<Arguments> =
            expectedKeys.flatMap { k -> Answer.entries.map { Arguments.of(k, it) } }.stream()
    }
}
