package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.WriteCatalogue.DUE_DATE
import com.dynokore.platform.configresolver.WriteCatalogue.TENANT_CONTROL
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate

/** INV-T-24432 (INV-AC-24431): the acknowledgements behind the value in force, on real PostgreSQL 16, 17 and 18. */
@Tag("integration")
class RelaxationReaderPostgresIT : WritePathBase() {
    private val sole = one("TENANT_ADMIN_SOLE")
    private val officer = one("SECURITY_OFFICER")

    private fun Run.reader() =
        JdbcRelaxationReader(h.dsl, WriteCatalogue.catalogue, DeploymentForm.SAAS, WriteHarness.SCHEMA)

    private fun Run.inForce(
        on: LocalDate,
        scope: PolicyScope = tenantScope,
        key: VariationPointKey = TENANT_CONTROL,
        tenant: Run = this,
    ) = h.inTenant(tenant.t) { reader().acknowledgementsInForce(key, scope, on) }

    /** T1's story: acknowledge and set TENANT_ADMIN_SOLE on 2026-10-01, the default option again on 2026-11-01. */
    private fun Run.story(): RelaxationId {
        val status = ack(ackCmd(TENANT_CONTROL, tenantScope, ScopeLevel.TENANT, sole, admin, "tenant.admin"))
        val setter = actor("a2", "tenant.admin")
        put(set(TENANT_CONTROL, tenantScope, ScopeLevel.TENANT, sole, setter, relaxation = status.relaxation))
        put(set(TENANT_CONTROL, tenantScope, ScopeLevel.TENANT, officer, setter, from = nextMonth))
        return status.relaxation
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-24431 a the relaxation in force returns its acknowledgement as stored`(image: String) {
        val r = Run(image)
        val relaxation = r.story()
        val found = r.inForce(LocalDate.of(2026, 10, 15))
        assertEquals(1, found.size)
        val ack = found.single()
        assertEquals(relaxation, ack.relaxation)
        assertEquals("tenant.admin", ack.role)
        assertEquals("admin", ack.principal)
        assertEquals(today, ack.effectiveFrom)
        val table = "svc.config_relaxation_acknowledgement"
        val sql = "select acknowledged_at from $table where tenant_id = '${r.t}'"
        val stored = (r.h.ownerProbe.inTenant(r.t) { fetchValue(sql) } as java.time.OffsetDateTime).toInstant()
        assertEquals(stored, ack.acknowledgedAt)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-24431 a two acknowledgements come back in the order recorded`(image: String) {
        val r = Run(image)
        val move = one("NEXT_WORKING_DAY")
        val client = PolicyScope.entity(r.t, "E2", clientEntity = "C2")
        val level = ScopeLevel.CLIENT_ENTITY
        val first = r.ack(ackCmd(DUE_DATE, client, level, move, approver, "tax.approver"))
        val signatory = actor("cs", "client.signatory")
        r.ack(ackCmd(DUE_DATE, client, level, move, signatory, "client.signatory", first.relaxation))
        r.put(set(DUE_DATE, client, level, move, relaxation = first.relaxation))
        val found = r.inForce(today, client, DUE_DATE)
        assertEquals(listOf("tax.approver", "client.signatory"), found.map { it.role })
        assertEquals(listOf("ta", "cs"), found.map { it.principal })
        assertTrue(found.all { it.relaxation == first.relaxation })
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-24431 b before the first value or under a later one without relaxation the list is empty`(
        image: String,
    ) {
        val r = Run(image)
        r.story()
        assertEquals(emptyList<RelaxationAcknowledgement>(), r.inForce(LocalDate.of(2026, 9, 30)))
        assertEquals(emptyList<RelaxationAcknowledgement>(), r.inForce(nextMonth))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-24431 c the catalogue default has no acknowledgements`(image: String) {
        val r = Run(image)
        assertEquals(emptyList<RelaxationAcknowledgement>(), r.inForce(today))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-24431 d another tenant reads an empty list and its SQL count sees no row of the first`(image: String) {
        val t1 = Run(image)
        val t2 = Run(image)
        t1.story()
        assertEquals(1, t1.inForce(LocalDate.of(2026, 10, 15)).size)
        assertEquals(emptyList<RelaxationAcknowledgement>(), t2.inForce(LocalDate.of(2026, 10, 15)))
        assertEquals(0L, t2.h.count("select count(*) from svc.config_relaxation_acknowledgement", t2.t))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-24431 e an unknown row throws UnknownVariationPoint`(image: String) {
        val r = Run(image)
        assertThrows(UnknownVariationPoint::class.java) { r.inForce(today, key = VariationPointKey("VP-99")) }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-24431 f an installation scope throws IllegalArgumentException`(image: String) {
        val r = Run(image)
        assertThrows(IllegalArgumentException::class.java) { r.inForce(today, PolicyScope.installation()) }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-24431 g a profile-sourced value that carries a relaxation returns its acknowledgements`(image: String) {
        val r = Run(image)
        val status = r.ack(ackCmd(TENANT_CONTROL, r.tenantScope, ScopeLevel.TENANT, sole, admin, "tenant.admin"))
        val setter = actor("a2", "tenant.admin")
        val profile =
            set(
                TENANT_CONTROL,
                r.tenantScope,
                ScopeLevel.TENANT,
                sole,
                setter,
                relaxation = status.relaxation,
                source = SettingSource.PROFILE,
                profile = "PRF-1" to "3",
            )
        r.put(profile)
        assertTrue(r.resolve(TENANT_CONTROL, r.tenantScope).source is PolicySource.Profile)
        assertEquals(listOf(status.relaxation), r.inForce(today).map { it.relaxation })
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-AC-24431 h the setting row is read with its own tenant only, also where RLS shows the row`(image: String) {
        val t1 = Run(image)
        val relaxation = t1.story()
        val t2 = Run(image)
        val settingId = (t1.resolve(TENANT_CONTROL, t1.tenantScope, today).source as PolicySource.Override).settingId
        // `relaxationOf` is internal to the impl half, which this test source set cannot name: call it by reflection.
        val type = Class.forName("com.dynokore.platform.configresolver.JdbcSettingWrites")
        val make = type.declaredConstructors.single { it.parameterCount == 5 }
        val writes = make.newInstance(t1.h.dsl, WriteHarness.SCHEMA, null, 4, null)
        val read = type.declaredMethods.single { it.name.startsWith("relaxationOf") }

        // Value classes are passed as their UUID when the compiler unboxes them.
        fun relaxationOf(
            tenant: TenantId,
            id: java.util.UUID,
        ): String? {
            val tenantArg: Any = if (read.parameterTypes[0] == java.util.UUID::class.java) tenant.value else tenant
            return t1.h.inTenant(t1.t) { read.invoke(writes, tenantArg, id) }?.toString()
        }
        // The connection is bound to T1, so RLS shows T1's row: only the explicit tenant predicate can refuse T2.
        assertEquals(relaxation.toString(), relaxationOf(t1.t, settingId))
        assertEquals(null, relaxationOf(t2.t, settingId))
    }
}
