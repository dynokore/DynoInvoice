package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.WriteCatalogue.DELEGATION_ONLY
import com.dynokore.platform.configresolver.WriteCatalogue.DESPATCH
import com.dynokore.platform.configresolver.WriteCatalogue.DUE_DATE
import com.dynokore.platform.configresolver.WriteCatalogue.EVERY_LEVEL
import com.dynokore.platform.configresolver.WriteCatalogue.NO_ACKNOWLEDGER
import com.dynokore.platform.configresolver.WriteCatalogue.PRIVATE_ONLY_ACK
import com.dynokore.platform.configresolver.WriteCatalogue.TENANT_CONTROL
import com.dynokore.platform.configresolver.WriteHarness.Companion.clockAt
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate

/** The T26 rulings (INV-Q-3134..3142, INV-Q-3165..3171, INV-Q-2979) against PostgreSQL 17 and 18. */
@Tag("integration")
class WriteRulingsPostgresIT : WritePathBase() {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3165 tenant and group changes carry the real tenant and the bare scope in both payloads`(image: String) {
        val r = Run(image)
        val scope = PolicyScope.entity(r.t, "E1", group = "G1")
        val before = r.h.events(TenantId.PLATFORM).size
        r.put(set(EVERY_LEVEL, scope, ScopeLevel.TENANT, one("B"), admin))
        r.put(set(EVERY_LEVEL, scope, ScopeLevel.GROUP, one("B"), admin))
        val events = r.events()
        assertEquals(listOf("audit", "event", "audit", "event"), events.map { it.kind })
        assertTrue(events.all { it.tenant == r.t.value && it.entityRef == EntityRef.TENANT_WIDE.value })
        val scopes = events.map { Regex("\"scope\": \"([^\"]*)\"").find(it.dataJson)?.groupValues?.get(1) }
        val tenantScope = "TENANT:${r.t.value}"
        assertEquals(listOf(tenantScope, tenantScope, "GROUP:G1", "GROUP:G1"), scopes)
        // Nothing went to the platform tenant.
        assertEquals(before, r.h.events(TenantId.PLATFORM).size)
        val operator = actor("op", "platform.engineer")
        val installation = set(EVERY_LEVEL, scope, ScopeLevel.INSTALLATION, one("B"), operator)
        r.h.putInstallation(r.h.writer(platform = true), installation)
        val platform = r.h.events(TenantId.PLATFORM).drop(before)
        assertTrue(platform.all { it.dataJson.contains("\"scope\": \"INSTALLATION\"") }, platform.toString())
        assertEquals(2, platform.size)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3136 an unbound or failing delegation probe requires the delegation acknowledger`(image: String) {
        val r = Run(image)
        val cmd = ackCmd(DUE_DATE, r.e1, ScopeLevel.ENTITY, one("NEXT_WORKING_DAY"), approver, "tax.approver")
        listOf(
            r.h.writer(delegation = null),
            r.h.writer(delegation = { _, _ -> null }),
            r.h.writer(delegation = { _, _ -> error("down") }),
        ).forEach { assertEquals(listOf("client.signatory"), r.ack(cmd, it).outstandingRoles) }
        assertEquals(emptyList<String>(), r.ack(cmd).outstandingRoles)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3134 a looser option nobody is accountable for is refused`(image: String) {
        val r = Run(image)
        val both = actor("ta", "tax.approver", "client.signatory")
        val entity = ScopeLevel.ENTITY
        val hold = one("HOLD")
        assertEquals(
            PolicyRefusal.NO_ACCOUNTABLE_ROLE,
            r.refusedAck(ackCmd(NO_ACKNOWLEDGER, r.e1, entity, hold, both, "tax.approver")),
        )
        assertEquals(PolicyRefusal.NO_ACCOUNTABLE_ROLE, r.refusedPut(set(NO_ACKNOWLEDGER, r.e1, entity, hold, both)))
        assertEquals(0L, r.settings())
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3134 acknowledgers only under delegation, and no delegation applies - refused`(image: String) {
        val r = Run(image)
        val both = actor("ta", "tax.approver", "client.signatory")
        val filer = one("FILER_OF_RECORD")
        val entityAck = ackCmd(DELEGATION_ONLY, r.e1, ScopeLevel.ENTITY, filer, both, "client.signatory")
        assertEquals(PolicyRefusal.NO_ACCOUNTABLE_ROLE, r.refusedAck(entityAck))
        assertEquals(
            PolicyRefusal.NO_ACCOUNTABLE_ROLE,
            r.refusedPut(set(DELEGATION_ONLY, r.e1, ScopeLevel.ENTITY, filer, both)),
        )
        assertEquals(0L, r.acknowledgements())
        // At client-entity scope the delegation always applies.
        val client = PolicyScope.entity(r.t, "E1", clientEntity = "C1")
        val clientAck = ackCmd(DELEGATION_ONLY, client, ScopeLevel.CLIENT_ENTITY, filer, both, "client.signatory")
        assertTrue(r.ack(clientAck).complete)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3134 an acknowledger naming no role for this deployment form - refused`(image: String) {
        val r = Run(image)
        val elsewhere = one("ELSEWHERE")
        val cmd = ackCmd(PRIVATE_ONLY_ACK, r.e1, ScopeLevel.ENTITY, elsewhere, approver, "tax.approver")
        assertEquals(PolicyRefusal.NO_ACCOUNTABLE_ROLE, r.refusedAck(cmd))
        val apply = set(PRIVATE_ONLY_ACK, r.e1, ScopeLevel.ENTITY, elsewhere)
        assertEquals(PolicyRefusal.NO_ACCOUNTABLE_ROLE, r.refusedPut(apply))
        assertEquals(0L, r.acknowledgements())
        assertTrue(r.ack(cmd, r.h.writer(form = DeploymentForm.PRIVATE)).complete)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3142 a tenant-scope relaxation has no entity, without an outbox only audit is written`(
        image: String,
    ) {
        val r = Run(image)
        val w = r.h.writer(withOutbox = false)
        val sole = one("TENANT_ADMIN_SOLE")
        val status = r.ack(ackCmd(TENANT_CONTROL, r.tenantScope, ScopeLevel.TENANT, sole, admin, "tenant.admin"), w)
        val setter = actor("a2", "tenant.admin")
        val apply = set(TENANT_CONTROL, r.tenantScope, ScopeLevel.TENANT, sole, setter, relaxation = status.relaxation)
        assertNull(r.put(apply, w).policyChangedEventId)
        assertEquals(listOf("audit", "audit"), r.events().map { it.kind })
        assertEquals(1L, r.acknowledgements("and entity_id is null"))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3138 an acknowledgement effective after the setting does not back it`(image: String) {
        val r = Run(image)
        val move = one("NEXT_WORKING_DAY")
        val later = r.ack(ackCmd(DUE_DATE, r.e1, ScopeLevel.ENTITY, move, approver, "tax.approver", from = nextMonth))
        assertTrue(later.complete)
        val early = set(DUE_DATE, r.e1, ScopeLevel.ENTITY, move, relaxation = later.relaxation)
        assertEquals(PolicyRefusal.RELAXATION_REQUIRED, r.refusedPut(early))
        assertEquals(0L, r.settings())
        r.put(set(DUE_DATE, r.e1, ScopeLevel.ENTITY, move, from = nextMonth, relaxation = later.relaxation))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3140 installation has no floor, and a group's first value goes back to its floor`(image: String) {
        val r = Run(image)
        val floor = LocalDate.of(2026, 4, 1)
        val asked = mutableListOf<ScopeLevel>()
        val floored = r.h.writer(backdating = { target, _ -> floor.also { asked += target.level } })
        val platform = r.h.writer(platform = true, backdating = { _, _ -> floor })
        val operator = actor("op", "platform.engineer")
        val installation = PolicyScope.installation()
        val back = set(EVERY_LEVEL, installation, ScopeLevel.INSTALLATION, one("B"), operator, floor)
        assertEquals(PolicyRefusal.BACKDATING_REFUSED, refusal { r.h.putInstallation(platform, back) })
        val group = PolicyScope.entity(r.t, "E1", group = "G1")
        val beforeFloor = set(EVERY_LEVEL, group, ScopeLevel.GROUP, one("B"), admin, floor.minusDays(1))
        assertEquals(PolicyRefusal.BACKDATING_REFUSED, r.refusedPut(beforeFloor, floored))
        r.put(set(EVERY_LEVEL, group, ScopeLevel.GROUP, one("B"), admin, floor), floored)
        assertEquals(setOf(ScopeLevel.GROUP), asked.toSet())
        assertEquals(one("B"), r.resolve(EVERY_LEVEL, group, floor).selected)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3171 a version recorded after the date but effective before it is in force and listed as changed`(
        image: String,
    ) {
        val r = Run(image)
        val tenth = LocalDate.of(2026, 10, 10)
        val june = LocalDate.of(2026, 6, 1)
        val w = r.h.writer(clock = clockAt("2026-10-15T02:00:00Z"), backdating = { _, _ -> LocalDate.of(2026, 4, 1) })
        r.put(set(DESPATCH, r.e1, ScopeLevel.ENTITY, one("DAILY_WINDOW"), from = june), w)
        val entry = r.h.inTenant(r.t) { r.h.history().asOf(r.e1, tenth, setOf(DESPATCH)) }.entries.single()
        assertEquals(one("DAILY_WINDOW"), entry.inForce?.selected)
        assertEquals(listOf(june), entry.changedSince.map { it.effectiveFrom })
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-3136 an under-delegation role cannot acknowledge where no delegation applies`(image: String) {
        val r = Run(image)
        val signatory = actor("cs", "client.signatory")
        val cmd = ackCmd(DUE_DATE, r.e1, ScopeLevel.ENTITY, one("NEXT_WORKING_DAY"), signatory, "client.signatory")
        assertEquals(PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED, r.refusedAck(cmd))
        assertEquals(0L, r.acknowledgements())
        assertEquals(listOf("tax.approver"), r.ack(cmd, r.h.writer(delegation = { _, _ -> true })).outstandingRoles)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `INV-Q-2979 a profile value at installation is refused, never stored as an override`(image: String) {
        val r = Run(image)
        val operator = actor("op", "platform.engineer")
        val cmd =
            set(
                EVERY_LEVEL,
                PolicyScope.installation(),
                ScopeLevel.INSTALLATION,
                one("B"),
                operator,
                source = SettingSource.PROFILE,
                profile = "PRF-1" to "3",
            )
        val w = r.h.writer(platform = true)
        val stored = "select count(*) from svc.config_installation_setting"
        val before = r.h.count(stored)
        assertEquals(PolicyRefusal.PROFILE_AT_INSTALLATION, refusal { r.h.putInstallation(w, cmd) })
        assertEquals(before, r.h.count(stored))
    }
}
