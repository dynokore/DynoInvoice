package com.dynokore.platform.configresolver

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** INV-T-10128 (INV-Q-10140 C): the derivation of the acknowledge key and the start-up key check. */
class AcknowledgeKeyTest {
    private val tax = Acknowledger("tax.approver")
    private val signatory = Acknowledger("client.signatory")
    private val single = OptionSpec("OPEN", Strictness.LOOSER, acknowledgers = listOf(tax))
    private val two = OptionSpec("OPEN", Strictness.LOOSER, acknowledgers = listOf(tax, signatory))

    @Test
    fun `one place gives the plain key and several places give the role suffixed key`() {
        assertEquals("vp-03.acknowledge", acknowledgePermissionKey("VP-03", single, tax))
        assertEquals("vp-59.acknowledge.tax-approver", acknowledgePermissionKey("VP-59", two, tax))
        assertEquals("vp-59.acknowledge.client-signatory", acknowledgePermissionKey("VP-59", two, signatory))
        assertEquals("vp-62.acknowledge.tax-approver", acknowledgePermissionKey("VP-62", two, tax))
    }

    @Test
    fun `a place that is not a place of the option has no key`() {
        assertThrows(IllegalArgumentException::class.java) { acknowledgePermissionKey("VP-03", single, signatory) }
        assertThrows(IllegalArgumentException::class.java) { acknowledgePermissionKey("vp-03", single, tax) }
    }

    private fun catalogueWith(
        keys: Set<String>,
        id: String,
        option: OptionSpec,
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
                listOf(OptionSpec("BASE", Strictness.DEFAULT), option),
            )
        return object : VariationPointCatalogue {
            override fun find(key: VariationPointKey) = row.takeIf { it.key == key }

            override fun keys() = setOf(key)

            override fun permissionKeys() = keys
        }
    }

    private val noAudit =
        object : AuditWriter {
            override fun record(
                entry: AuditEntry,
                context: EventContext,
            ): Envelope = error("start-up writes nothing")
        }

    private fun start(catalogue: VariationPointCatalogue) =
        JdbcPolicyWriter(
            DSL.using(SQLDialect.POSTGRES),
            catalogue,
            DeploymentForm.SAAS,
            "svc",
            noAudit,
            null,
            WriteHarness.CLOCK,
        )

    @Test
    fun `start-up passes when every derived key is in the key set`() {
        start(
            catalogueWith(
                setOf("vp-59.set", "vp-59.acknowledge.tax-approver", "vp-59.acknowledge.client-signatory"),
                "VP-59",
                two,
            ),
        )
    }

    @Test
    fun `look-alike keys in place of the derived ones make start-up fail`() {
        listOf(
            setOf("vp-59.set", "vp-59.acknowledge", "vp-59.acknowledge.client-signatory"),
            setOf("vp-59.set", "vp-59.acknowledge.tax_approver", "vp-59.acknowledge.client-signatory"),
            setOf("vp-59.set", "vp-60.acknowledge", "vp-59.acknowledge.client-signatory"),
            setOf("vp-59.set", "VP-59.acknowledge.tax-approver", "vp-59.acknowledge.client-signatory"),
            setOf("vp-59.acknowledge.tax-approver", "vp-59.acknowledge.client-signatory"),
        ).forEach { known ->
            assertThrows(IllegalStateException::class.java) { start(catalogueWith(known, "VP-59", two)) }
        }
    }

    @Test
    fun `an entry flattened to two roles is derived with the role suffix and start-up needs both keys`() {
        val flattened =
            OptionSpec("OPEN", Strictness.LOOSER, acknowledgers = listOf(Acknowledger("a.x"), Acknowledger("b.y")))
        val both = setOf("vp-99.set", "vp-99.acknowledge.a-x", "vp-99.acknowledge.b-y")
        start(catalogueWith(both, "VP-99", flattened))
        listOf(both - "vp-99.acknowledge.a-x", both - "vp-99.acknowledge.b-y").forEach { known ->
            assertThrows(IllegalStateException::class.java) { start(catalogueWith(known, "VP-99", flattened)) }
        }
    }
}
