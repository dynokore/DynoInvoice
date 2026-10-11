package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.WriteCatalogue.BOUNDED
import com.dynokore.platform.configresolver.WriteCatalogue.DELEGATION_ONLY
import com.dynokore.platform.configresolver.WriteCatalogue.DESPATCH
import com.dynokore.platform.configresolver.WriteCatalogue.DISTINCT_ACK
import com.dynokore.platform.configresolver.WriteCatalogue.DUE_DATE
import com.dynokore.platform.configresolver.WriteCatalogue.EVERY_LEVEL
import com.dynokore.platform.configresolver.WriteCatalogue.NO_ACKNOWLEDGER
import com.dynokore.platform.configresolver.WriteCatalogue.PLATFORM_MAXIMUM
import com.dynokore.platform.configresolver.WriteCatalogue.PRIVATE_ONLY_ACK
import com.dynokore.platform.configresolver.WriteCatalogue.TENANT_CONTROL
import com.dynokore.platform.configresolver.WriteHarness.Companion.CAUSATION
import com.dynokore.platform.configresolver.WriteHarness.Companion.CORRELATION
import com.dynokore.platform.configresolver.WriteHarness.Companion.clockAt
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

/** Every write path against real PostgreSQL 17 and 18 (STK-14, STK-15; INV-T-2870). */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WritePathPostgresIT {
    private val harnesses = mutableMapOf<String, WriteHarness>()

    @AfterAll
    fun stop() = harnesses.values.forEach { it.close() }

    private val today = LocalDate.of(2026, 10, 1)
    private val nextMonth = LocalDate.of(2026, 11, 1)
    private val approver = Actor("ta", setOf("tax.approver"))
    private val admin = Actor("admin", setOf("tenant.admin"))

    /** One tenant on one database; every helper writes inside that tenant. */
    private inner class Run(
        image: String,
    ) {
        val h = harnesses.getOrPut(image) { WriteHarness(image) }
        val t = TenantId(UUID.randomUUID())
        val e1 = PolicyScope.entity(t, "E1")
        val tenantScope = PolicyScope.tenant(t)

        fun put(
            cmd: SetPolicyCommand,
            w: PolicyWriter = h.writer(),
        ) = h.inTenant(t) { w.setPolicy(cmd) }

        fun ack(
            cmd: AcknowledgeRelaxationCommand,
            w: PolicyWriter = h.writer(),
        ) = h.inTenant(t) { w.acknowledgeControlRelaxation(cmd) }

        fun refusedPut(
            cmd: SetPolicyCommand,
            w: PolicyWriter = h.writer(),
        ) = refusal { put(cmd, w) }

        fun refusedAck(
            cmd: AcknowledgeRelaxationCommand,
            w: PolicyWriter = h.writer(),
        ) = refusal { ack(cmd, w) }

        fun resolve(
            key: VariationPointKey,
            scope: PolicyScope,
            on: LocalDate = today,
        ) = h.inTenant(t) { h.reader().resolve(key, scope, on) }

        fun settings() = h.count("select count(*) from svc.config_setting where tenant_id = '$t'")

        fun acknowledgements(extra: String = ""): Long {
            val table = "svc.config_relaxation_acknowledgement"
            return h.count("select count(*) from $table where tenant_id = '$t' $extra")
        }

        fun events() = h.events(t)
    }

    private fun one(key: String) = listOf(SelectedOption(key))

    private fun actor(
        principal: String,
        vararg roles: String,
    ) = Actor(principal, roles.toSet())

    @Suppress("LongParameterList")
    private fun set(
        key: VariationPointKey,
        scope: PolicyScope,
        level: ScopeLevel,
        selected: List<SelectedOption>,
        actor: Actor = approver,
        from: LocalDate = today,
        reason: String? = "because",
        relaxation: RelaxationId? = null,
        source: SettingSource = SettingSource.OVERRIDE,
        profile: Pair<String, String>? = null,
    ) = SetPolicyCommand(
        key,
        scope,
        level,
        selected,
        from,
        reason,
        actor,
        CORRELATION,
        CAUSATION,
        source,
        profile?.first,
        profile?.second,
        relaxation,
    )

    @Suppress("LongParameterList")
    private fun ackCmd(
        key: VariationPointKey,
        scope: PolicyScope,
        level: ScopeLevel,
        selected: List<SelectedOption>,
        actor: Actor,
        asRole: String,
        relaxation: RelaxationId? = null,
    ) = AcknowledgeRelaxationCommand(
        key,
        scope,
        level,
        selected,
        today,
        actor,
        asRole,
        CORRELATION,
        CAUSATION,
        relaxation,
        "accepted risk",
    )

    private fun refusal(block: () -> Unit) = assertThrows(PolicyRefused::class.java, block).refusal

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-2227 a setting at a scope the row does not allow is refused and nothing is stored`(image: String) {
        val r = Run(image)
        val cmd = set(DESPATCH, r.tenantScope, ScopeLevel.TENANT, one("DAILY_WINDOW"))
        assertEquals(PolicyRefusal.SCOPE_NOT_ALLOWED, r.refusedPut(cmd))
        assertEquals(0L, r.settings())
        assertTrue(r.events().isEmpty())
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-REQ-1242 a value is set at every level the row allows and the most specific wins`(image: String) {
        val r = Run(image)
        val scope = PolicyScope.entity(r.t, "E1", group = "G1", feed = "F1", apConnection = "AP1", clientEntity = "C1")
        listOf(
            ScopeLevel.TENANT,
            ScopeLevel.GROUP,
            ScopeLevel.ENTITY,
            ScopeLevel.FEED,
            ScopeLevel.AP_CONNECTION,
            ScopeLevel.CLIENT_ENTITY,
        ).forEach { r.put(set(EVERY_LEVEL, scope, it, one("B"), admin)) }
        val installation = set(EVERY_LEVEL, scope, ScopeLevel.INSTALLATION, one("B"), actor("op", "platform.engineer"))
        r.h.inPlatform { r.h.writer(platform = true).setPolicy(installation) }
        assertEquals(6L, r.settings())
        assertEquals(ScopeLevel.CLIENT_ENTITY, (r.resolve(EVERY_LEVEL, scope).source as PolicySource.Override).level)
        // Tenant and group changes carry the real tenant with the tenant-wide entity (INV-Q-3165).
        val wide = EntityRef.TENANT_WIDE.value
        val entities = r.events().filter { it.kind == "event" }.map { it.entityRef }
        assertEquals(listOf(wide, wide, "E1", "E1", "E1", "E1"), entities)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-2224 out of bounds and the wrong role are refused, a valid value shows its source`(image: String) {
        val r = Run(image)
        val officer = actor("so", "security.officer")
        val five = listOf(SelectedOption("LAST_N", "5"))
        val three = listOf(SelectedOption("LAST_N", "3"))
        val tenant = ScopeLevel.TENANT
        assertEquals(PolicyRefusal.OUT_OF_BOUNDS, r.refusedPut(set(BOUNDED, r.tenantScope, tenant, five, officer)))
        assertEquals(PolicyRefusal.ROLE_NOT_ALLOWED, r.refusedPut(set(BOUNDED, r.tenantScope, tenant, three, admin)))
        assertEquals(PolicySource.CatalogueDefault, r.resolve(BOUNDED, r.tenantScope).source)
        val change = r.put(set(BOUNDED, r.tenantScope, ScopeLevel.TENANT, three, officer))
        val resolved = r.resolve(BOUNDED, r.tenantScope)
        assertEquals(three, resolved.selected)
        assertEquals(change.version.id, (resolved.source as PolicySource.Override).settingId)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-2229 a change needs a reason, records the prior value, leaves earlier dates alone`(image: String) {
        val r = Run(image)
        val e1 = r.e1
        r.put(set(DESPATCH, e1, ScopeLevel.ENTITY, one("CONTINUOUS")))
        val noReason = set(DESPATCH, e1, ScopeLevel.ENTITY, one("DAILY_WINDOW"), from = nextMonth, reason = " ")
        assertEquals(PolicyRefusal.REASON_REQUIRED, r.refusedPut(noReason))
        val withReason = set(DESPATCH, e1, ScopeLevel.ENTITY, one("DAILY_WINDOW"), from = nextMonth, reason = "cheaper")
        val change = r.put(withReason)
        assertEquals(one("CONTINUOUS"), change.prior?.selected)
        val audit = r.events().last { it.kind == "audit" }
        assertTrue(audit.dataJson.contains("\"CONTINUOUS\""), audit.dataJson)
        assertTrue(audit.dataJson.contains("\"cheaper\""), audit.dataJson)
        assertEquals(one("CONTINUOUS"), r.resolve(DESPATCH, e1, nextMonth.minusDays(1)).selected)
        assertEquals(one("DAILY_WINDOW"), r.resolve(DESPATCH, e1, nextMonth).selected)
        val changed = r.events().last { it.kind == "event" }
        assertEquals(JdbcPolicyWriter.POLICY_CHANGED, changed.type)
        assertTrue(changed.dataJson.contains("\"effectiveDate\": \"2026-11-01\""), changed.dataJson)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-2940 INV-Q-3140 never retroactive, except a first value back to its floor`(image: String) {
        val r = Run(image)
        val june = LocalDate.of(2026, 6, 1)
        val floor = BackdatingFloor { _, _ -> LocalDate.of(2026, 4, 1) }
        val floored = r.h.writer(backdating = floor)
        // Singapore's date, not UTC's: at 2026-09-30T20:00Z it is already 1 October in Singapore.
        val lateUtc = r.h.writer(clock = clockAt("2026-09-30T20:00:00Z"))
        r.put(set(EVERY_LEVEL, r.tenantScope, ScopeLevel.TENANT, one("B"), admin), lateUtc)
        val yesterday = set(EVERY_LEVEL, r.tenantScope, ScopeLevel.TENANT, one("A"), admin, today.minusDays(1))
        assertEquals(PolicyRefusal.BACKDATING_REFUSED, r.refusedPut(yesterday, lateUtc))
        // No port, a null floor, or a date before the floor: refused.
        val inJune = set(DESPATCH, r.e1, ScopeLevel.ENTITY, one("DAILY_WINDOW"), from = june)
        assertEquals(PolicyRefusal.BACKDATING_REFUSED, r.refusedPut(inJune))
        assertEquals(PolicyRefusal.BACKDATING_REFUSED, r.refusedPut(inJune, r.h.writer(backdating = { _, _ -> null })))
        val inMarch = set(DESPATCH, r.e1, ScopeLevel.ENTITY, one("DAILY_WINDOW"), from = LocalDate.of(2026, 3, 1))
        assertEquals(PolicyRefusal.BACKDATING_REFUSED, r.refusedPut(inMarch, floored))
        // The first value from the floor on is accepted; a second back-dated value is not.
        r.put(inJune, floored)
        val inJuly = set(DESPATCH, r.e1, ScopeLevel.ENTITY, one("CONTINUOUS"), from = LocalDate.of(2026, 7, 1))
        assertEquals(PolicyRefusal.BACKDATING_REFUSED, r.refusedPut(inJuly, floored))
        // A tenant's first value has no floor to go back to.
        val other = Run(image)
        val tenantInJune = set(EVERY_LEVEL, other.tenantScope, ScopeLevel.TENANT, one("B"), admin, june)
        assertEquals(PolicyRefusal.BACKDATING_REFUSED, other.refusedPut(tenantInJune, floored))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-205 where an agent operates the looser VP-62 option also needs the client signatory`(image: String) {
        val r = Run(image)
        val move = one("NEXT_WORKING_DAY")
        val client = PolicyScope.entity(r.t, "E2", clientEntity = "C2")
        val level = ScopeLevel.CLIENT_ENTITY
        val first = r.ack(ackCmd(DUE_DATE, client, level, move, approver, "tax.approver"))
        assertEquals(listOf("client.signatory"), first.outstandingRoles)
        val apply = set(DUE_DATE, client, level, move, relaxation = first.relaxation)
        assertEquals(PolicyRefusal.RELAXATION_REQUIRED, r.refusedPut(apply))
        // One person cannot give both acknowledgements.
        val both = actor("ta", "tax.approver", "client.signatory")
        val same = ackCmd(DUE_DATE, client, level, move, both, "client.signatory", first.relaxation)
        assertEquals(PolicyRefusal.ACKNOWLEDGER_DUPLICATE, r.refusedAck(same))
        val signatory = actor("cs", "client.signatory")
        val second = ackCmd(DUE_DATE, client, level, move, signatory, "client.signatory", first.relaxation)
        assertTrue(r.ack(second).complete)
        val change = r.put(apply)
        assertEquals(first.relaxation, change.version.relaxation)
        val changed = r.events().last { it.kind == "event" }
        val reference = "\"relaxationAcknowledgementReference\": \"${first.relaxation}\""
        assertTrue(changed.dataJson.contains(reference), changed.dataJson)
        // A relaxation backs exactly one setting.
        val again = set(DUE_DATE, client, level, move, from = nextMonth, relaxation = first.relaxation)
        assertEquals(PolicyRefusal.RELAXATION_REQUIRED, r.refusedPut(again))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-205 without acknowledgement refused, with the approver alone accepted where no agent operates`(
        image: String,
    ) {
        val r = Run(image)
        val move = one("NEXT_WORKING_DAY")
        val level = ScopeLevel.ENTITY
        assertEquals(PolicyRefusal.RELAXATION_REQUIRED, r.refusedPut(set(DUE_DATE, r.e1, level, move)))
        val solo = r.ack(ackCmd(DUE_DATE, r.e1, level, move, approver, "tax.approver"))
        assertTrue(solo.complete)
        // The relaxation does not cover another scope or value (the match key, INV-Q-3138).
        val elsewhere = set(DUE_DATE, PolicyScope.entity(r.t, "E9"), level, move, relaxation = solo.relaxation)
        assertEquals(PolicyRefusal.RELAXATION_REQUIRED, r.refusedPut(elsewhere))
        val otherValue = set(DUE_DATE, r.e1, level, one("NO_MOVE"), relaxation = solo.relaxation)
        assertEquals(PolicyRefusal.NOT_LOOSER, r.refusedPut(otherValue))
        r.put(set(DUE_DATE, r.e1, level, move, relaxation = solo.relaxation))
        assertEquals(1L, r.acknowledgements())
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
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
    @ValueSource(strings = ["postgres:17", "postgres:18"])
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
    @ValueSource(strings = ["postgres:17", "postgres:18"])
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
    @ValueSource(strings = ["postgres:17", "postgres:18"])
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
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `an acknowledger must hold a named role, and a distinct-from-setter role cannot be the setter`(image: String) {
        val r = Run(image)
        val single = one("SINGLE_ATTESTATION")
        val level = ScopeLevel.ENTITY
        val notHeld = ackCmd(DISTINCT_ACK, r.e1, level, single, admin, "tax.approver")
        assertEquals(PolicyRefusal.ACKNOWLEDGER_NOT_ALLOWED, r.refusedAck(notHeld))
        val strict = ackCmd(DISTINCT_ACK, r.e1, level, one("PREPARER_AND_APPROVER"), approver, "tax.approver")
        assertEquals(PolicyRefusal.NOT_LOOSER, r.refusedAck(strict))
        val relaxation = r.ack(ackCmd(DISTINCT_ACK, r.e1, level, single, approver, "tax.approver")).relaxation
        val bySetter = set(DISTINCT_ACK, r.e1, level, single, relaxation = relaxation)
        assertEquals(PolicyRefusal.RELAXATION_REQUIRED, r.refusedPut(bySetter))
        r.put(set(DISTINCT_ACK, r.e1, level, single, actor("b", "tax.approver"), relaxation = relaxation))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
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
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-2234 the deployment operator sets a platform maximum in the private form`(image: String) {
        val r = Run(image)
        val h = r.h
        val w = h.writer(form = DeploymentForm.PRIVATE, platform = true)
        val smaller = listOf(SelectedOption("SMALLER", "1048576"))
        val installation = PolicyScope.installation()
        val operator = actor("op", "deployment.operator")
        val before = h.events(TenantId.PLATFORM).size
        val wrongRole = set(PLATFORM_MAXIMUM, installation, ScopeLevel.INSTALLATION, smaller, admin)
        assertEquals(PolicyRefusal.ROLE_NOT_ALLOWED, refusal { h.inPlatform { w.setPolicy(wrongRole) } })
        val apply = set(PLATFORM_MAXIMUM, installation, ScopeLevel.INSTALLATION, smaller, operator)
        val change = h.inPlatform { w.setPolicy(apply) }
        val id = change.version.id
        assertEquals(1L, h.count("select count(*) from svc.config_installation_setting where id = '$id'"))
        val events = h.events(TenantId.PLATFORM).drop(before)
        assertEquals(listOf("audit", "event"), events.map { it.kind })
        assertTrue(events.all { it.entityRef == EntityRef.PLATFORM.value })
        // A looser option at installation is refused in 1.1.0.
        val larger = set(PLATFORM_MAXIMUM, installation, ScopeLevel.INSTALLATION, one("LARGER"), operator)
        assertEquals(PolicyRefusal.LOOSER_AT_INSTALLATION, refusal { h.inPlatform { w.setPolicy(larger) } })
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-Q-3169 the setting role is checked for profile values too, which keep their profile`(image: String) {
        val r = Run(image)
        val window = one("DAILY_WINDOW")
        val profile = "PRF-1" to "3"
        val fromProfile = SettingSource.PROFILE
        val notAllowed = set(DESPATCH, r.e1, ScopeLevel.ENTITY, window, admin, source = fromProfile, profile = profile)
        assertEquals(PolicyRefusal.ROLE_NOT_ALLOWED, r.refusedPut(notAllowed))
        val incomplete = set(DESPATCH, r.e1, ScopeLevel.ENTITY, window, source = SettingSource.PROFILE)
        assertEquals(PolicyRefusal.PROFILE_INCOMPLETE, r.refusedPut(incomplete))
        r.put(set(DESPATCH, r.e1, ScopeLevel.ENTITY, window, source = SettingSource.PROFILE, profile = profile))
        val source = r.resolve(DESPATCH, r.e1).source as PolicySource.Profile
        assertEquals(profile, source.profileId to source.profileVersion)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-REQ-1226 a failed audit entry leaves nothing stored`(image: String) {
        val r = Run(image)
        r.h.failAudit = true
        try {
            val cmd = set(DESPATCH, r.e1, ScopeLevel.ENTITY, one("DAILY_WINDOW"))
            assertThrows(RuntimeException::class.java) { r.put(cmd) }
        } finally {
            r.h.failAudit = false
        }
        assertEquals(0L, r.settings())
        assertTrue(r.events().isEmpty())
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-1146 the as-of view returns the state in force and later changes, scheduled included`(
        image: String,
    ) {
        val r = Run(image)
        val e1 = r.e1
        val tenth = LocalDate.of(2026, 10, 10)
        val w1 = r.h.writer(clock = clockAt("2026-10-01T02:00:00Z"))
        val a = r.put(set(DESPATCH, e1, ScopeLevel.ENTITY, one("DAILY_WINDOW"), actor("alice", "tax.approver")), w1)
        val bobActor = actor("bob", "tax.approver")
        val bob = set(DESPATCH, e1, ScopeLevel.ENTITY, one("CONTINUOUS"), bobActor, LocalDate.of(2026, 10, 20))
        val b = r.put(bob, w1)
        val w2 = r.h.writer(clock = clockAt("2026-10-15T02:00:00Z"))
        val carol = actor("carol", "tax.approver")
        r.put(set(DESPATCH, e1, ScopeLevel.ENTITY, one("DAILY_WINDOW"), carol, LocalDate.of(2026, 10, 25)), w2)
        val history = r.h.history()
        val entry = r.h.inTenant(r.t) { history.asOf(e1, tenth, setOf(DESPATCH)) }.entries.single()
        assertEquals(one("DAILY_WINDOW"), entry.inForce?.selected)
        assertEquals(listOf("bob", "carol"), entry.changedSince.map { it.setBy })
        // Without a filter every row is listed; one with nothing set shows its default.
        val all = r.h.inTenant(r.t) { history.asOf(e1, tenth) }
        assertTrue(all.entries.any { it.key == BOUNDED && it.inForce?.source == PolicySource.CatalogueDefault })
        val unknown = setOf(VariationPointKey("VP-99"))
        assertThrows(UnknownVariationPoint::class.java) { r.h.inTenant(r.t) { history.asOf(e1, tenth, unknown) } }
        val unlisted = r.h.history(VariationPointCatalogue { WriteCatalogue.rows[it] })
        assertThrows(CatalogueListingUnavailable::class.java) { r.h.inTenant(r.t) { unlisted.asOf(e1, tenth) } }
        val versions = r.h.inTenant(r.t) { history.versions(DESPATCH, e1.target(ScopeLevel.ENTITY)) }
        assertEquals(3, versions.size)
        val diff = history.compare(versions[1], versions[0])
        assertEquals(a.version.id, diff.older.id)
        assertEquals(listOf("CONTINUOUS"), diff.addedOptions)
        assertEquals(listOf("DAILY_WINDOW"), diff.removedOptions)
        assertEquals(b.version.reason, versions[1].reason)
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the acknowledgement table is RLS-forced and insert-only, and purged with its tenant`(image: String) {
        val r = Run(image)
        val h = r.h
        r.ack(ackCmd(DUE_DATE, r.e1, ScopeLevel.ENTITY, one("NEXT_WORKING_DAY"), approver, "tax.approver"))
        val table = "svc.config_relaxation_acknowledgement"
        val forced =
            "select count(*) from pg_class where oid = '$table'::regclass " +
                "and relrowsecurity and relforcerowsecurity"
        assertEquals(1L, h.count(forced))
        fun update(sql: String) = h.app.require().createStatement().use { it.executeUpdate(sql) }
        val seen =
            h.inTenant(TenantId(UUID.randomUUID())) {
                h.app.require().createStatement().use { s ->
                    s.executeQuery("select count(*) from $table").use { it.next().let { _ -> it.getInt(1) } }
                }
            }
        assertEquals(0, seen)
        assertThrows(SQLException::class.java) { h.inTenant(r.t) { update("update $table set role = 'x'") } }
        assertThrows(SQLException::class.java) { h.inTenant(r.t) { update("delete from $table") } }
        val purged = h.tenants.bind(r.t) { h.purgeTx.inTransaction { h.purge().purgeTenant(r.t) } }
        assertEquals(1, purged.relaxationAcknowledgements)
        assertEquals(0L, r.acknowledgements())
    }
}
