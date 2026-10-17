package com.dynokore.platform.configresolver

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditStager
import com.dynokore.platform.configresolver.ConfigSchema.EventMode
import com.dynokore.platform.configresolver.WriteHarness.Companion.CAUSATION
import com.dynokore.platform.configresolver.WriteHarness.Companion.CORRELATION
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Direct calls of the installation write and purge functions on the runtime login (INV-T-3983), with the rows staged
 * as [JdbcPolicyWriter] stages them, for the tests that plant faults and check the database's side.
 */
internal object InstallationCalls {
    val mapper: JsonMapper = JsonMapper.builder().build()
    val effective: LocalDate = LocalDate.of(2026, 11, 1)

    /** Long before any database time the tests run at: a staged time that must never be stored (INV-Q-4316). */
    val backdated: Clock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)

    /** The context [JdbcPolicyWriter] builds for an installation value of VP-78. */
    val context =
        EventContext(
            tenant = TenantId.PLATFORM,
            entity = EntityRef.PLATFORM,
            docUuid = null,
            transactionDate = null,
            aggregateKey = "config:VP-78:INSTALLATION",
            aggregateVersion = null,
            correlation = CORRELATION,
            causation = CAUSATION,
        )
}

/** The arguments of one call of `write_installation_setting`, the staged rows as JSON objects. */
internal data class InstallationArgs(
    val vpKey: String,
    val optionKeys: List<String>,
    val optionValues: String?,
    val effectiveFrom: LocalDate,
    val reason: String,
    val audit: ObjectNode,
    val policyChanged: ObjectNode?,
)

/** One planted fault: the call it makes and the SQLSTATE it must get. */
internal data class FaultCall(
    val args: InstallationArgs,
    val state: String = "22023",
    val tenant: String? = TenantId.PLATFORM.value.toString(),
    val actor: String? = "op",
)

/**
 * The arguments [JdbcPolicyWriter] passes for an installation value of VP-78, its rows staged by the real stage
 * calls on a backdated clock in a platform transaction bound to [actor], encoded as the writer encodes them.
 */
internal fun WriteHarness.staged(actor: String = "op"): InstallationArgs {
    val clock = InstallationCalls.backdated
    val context = InstallationCalls.context
    val reason = "platform maximum"
    val entry =
        AuditEntry(
            actor = actor,
            action = JdbcPolicyWriter.SET_POLICY,
            scope = "INSTALLATION",
            reason = reason,
            priorStateJson = "{\"value\":null}",
        )
    val payload =
        "{\"scope\":\"INSTALLATION\",\"vpId\":\"VP-78\",\"value\":[{\"key\":\"SMALLER\",\"value\":1048576}]," +
            "\"effectiveDate\":\"${InstallationCalls.effective}\",\"source\":\"OVERRIDE\"}"
    return inPlatform(actor) {
        val audit = (installationAudit(app, clock) as AuditStager).stage(entry, context)
        val changed =
            if (mode == EventMode.OUTBOX) {
                installationOutbox(app, clock)
                    .stage(listOf(OutboundEvent(JdbcPolicyWriter.POLICY_CHANGED, payload)), context)
                    .single()
            } else {
                null
            }
        InstallationArgs(
            "VP-78",
            listOf("SMALLER"),
            "{\"SMALLER\":1048576}",
            InstallationCalls.effective,
            reason,
            json(audit.envelope, null),
            changed?.let { json(it.envelope, it.topic) },
        )
    }
}

private fun json(
    envelope: Envelope,
    topic: String?,
): ObjectNode =
    InstallationCalls.mapper.createObjectNode().apply {
        put("id", envelope.id.value)
        put("type", envelope.type)
        topic?.let { put("topic", it) }
        put("source", envelope.source)
        put("subject", envelope.subject)
        put("tenant_id", envelope.tenant.value.toString())
        put("entity_ref", envelope.entity.value)
        put("instance_id", envelope.instanceId)
        put("correlation_id", envelope.correlation.value)
        put("causation_id", envelope.causation.value)
        set("data", InstallationCalls.mapper.readTree(envelope.dataJson))
    }

/** Calls the function on a raw runtime-login transaction with [tenant] and [actor] set as given; commits. */
internal fun WriteHarness.callInstallation(
    args: InstallationArgs,
    tenant: String? = TenantId.PLATFORM.value.toString(),
    actor: String? = "op",
): List<UUID> =
    raw(tenant, actor) {
        fetchRows(
            "select setting_id from svc.write_installation_setting(" +
                "?, ?::text[], ?::jsonb, ?::date, ?, ?::jsonb, ?::jsonb)",
            args.vpKey,
            args.optionKeys.toTypedArray(),
            args.optionValues,
            args.effectiveFrom,
            args.reason,
            InstallationCalls.mapper.writeValueAsString(args.audit),
            args.policyChanged?.let { p -> InstallationCalls.mapper.writeValueAsString(p) },
        ).map { it.values.single() as UUID }
    }

/** The SQLSTATE [sql] fails with on a raw runtime-login transaction with [tenant] and [actor] set as given. */
internal fun WriteHarness.refusedRaw(
    sql: String,
    tenant: String?,
    actor: String?,
): String? = refusal { raw(tenant, actor) { fetchValue(sql) } }.sqlState

/** Rows of every table an installation write touches. */
internal fun WriteHarness.stored(): List<Long> =
    listOfNotNull(
        count("select count(*) from svc.config_installation_setting"),
        if (mode == EventMode.OUTBOX) count("select count(*) from svc.outbox") else null,
        if (mode == EventMode.NO_OUTBOX) count("select count(*) from svc.audit_entry") else null,
    )
