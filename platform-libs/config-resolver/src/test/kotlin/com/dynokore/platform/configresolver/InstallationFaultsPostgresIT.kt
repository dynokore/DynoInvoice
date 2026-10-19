package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.ConfigSchema.EventMode
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * INV-T-3983: every check of `write_installation_setting` refuses before any insert, with nothing written (coordinator
 * ruling INV-Q-4254 A): one planted fault per check, on PostgreSQL 16, 17 and 18, in outbox and no-outbox mode.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstallationFaultsPostgresIT {
    private val harnesses = mutableMapOf<Pair<String, EventMode>, WriteHarness>()

    @AfterAll
    fun stop() = harnesses.values.forEach { it.close() }

    fun cases() =
        listOf("postgres:16", "postgres:17", "postgres:18").flatMap { image ->
            EventMode.entries.map { Arguments.of(image, it) }
        }

    @ParameterizedTest(name = "{displayName}[{0}, {1}]")
    @MethodSource("cases")
    fun `every check refuses before any insert, with nothing written`(
        image: String,
        mode: EventMode,
    ) {
        val h = harnesses.getOrPut(image to mode) { WriteHarness(image, mode) }
        val faults = faults(mode)
        faults.forEach { (name, fault) ->
            val args = h.staged()
            val call = fault(args)
            val counts = h.stored()
            val refused = h.refusal { h.callInstallation(call.args, call.tenant, call.actor) }
            assertEquals(call.state, refused.sqlState, "$name: ${refused.message}")
            val check = checkNotNull(CHECKS[name]) { "no expected check for $name" }
            assertTrue(refused.message.contains(check), "$name refused by another check: ${refused.message}")
            assertEquals(counts, h.stored(), "$name wrote rows")
        }
        assertEquals(1, h.callInstallation(h.staged()).size, "the unfaulted call writes")
    }

    private fun ObjectNode.edit(
        path: String,
        value: String?,
    ): ObjectNode =
        deepCopy().also { copy ->
            val parent = if (path.startsWith("data.")) copy.get("data") as ObjectNode else copy
            val field = path.removePrefix("data.")
            if (value == null) parent.remove(field) else parent.set(field, InstallationCalls.mapper.readTree(value))
        }

    private fun InstallationArgs.auditWith(
        path: String,
        value: String?,
    ) = copy(audit = audit.edit(path, value))

    private fun InstallationArgs.changedWith(
        path: String,
        value: String?,
    ) = copy(policyChanged = checkNotNull(policyChanged).edit(path, value))

    private fun audited(
        path: String,
        value: String?,
    ): (InstallationArgs) -> FaultCall = { a -> FaultCall(a.auditWith(path, value)) }

    private fun changed(
        path: String,
        value: String?,
    ): (InstallationArgs) -> FaultCall = { a -> FaultCall(a.changedWith(path, value)) }

    @Suppress("LongMethod") // the full list of planted faults, one per check of the function
    private fun faults(mode: EventMode): List<Pair<String, (InstallationArgs) -> FaultCall>> {
        val platform = TenantId.PLATFORM.value.toString()
        val v4 = "\"${UUID.randomUUID()}\""
        val common =
            listOf<Pair<String, (InstallationArgs) -> FaultCall>>(
                "actor unset" to { a -> FaultCall(a, "42501", actor = null) },
                "unbound tenant" to { a -> FaultCall(a, "42501", tenant = null) },
                "a tenant bound" to { a -> FaultCall(a, "42501", tenant = UUID.randomUUID().toString()) },
                "audit actor not the bound actor" to { a -> FaultCall(a, "42501", actor = "someone-else") },
                "audit tenant" to audited("tenant_id", "\"${UUID.randomUUID()}\""),
                "audit entity" to audited("entity_ref", "\"E1\""),
                "audit type" to audited("type", "\"${JdbcPolicyWriter.POLICY_CHANGED}\""),
                "audit topic given" to audited("topic", "\"gst.platform.policy-changed.v1\""),
                "audit id not v7" to audited("id", v4),
                "audit source" to audited("source", "\"gst/x\""),
                "audit subject" to audited("subject", "\"config:VP-12:INSTALLATION\""),
                "audit correlation blank" to audited("correlation_id", "\" \""),
                "audit staged time" to audited("event_time", "\"2020-01-01T00:00:00Z\""),
                "audit missing column" to audited("instance_id", null),
                "audit action" to audited("data.action", "\"acknowledgeControlRelaxation\""),
                "audit scope" to audited("data.scope", "\"TENANT:$platform\""),
                "audit reason" to audited("data.reason", "\"another reason\""),
                "audit extra field" to audited("data.extra", "1"),
                "audit prior state not an object" to audited("data.priorState", "[]"),
                "audit prior state missing" to audited("data.priorState", null),
                "option value of an unselected option" to { a -> FaultCall(a.copy(optionValues = "{\"LARGER\":1}")) },
                "blank reason" to { a -> FaultCall(a.copy(reason = " ").auditWith("data.reason", "\" \"")) },
            )
        val modeFaults =
            when (mode) {
                EventMode.NO_OUTBOX -> {
                    listOf<Pair<String, (InstallationArgs) -> FaultCall>>(
                        "a PolicyChanged without an outbox" to { a ->
                            val changed = a.audit.deepCopy().also { it.put("type", JdbcPolicyWriter.POLICY_CHANGED) }
                            FaultCall(a.copy(policyChanged = changed))
                        },
                    )
                }

                EventMode.OUTBOX -> {
                    listOf<Pair<String, (InstallationArgs) -> FaultCall>>(
                        "no PolicyChanged in outbox mode" to { a -> FaultCall(a.copy(policyChanged = null)) },
                        "PolicyChanged tenant" to changed("tenant_id", "\"${UUID.randomUUID()}\""),
                        "PolicyChanged entity" to changed("entity_ref", "\"TENANT\""),
                        "PolicyChanged type" to changed("type", "\"${WriteHarness.AUDIT}\""),
                        "PolicyChanged topic" to changed("topic", "\"${WriteHarness.AUDIT_TOPIC}\""),
                        "PolicyChanged topic missing" to changed("topic", null),
                        "PolicyChanged id not v7" to changed("id", v4),
                        "PolicyChanged subject" to changed("subject", "\"config:VP-78:TENANT\""),
                        "rows disagree on correlation" to changed("correlation_id", "\"corr-2\""),
                        "rows disagree on source" to changed("source", "\"/gst/other-svc\""),
                        "PolicyChanged scope" to changed("data.scope", "\"GROUP:G1\""),
                        "PolicyChanged vpId" to changed("data.vpId", "\"VP-12\""),
                        "PolicyChanged value" to { a ->
                            FaultCall(a.changedWith("data.value", "[{\"key\":\"SMALLER\",\"value\":2097152}]"))
                        },
                        "PolicyChanged effectiveDate" to { a ->
                            FaultCall(a.changedWith("data.effectiveDate", "\"2026-12-01\""))
                        },
                        "PolicyChanged source" to changed("data.source", "\"PROFILE\""),
                        "PolicyChanged extra field" to { a ->
                            FaultCall(a.changedWith("data.relaxationAcknowledgementReference", "\"x\""))
                        },
                        "PolicyChanged data not an object" to changed("data", "[]"),
                    )
                }
            }
        return common + modeFaults
    }

    companion object {
        /** The check each fault must be refused by: its message, so a fault caught by an earlier check fails. */
        private val CHECKS =
            mapOf(
                "actor unset" to "the bound actor is not set",
                // a signed binding carries the tenant and the actor together: without a tenant the actor is unbound too
                "unbound tenant" to "the bound actor is not set",
                "a tenant bound" to "under the platform tenant only",
                "audit actor not the bound actor" to "audit entry actor is not the bound actor",
                "audit tenant" to "audit entry row is not under the platform tenant and entity",
                "audit entity" to "audit entry row is not under the platform tenant and entity",
                "audit type" to "audit entry row has type",
                "audit topic given" to "audit entry row has another shape",
                "audit id not v7" to "audit entry row id is not a version-7 UUID",
                "audit source" to "audit entry row source is not",
                "audit subject" to "audit entry row subject is not",
                "audit correlation blank" to "audit entry row needs an instance, correlation and causation",
                "audit staged time" to "audit entry row has another shape",
                "audit missing column" to "audit entry row has another shape",
                "audit action" to "audit entry action is",
                "audit scope" to "audit entry scope is",
                "audit reason" to "audit entry reason is not",
                "audit extra field" to "audit entry payload has another shape",
                "audit prior state not an object" to "audit entry has no prior state object",
                "audit prior state missing" to "audit entry has no prior state object",
                "option value of an unselected option" to "not selected",
                "blank reason" to "are required",
                "a PolicyChanged without an outbox" to "no PolicyChanged row is written",
                "no PolicyChanged in outbox mode" to "writes PolicyChanged with every installation value",
                "PolicyChanged tenant" to "PolicyChanged row is not under the platform tenant and entity",
                "PolicyChanged entity" to "PolicyChanged row is not under the platform tenant and entity",
                "PolicyChanged type" to "PolicyChanged row has type",
                "PolicyChanged topic" to "PolicyChanged row has topic",
                "PolicyChanged topic missing" to "PolicyChanged row has topic",
                "PolicyChanged id not v7" to "PolicyChanged row id is not a version-7 UUID",
                "PolicyChanged subject" to "PolicyChanged row subject is not",
                "rows disagree on correlation" to "disagree",
                "rows disagree on source" to "disagree",
                "PolicyChanged scope" to "PolicyChanged scope is",
                "PolicyChanged vpId" to "PolicyChanged vpId is",
                "PolicyChanged value" to "PolicyChanged value is not",
                "PolicyChanged effectiveDate" to "PolicyChanged effectiveDate is",
                "PolicyChanged source" to "another source or other fields",
                "PolicyChanged extra field" to "another source or other fields",
                "PolicyChanged data not an object" to "PolicyChanged row data is not an object",
            )
    }
}
