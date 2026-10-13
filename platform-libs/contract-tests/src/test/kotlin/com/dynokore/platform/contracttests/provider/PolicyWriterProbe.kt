package com.dynokore.platform.contracttests.provider

import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.DelegationProbe
import com.dynokore.platform.configresolver.DeploymentForm
import com.dynokore.platform.configresolver.JdbcPolicyWriter
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.SettingSource
import com.dynokore.platform.configresolver.WriteCatalogue
import com.dynokore.platform.configresolver.WriteHarness
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate
import java.util.UUID

/**
 * The provider side of the PolicyChanged pact: config-resolver's real [JdbcPolicyWriter] on PostgreSQL (its own
 * write harness, `svc` schema), whose emitted event is put into the envelope outbox-and-queue's [EnvelopeJson] writes
 * from the [EventContext] the writer passed (the envelope is the outbox's; type, tenant, entity and data are the
 * writer's). [payload] lets a fixture change the payload the writer emitted, to prove a broken writer fails the
 * verification (done-when: a deliberately broken contract makes the job fail).
 */
class PolicyWriterProbe(
    image: String = "postgres:18",
) : AutoCloseable {
    private val harness = WriteHarness(image)
    private val envelopes = EnvelopeJson()

    /** The PolicyChanged an entity-level override of VP-12 emits, as its log record's JSON. */
    fun entityOverride(payload: (String) -> String = { it }): String {
        val tenant = TenantId(UUID.randomUUID())
        val emitted = mutableListOf<String>()
        val outbox =
            object : OutboxWriter {
                override fun append(
                    events: List<OutboundEvent>,
                    context: EventContext,
                ): List<Envelope> =
                    events.map { event ->
                        envelope(event, context, payload(event.dataJson)).also { emitted += envelopes.write(it) }
                    }
            }
        val writer =
            JdbcPolicyWriter(
                connections = harness.app,
                catalogue = WriteCatalogue.catalogue,
                form = DeploymentForm.SAAS,
                schema = WriteHarness.SCHEMA,
                audit = harness.audit(harness.app),
                outbox = outbox,
                clock = WriteHarness.CLOCK,
                delegation = DelegationProbe { _, _ -> false },
            )
        harness.inTenant(tenant) {
            writer.setPolicy(
                SetPolicyCommand(
                    WriteCatalogue.EVERY_LEVEL,
                    PolicyScope.entity(tenant, "E-001"),
                    ScopeLevel.ENTITY,
                    listOf(SelectedOption("B")),
                    LocalDate.of(2026, 10, 1),
                    "because",
                    Actor("admin", setOf("tenant.admin")),
                    WriteHarness.CORRELATION,
                    WriteHarness.CAUSATION,
                    SettingSource.OVERRIDE,
                    null,
                    null,
                    null,
                ),
            )
        }
        return emitted.single()
    }

    private fun envelope(
        event: OutboundEvent,
        context: EventContext,
        dataJson: String,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/tenancy-svc", // the embedding service: config-resolver runs inside a VP-owning service
        type = event.type,
        subject = context.aggregateKey,
        time = WriteHarness.CLOCK.instant(),
        tenant = context.tenant,
        entity = context.entity,
        docUuid = context.docUuid,
        transactionDate = context.transactionDate,
        docSequence = null,
        instanceId = "contract-tests",
        correlation = context.correlation,
        causation = context.causation,
        dataJson = dataJson,
    )

    override fun close() = harness.close()
}
