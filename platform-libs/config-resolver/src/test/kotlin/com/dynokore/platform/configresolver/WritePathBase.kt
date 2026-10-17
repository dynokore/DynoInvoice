package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.WriteHarness.Companion.CAUSATION
import com.dynokore.platform.configresolver.WriteHarness.Companion.CORRELATION
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.util.UUID

/** Shared helpers of the write-path integration tests: one harness per image, one tenant per [Run]. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class WritePathBase {
    protected val harnesses = mutableMapOf<String, WriteHarness>()

    @AfterAll
    fun stop() = harnesses.values.forEach { it.close() }

    protected val today = LocalDate.of(2026, 10, 1)
    protected val nextMonth = LocalDate.of(2026, 11, 1)
    protected val approver = Actor("ta", setOf("tax.approver"))
    protected val admin = Actor("admin", setOf("tenant.admin"))

    /** One tenant on one database; every helper writes inside that tenant. */
    protected inner class Run(
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

        fun settings() = h.count("select count(*) from svc.config_setting where tenant_id = '$t'", t)

        fun acknowledgements(extra: String = ""): Long {
            val table = "svc.config_relaxation_acknowledgement"
            return h.count("select count(*) from $table where tenant_id = '$t' $extra", t)
        }

        fun events() = h.events(t)
    }

    protected fun one(key: String) = listOf(SelectedOption(key))

    protected fun actor(
        principal: String,
        vararg roles: String,
    ) = Actor(principal, roles.toSet())

    @Suppress("LongParameterList") // mirrors SetPolicyCommand's fields with test defaults
    protected fun set(
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

    @Suppress("LongParameterList") // mirrors AcknowledgeRelaxationCommand's fields with test defaults
    protected fun ackCmd(
        key: VariationPointKey,
        scope: PolicyScope,
        level: ScopeLevel,
        selected: List<SelectedOption>,
        actor: Actor,
        asRole: String,
        relaxation: RelaxationId? = null,
        from: LocalDate = today,
    ) = AcknowledgeRelaxationCommand(
        key,
        scope,
        level,
        selected,
        from,
        actor,
        asRole,
        CORRELATION,
        CAUSATION,
        relaxation,
        "accepted risk",
    )

    protected fun refusal(block: () -> Unit) = assertThrows(PolicyRefused::class.java, block).refusal
}
