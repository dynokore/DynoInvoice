package com.dynokore.platform.configresolver

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.configresolver.WriteCatalogue.BOUNDED
import com.dynokore.platform.configresolver.WriteCatalogue.DESPATCH
import com.dynokore.platform.configresolver.WriteCatalogue.NAMED_BOUND
import com.dynokore.platform.configresolver.WriteCatalogue.NON_WAIVABLE
import com.dynokore.platform.configresolver.WriteCatalogue.PLANNED
import com.dynokore.platform.configresolver.WriteCatalogue.RECORD
import com.dynokore.platform.configresolver.WriteCatalogue.RETIRED
import com.dynokore.platform.configresolver.WriteCatalogue.UNDIRECTED_CROSS
import com.dynokore.platform.configresolver.WriteCatalogue.UNNAMED_BOUND
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.util.UUID

/** The validator half of the write path (INV-REQ-1239 b3, INV-Q-3167, INV-Q-3173): no database is touched. */
class WriteValidationTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val day = LocalDate.of(2026, 10, 1)
    private val tenantScope = PolicyScope.tenant(tenant)
    private val e1 = PolicyScope.entity(tenant, "E1")
    private val noDatabase =
        object : CurrentConnection {
            override fun require() = error("validation never touches the database")
        }
    private val noAudit =
        object : AuditWriter {
            override fun record(
                entry: AuditEntry,
                context: EventContext,
            ): Envelope = error("validation writes nothing")
        }

    private fun writer(constraints: ValueConstraints? = null) = JdbcPolicyWriter(
        noDatabase,
        WriteCatalogue.catalogue,
        DeploymentForm.SAAS,
        "svc",
        noAudit,
        null,
        WriteHarness.CLOCK,
        constraints = constraints,
    )

    private fun one(key: String) = listOf(SelectedOption(key))

    private fun typed(
        key: String,
        json: String,
    ) = listOf(SelectedOption(key, json))

    /** Validates at the scope's most specific level; returns the refusal. */
    private fun refused(
        key: VariationPointKey,
        scope: PolicyScope,
        selected: List<SelectedOption>,
        w: PolicyWriter = writer(),
    ): PolicyRefusal = assertThrows(PolicyRefused::class.java) { accept(key, scope, selected, w) }.refusal

    private fun accept(
        key: VariationPointKey,
        scope: PolicyScope,
        selected: List<SelectedOption>,
        w: PolicyWriter = writer(),
    ) = w.validate(key, scope, if (scope == tenantScope) ScopeLevel.TENANT else ScopeLevel.ENTITY, selected, day)

    @ParameterizedTest
    @ValueSource(strings = ["0", "5", "2.5", "\"3\"", "null"])
    fun `INV-AC-2224 a number outside the bounds or of the wrong type is refused`(json: String) {
        assertEquals(PolicyRefusal.OUT_OF_BOUNDS, refused(BOUNDED, tenantScope, typed("LAST_N", json)))
    }

    @Test
    fun `a number within the bounds is accepted`() {
        accept(BOUNDED, tenantScope, typed("LAST_N", "4"))
    }

    @Test
    fun `a typed option needs its value and an untyped one takes none`() {
        assertEquals(PolicyRefusal.OUT_OF_BOUNDS, refused(BOUNDED, tenantScope, one("LAST_N")))
        assertEquals(PolicyRefusal.OUT_OF_BOUNDS, refused(BOUNDED, tenantScope, typed("LAST_PERIOD", "1")))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{\"scale\":2}",
            "{\"scale\":2,\"mode\":\"HALF_UP\",\"level\":\"LINE\"}",
            "{\"scale\":5,\"mode\":\"HALF_UP\"}",
            "{\"scale\":2,\"mode\":\"UP\"}",
            "[]",
        ],
    )
    fun `INV-Q-3167 a record needs every field and nothing else, each within its bounds`(json: String) {
        assertEquals(PolicyRefusal.OUT_OF_BOUNDS, refused(RECORD, e1, typed("CUSTOM", json)))
    }

    @Test
    fun `a complete record is accepted`() {
        accept(RECORD, e1, typed("CUSTOM", "{\"scale\":4,\"mode\":\"HALF_EVEN\"}"))
    }

    @Test
    fun `INV-Q-3167 a named bound goes to the owning service and an unbound port refuses`() {
        val value = typed("EARLIER_DATE", "\"2026-06-01\"")
        val refuse = writer { _, _, _, _ -> ConstraintVerdict.Refuse("after the mandatory date") }
        assertEquals(PolicyRefusal.CONSTRAINT_REFUSED, refused(NAMED_BOUND, e1, value))
        assertEquals(PolicyRefusal.CONSTRAINT_REFUSED, refused(NAMED_BOUND, e1, value, refuse))
        assertEquals(PolicyRefusal.CONSTRAINT_REFUSED, refused(NAMED_BOUND, e1, value, writer { _, _, _, _ -> null }))
        val failing = writer { _, _, _, _ -> error("replica down") }
        assertEquals(PolicyRefusal.CONSTRAINT_REFUSED, refused(NAMED_BOUND, e1, value, failing))
        accept(NAMED_BOUND, e1, value, writer { _, _, _, _ -> ConstraintVerdict.Accept })
    }

    @Test
    fun `the port is not asked for a value it has no part in`() {
        accept(BOUNDED, tenantScope, typed("LAST_N", "2"), writer { _, _, _, _ -> error("must not be asked") })
    }

    @Test
    fun `INV-AC-2230 a non-waivable control offers no looser option, in either form, and refuses one`() {
        val row = WriteCatalogue.rows.getValue(NON_WAIVABLE)
        DeploymentForm.entries.forEach { assertFalse("SOLE_OPERATOR" in row.offeredOptions(it)) }
        assertEquals(PolicyRefusal.LOOSER_NOT_OFFERED, refused(NON_WAIVABLE, e1, one("SOLE_OPERATOR")))
    }

    @Test
    fun `INV-AC-2234 both forms offer the same options`() {
        WriteCatalogue.rows.values.forEach {
            val saas = it.offeredOptions(DeploymentForm.SAAS)
            assertEquals(saas, it.offeredOptions(DeploymentForm.PRIVATE), it.key.value)
        }
    }

    @Test
    fun `INV-AC-2227 a disallowed scope is refused before anything else`() {
        assertEquals(PolicyRefusal.SCOPE_NOT_ALLOWED, refused(DESPATCH, tenantScope, one("NOPE")))
    }

    @Test
    fun `selection shape, unknown options, planned, retired and unsupported rows are refused`() {
        val both = listOf(SelectedOption("CONTINUOUS"), SelectedOption("DAILY_WINDOW"))
        assertEquals(PolicyRefusal.SELECTION_INVALID, refused(DESPATCH, e1, emptyList()))
        assertEquals(PolicyRefusal.SELECTION_INVALID, refused(DESPATCH, e1, both))
        assertEquals(PolicyRefusal.UNKNOWN_OPTION, refused(DESPATCH, e1, one("HOURLY")))
        assertEquals(PolicyRefusal.PLANNED, refused(PLANNED, e1, one("LONGER")))
        assertThrows(VariationPointRetired::class.java) { accept(RETIRED, e1, one("X")) }
        assertThrows(UnknownVariationPoint::class.java) { accept(VariationPointKey("VP-99"), e1, one("X")) }
        // Lifted only for what is implemented (INV-T-3176).
        assertEquals(PolicyRefusal.NOT_SUPPORTED, refused(UNDIRECTED_CROSS, tenantScope, one("OTHER")))
        assertEquals(PolicyRefusal.NOT_SUPPORTED, refused(UNNAMED_BOUND, tenantScope, one("LONGER")))
    }

    @Test
    fun `a by-form acknowledger may name one form only, and a catalogue without a listing refuses it`() {
        val privateOnly = Acknowledger(roleByForm = mapOf(DeploymentForm.PRIVATE to "tax.approver"))
        assertNull(privateOnly.roleIn(DeploymentForm.SAAS))
        assertThrows(CatalogueListingUnavailable::class.java) { VariationPointCatalogue { null }.keys() }
    }

    @Test
    fun `INV-Q-3202 a row bound names the row's installation parameter, and bounds only a number's min or max`() {
        val max = VariationPointKey("VP-80")
        val bounded = TypedValueSpec(ValueType.DURATION, "hours", max = Bound.row(max))
        fun row(parameter: VariationPointKey?) = VariationPointDefinition(
            key = VariationPointKey("VP-67"),
            vpClass = VpClass.PARAMETER,
            modifiers = emptySet(),
            status = VpStatus.AVAILABLE,
            scopeLevels = setOf(ScopeLevel.TENANT),
            setBy = setOf("integration.engineer"),
            options =
            listOf(OptionSpec("H72", Strictness.DEFAULT), OptionSpec("CUSTOM", Strictness.NEUTRAL, true, bounded)),
            installationParameter = parameter,
        )
        assertEquals(max, row(max).installationParameter)
        assertThrows(IllegalArgumentException::class.java) { row(null) }
        assertThrows(IllegalArgumentException::class.java) { row(VariationPointKey("VP-79")) }
        assertThrows(IllegalArgumentException::class.java) { TypedValueSpec(ValueType.TEXT, max = Bound.row(max)) }
        val exclusive = { TypedValueSpec(ValueType.INT, minExclusive = Bound.row(max)) }
        assertThrows(IllegalArgumentException::class.java) { exclusive() }
        assertThrows(IllegalArgumentException::class.java) { Bound(number = java.math.BigDecimal.ONE, row = max) }
    }
}
