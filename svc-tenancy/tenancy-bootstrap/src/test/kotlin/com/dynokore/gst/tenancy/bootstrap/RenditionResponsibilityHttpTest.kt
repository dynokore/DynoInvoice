package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.rendition.ConsumedRenditionEvents
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.RenditionResponsibilityRequest
import com.dynokore.gst.tenancy.contract.model.RenditionResponsibilityResponse
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

private const val CONSUMER = "renditionFindingChangedConsumer"
private const val ATTESTER = "identity:USR-R1"
private const val OTHER = "identity:USR-R2"
private val APPLIED_ONE = PollResult(1, 0, 0, 0, 0)

/**
 * recordRenditionResponsibility and the RenditionFindingChanged consumer at the service boundary (INV-T-16411): the
 * response, the stored rows and the event deliveries. The attestation has no read operation in the contract at this
 * commit, so the tests read the stored row (INV-AC-2008).
 */
class RenditionResponsibilityHttpTest {
    private val json = EnvelopeJson()

    private class Rows(
        val state: String,
        val renderer: String,
        val attestedBy: String,
        val attestedAt: Instant?,
    )

    private inner class Env(
        val e: CertificationEnv,
        val log: TopicLog,
    ) {
        val people: TestPeople = e.people

        fun record(
            entity: EntityResponse,
            documentClass: String,
            renderer: RenditionResponsibilityRequest.Renderer = RenditionResponsibilityRequest.Renderer.SELF,
            attestedBy: String = people.caller,
        ): ResponseEntity =
            try {
                e.env.api
                    .recordRenditionResponsibility(
                        entity.entityRef,
                        UUID.randomUUID(),
                        RenditionResponsibilityRequest(documentClass, renderer, attestedBy),
                    ).let { ResponseEntity(it.statusCode.value(), it.body) }
            } catch (refusal: ApiRefusal) {
                ResponseEntity(Problems.render(refusal.code, "corr-test", refusal.detail).status, null)
            }

        fun row(
            entity: EntityResponse,
            documentClass: String,
        ): Rows? =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select a.state, a.renderer, a.attested_by, a.attested_at from tenancy.rendition_attestation a " +
                            "join tenancy.entity e on e.tenant_id = a.tenant_id and e.id = a.entity_id " +
                            "where e.ref = ? and a.document_class = ?",
                    ).use { st ->
                        st.setString(1, entity.entityRef)
                        st.setString(2, documentClass)
                        st.executeQuery().use { rs ->
                            if (rs.next()) {
                                Rows(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4)?.toInstant())
                            } else {
                                null
                            }
                        }
                    }
            }

        fun rowCount(entity: EntityResponse): Int =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select count(*) from tenancy.rendition_attestation a " +
                            "join tenancy.entity e on e.tenant_id = a.tenant_id and e.id = a.entity_id where e.ref = ?",
                    ).use { st ->
                        st.setString(1, entity.entityRef)
                        st.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
            }

        fun lastSequence(entity: EntityResponse): Long? =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "select s.last_sequence from tenancy.rendition_finding_sequence s " +
                            "join tenancy.entity e on e.tenant_id = s.tenant_id and e.id = s.entity_id where e.ref = ?",
                    ).use { st ->
                        st.setString(1, entity.entityRef)
                        st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                    }
            }

        fun finding(
            tenant: TenantId,
            entity: EntityResponse,
            open: Boolean,
            sequence: Long,
            topic: String = ConsumedRenditionEvents.TOPIC,
        ) {
            val data =
                "{\"entityRef\":\"${entity.entityRef}\",\"open\":$open,\"code\":\"PAY-014\",\"sequence\":$sequence," +
                    "\"changedAt\":\"2026-11-08T00:00:00Z\"}"
            val event =
                Envelope(
                    EventId(UUID.randomUUID().toString()),
                    "/gst/payload-svc",
                    ConsumedRenditionEvents.TYPE,
                    entity.entityRef,
                    Instant.now(),
                    tenant,
                    EntityRef(entity.entityRef),
                    null,
                    null,
                    null,
                    "test-0",
                    CorrelationId("corr-${UUID.randomUUID()}"),
                    CausationId("cause-${UUID.randomUUID()}"),
                    data,
                )
            log.add(topic, json.write(event))
        }

        fun poll(): PollResult = e.context().getBean(CONSUMER, InboxConsumer::class.java).pollOnce()
    }

    private class ResponseEntity(
        val status: Int,
        val body: RenditionResponsibilityResponse?,
    )

    private fun run(block: (Env, TenantId, EntityResponse) -> Unit) {
        val log = TopicLog()
        BoundaryFixture
            .runner()
            .withBean(EventLogReader::class.java, { log })
            .run { context ->
                context.startupFailure?.let { throw it }
                val profile = ProfileEnv(context)
                val env = Env(CertificationEnv(profile, log), log)
                profile.withEntity { tenant, entity ->
                    env.people.caller = ATTESTER
                    block(env, tenant, entity)
                }
            }
    }

    @Test
    fun `INV-AC-2008 recordRenditionResponsibility answers 201 and the stored rows equal the requests`() {
        run { env, _, entity ->
            val invoices = env.record(entity, "TAX_INVOICE", RenditionResponsibilityRequest.Renderer.SELF)
            val notes = env.record(entity, "CREDIT_NOTE", RenditionResponsibilityRequest.Renderer.AGENT)
            assertThat(invoices.status).isEqualTo(HttpStatus.CREATED.value())
            assertThat(invoices.body!!.entityRef).isEqualTo(entity.entityRef)
            assertThat(invoices.body.documentClass).isEqualTo("TAX_INVOICE")
            assertThat(invoices.body.renderer).isEqualTo(RenditionResponsibilityResponse.Renderer.SELF)
            assertThat(notes.body!!.renderer).isEqualTo(RenditionResponsibilityResponse.Renderer.AGENT)
            val invoiceRow = env.row(entity, "TAX_INVOICE")!!
            val noteRow = env.row(entity, "CREDIT_NOTE")!!
            assertThat(invoiceRow.renderer).isEqualTo("SELF")
            assertThat(noteRow.renderer).isEqualTo("AGENT")
            assertThat(invoiceRow.attestedBy).isEqualTo(ATTESTER)
            assertThat(noteRow.attestedBy).isEqualTo(ATTESTER)
            assertThat(invoiceRow.state).isEqualTo("CURRENT")
            assertThat(invoiceRow.attestedAt).isNotNull()
        }
    }

    @Test
    fun `INV-AC-461 an open finding event sets every recorded class REQUESTED_AGAIN and a new record sets CURRENT`() {
        run { env, tenant, entity ->
            env.record(entity, "TAX_INVOICE")
            env.record(entity, "CREDIT_NOTE")
            env.finding(tenant, entity, open = true, sequence = 1)
            assertThat(env.poll()).isEqualTo(APPLIED_ONE)
            assertThat(env.row(entity, "TAX_INVOICE")!!.state).isEqualTo("REQUESTED_AGAIN")
            assertThat(env.row(entity, "CREDIT_NOTE")!!.state).isEqualTo("REQUESTED_AGAIN")
            assertThat(env.lastSequence(entity)).isEqualTo(1)
            env.record(entity, "TAX_INVOICE")
            assertThat(env.row(entity, "TAX_INVOICE")!!.state).isEqualTo("CURRENT")
            assertThat(env.row(entity, "CREDIT_NOTE")!!.state).isEqualTo("REQUESTED_AGAIN")
        }
    }

    @Test
    fun `probe attestedBy different from the caller is 403 and no row is stored`() {
        run { env, _, entity ->
            val answer = env.record(entity, "TAX_INVOICE", attestedBy = OTHER)
            assertThat(answer.status).isEqualTo(HttpStatus.FORBIDDEN.value())
            assertThat(env.rowCount(entity)).isZero()
        }
    }

    @Test
    fun `probe attestedBy of a look-alike case of the caller is 403 and no row is stored`() {
        run { env, _, entity ->
            val answer = env.record(entity, "TAX_INVOICE", attestedBy = ATTESTER.uppercase())
            assertThat(answer.status).isEqualTo(HttpStatus.FORBIDDEN.value())
            assertThat(env.rowCount(entity)).isZero()
        }
    }

    @Test
    fun `probe an event delivered twice is one state change`() {
        run { env, tenant, entity ->
            env.record(entity, "TAX_INVOICE")
            env.finding(tenant, entity, open = true, sequence = 7)
            assertThat(env.poll()).isEqualTo(APPLIED_ONE)
            env.record(entity, "TAX_INVOICE")
            env.finding(tenant, entity, open = true, sequence = 7)
            env.poll()
            assertThat(env.row(entity, "TAX_INVOICE")!!.state).isEqualTo("CURRENT")
            assertThat(env.lastSequence(entity)).isEqualTo(7)
        }
    }

    @Test
    fun `probe a lower sequence changes nothing`() {
        run { env, tenant, entity ->
            env.record(entity, "TAX_INVOICE")
            env.finding(tenant, entity, open = false, sequence = 5)
            env.poll()
            env.finding(tenant, entity, open = true, sequence = 4)
            env.poll()
            assertThat(env.row(entity, "TAX_INVOICE")!!.state).isEqualTo("CURRENT")
            assertThat(env.lastSequence(entity)).isEqualTo(5)
        }
    }

    @Test
    fun `probe an event of the look-alike topic rendition-finding-changed v2 is ignored`() {
        run { env, tenant, entity ->
            env.record(entity, "TAX_INVOICE")
            env.finding(tenant, entity, open = true, sequence = 1, topic = "gst.payload.rendition-finding-changed.v2")
            env.poll()
            assertThat(env.row(entity, "TAX_INVOICE")!!.state).isEqualTo("CURRENT")
            assertThat(env.lastSequence(entity)).isNull()
        }
    }

    @Test
    fun `probe a finding for an entity without a record stores no attestation row`() {
        run { env, tenant, entity ->
            env.finding(tenant, entity, open = true, sequence = 1)
            env.poll()
            assertThat(env.rowCount(entity)).isZero()
        }
    }
}
