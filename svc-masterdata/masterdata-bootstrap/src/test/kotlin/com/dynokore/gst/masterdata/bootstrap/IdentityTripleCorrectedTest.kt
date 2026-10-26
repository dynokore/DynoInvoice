package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.IdentityTripleCorrectedOutbox
import com.dynokore.gst.masterdata.adapters.events.MasterdataEventCatalogue
import com.dynokore.gst.masterdata.adapters.events.PublishedChannels
import com.dynokore.gst.masterdata.adapters.http.ApiRefusal
import com.dynokore.gst.masterdata.adapters.http.IdentityTriplesController
import com.dynokore.gst.masterdata.application.identity.ConfirmCommand
import com.dynokore.gst.masterdata.application.identity.ConfirmIdentityTripleChange
import com.dynokore.gst.masterdata.application.identity.ProposeCommand
import com.dynokore.gst.masterdata.application.identity.ProposeIdentityTripleChange
import com.dynokore.gst.masterdata.contract.model.ConfigureIdentityTripleRequest
import com.dynokore.gst.masterdata.contract.model.SecondConfirmation
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PendingChange
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.dynokore.platform.outboxandqueue.EntityRef as OutboxEntityRef

/** Makes the append of `IdentityTripleCorrected` fail while [armed] (the rollback probe). */
class FailingCorrectionWriter(
    private val delegate: OutboxWriter,
) : OutboxWriter {
    override fun append(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<Envelope> {
        check(!(armed.get() && events.any { it.type == IdentityTripleCorrectedOutbox.EVENT_TYPE })) {
            "append made to fail"
        }
        return delegate.append(events, context)
    }

    companion object {
        val armed = AtomicBoolean(false)
    }
}

/**
 * INV-T-8017 (INV-REQ-1006, INV-Q-7876 A): `IdentityTripleCorrected` when a confirmed back-dated identity triple change
 * takes effect, and never otherwise. The subject of every count is the rows the outbox holds for the channel's topic.
 */
class IdentityTripleCorrectedTest {
    private val tenant = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val proposer = "IDN-ADMIN"
    private val confirmer = "IDN-ADMIN-2"
    private val agent = Constitution("SECTION_33_2_AGENT")
    private val sole = Constitution("SOLE_PROPRIETOR")
    private val topic = "gst.masterdata.identity-triple-corrected.v1"
    private val mapper = JsonMapper.builder().build()
    private val schemaText =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "gst/contracts/masterdata/schemas/identity-triple-corrected-payload.schema.json",
            ),
        ).use { it.readBytes().toString(Charsets.UTF_8) }
    private val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schemaText)

    private fun run(block: (Env) -> Unit) {
        FailingCorrectionWriter.armed.set(false)
        BoundaryFixture
            .runner()
            .withBean(
                FailingBeanPostProcessor::class.java,
                { FailingBeanPostProcessor() },
            ).run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context))
            }
    }

    class FailingBeanPostProcessor : BeanPostProcessor {
        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any = if (bean is OutboxWriter && bean !is FailingCorrectionWriter) FailingCorrectionWriter(bean) else bean
    }

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val propose = context.getBean(ProposeIdentityTripleChange::class.java)
        val confirm = context.getBean(ConfirmIdentityTripleChange::class.java)
        val api = context.getBean(IdentityTriplesController::class.java)
        val today: LocalDate = LocalDate.now(context.getBean(Clock::class.java).withZone(ZoneOffset.UTC))

        fun <T> asUser(
            actor: String,
            block: () -> T,
        ): T = tenants.bind(tenant, actor) { block() }

        fun triple(n: Int) = IdentityTriple("0195:U-$n", "U-$n", "G-$n")

        fun proposed(
            entity: String,
            constitution: Constitution,
            from: LocalDate,
            n: Int = 1,
        ): PendingChange =
            asUser(proposer) {
                propose.handle(ProposeCommand(EntityRef(entity), constitution, triple(n), from, PersonRef(proposer)))
            }

        fun confirmedAs(
            actor: String,
            entity: String,
            changeRef: String,
        ): Any =
            asUser(actor) {
                try {
                    api.confirmIdentityTripleChange(entity, changeRef, UUID.randomUUID()).statusCode
                } catch (refusal: ApiRefusal) {
                    refusal.code
                }
            }

        /** Proposes and confirms; the confirmation must answer 200. */
        fun configured(
            entity: String,
            constitution: Constitution,
            from: LocalDate,
            n: Int = 1,
        ): PendingChange {
            val change = proposed(entity, constitution, from, n)
            assertThat(confirmedAs(confirmer, entity, change.changeRef)).isEqualTo(HttpStatus.OK)
            return change
        }

        fun events(entity: String): List<String> =
            BoundaryFixture.asSuperuser { c ->
                c
                    .createStatement()
                    .executeQuery(
                        "select data::text from masterdata.outbox where topic = '$topic' " +
                            "and entity_ref = '$entity' order by seq",
                    ).use { rs ->
                        val out = mutableListOf<String>()
                        while (rs.next()) out += rs.getString(1)
                        out
                    }
            }

        fun storedVersions(
            entity: String,
            constitution: Constitution,
        ): List<List<String?>> =
            BoundaryFixture.asSuperuser { c ->
                c
                    .createStatement()
                    .executeQuery(
                        "select effective_from::text, sender_id, supplier_id, supplier_gstn from " +
                            "masterdata.identity_triple where entity_ref = '$entity' and constitution = " +
                            "'${constitution.code}' order by effective_from",
                    ).use { rs ->
                        val out = mutableListOf<List<String?>>()
                        while (rs.next()) out += (1..4).map { rs.getString(it) }
                        out
                    }
            }

        fun changeState(
            entity: String,
            changeRef: String,
        ): String =
            BoundaryFixture.asSuperuser { c ->
                c
                    .createStatement()
                    .executeQuery(
                        "select state from masterdata.identity_triple_change where entity_ref = '$entity' " +
                            "and change_ref = '$changeRef'",
                    ).use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
            }
    }

    private fun unique(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun expected(
        entity: String,
        constitution: Constitution,
        from: LocalDate,
        changeRef: String,
    ) = mapper.readTree(
        """{"entityRef":"$entity","constitution":"${constitution.code}","effectiveFrom":"$from",""" +
            """"changeRef":"$changeRef","accessPointEscalation":true}""",
    )

    @Test
    fun `INV-AC-7823 a confirmed back-dated change appends one event with the original date and the escalation flag`() =
        run { env ->
            val entity = unique("ENT-COR")
            env.configured(entity, agent, LocalDate.of(2026, 1, 1))
            assertThat(env.events(entity)).isEmpty()
            val from = env.today.minusDays(40)
            val change = env.proposed(entity, agent, from, 2)
            assertThat(env.events(entity)).describedAs("the proposal alone").isEmpty()
            assertThat(env.confirmedAs(confirmer, entity, change.changeRef)).isEqualTo(HttpStatus.OK)
            val rows = env.events(entity)
            assertThat(rows).hasSize(1)
            val payload = mapper.readTree(rows.single())
            assertThat(payload).isEqualTo(expected(entity, agent, from, change.changeRef))
            val listed = mapper.readTree(schemaText)["properties"].propertyNames().toSet()
            assertThat(payload.propertyNames().toSet()).isEqualTo(listed)
            assertThat(schema.validate(rows.single(), InputFormat.JSON)).isEmpty()
        }

    @Test
    fun `INV-AC-7823 no event for a proposal, a refusal, a later or equal date, a first triple, secondConfirmation`() =
        run { env ->
            val entity = unique("ENT-NO")
            // the first triple of the entity and constitution: back-dated, but no stored version yet
            val first = env.proposed(entity, agent, LocalDate.of(2026, 1, 1))
            assertThat(env.events(entity)).isEmpty()
            assertThat(env.confirmedAs(proposer, entity, first.changeRef)).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(env.confirmedAs("IDN-OUTSIDER", entity, first.changeRef))
                .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(env.confirmedAs(confirmer, entity, "ITC-unknown")).isEqualTo(ClosedErrorCode.NOT_FOUND)
            assertThat(env.events(entity)).isEmpty()
            assertThat(env.confirmedAs(confirmer, entity, first.changeRef)).isEqualTo(HttpStatus.OK)
            assertThat(env.events(entity)).describedAs("first triple").isEmpty()
            assertThat(env.confirmedAs("IDN-ADVISOR", entity, first.changeRef))
                .isEqualTo(ClosedErrorCode.STALE_AGGREGATE)
            assertThat(env.events(entity)).isEmpty()
            // a date equal to the date of the confirmation, and a later one
            env.configured(entity, agent, env.today, 2)
            assertThat(env.events(entity)).describedAs("equal date").isEmpty()
            env.configured(entity, agent, env.today.plusDays(10), 3)
            assertThat(env.events(entity)).describedAs("later date").isEmpty()
            // back-dated, but the only stored version of this constitution starts after the date of the confirmation
            val onlyFuture = unique("ENT-FUT")
            env.configured(onlyFuture, agent, env.today.plusDays(30))
            env.configured(onlyFuture, agent, env.today.minusDays(5), 2)
            assertThat(env.events(onlyFuture)).describedAs("no stored version on or before the date").isEmpty()
            // a body that carries secondConfirmation is refused 400
            val body =
                ConfigureIdentityTripleRequest(
                    "0195:U-A",
                    "U-A",
                    "G-A",
                    env.today.minusDays(3),
                    agent.code,
                    SecondConfirmation(confirmer, "tenant.admin"),
                )
            val refusal =
                runCatching {
                    env.asUser(proposer) { env.api.configureIdentityTriple(entity, UUID.randomUUID(), body) }
                }.exceptionOrNull()
            assertThat((refusal as ApiRefusal).code).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            assertThat(env.events(entity)).isEmpty()
        }

    @Test
    fun `INV-AC-7823 a correction for one constitution leaves the stored versions of the other unchanged`() =
        run { env ->
            val entity = unique("ENT-ISO")
            env.configured(entity, agent, LocalDate.of(2026, 1, 1))
            env.configured(entity, sole, LocalDate.of(2026, 1, 1), 5)
            val soleBefore = env.storedVersions(entity, sole)
            val from = env.today.minusDays(20)
            val change = env.proposed(entity, agent, from, 2)
            assertThat(env.confirmedAs(confirmer, entity, change.changeRef)).isEqualTo(HttpStatus.OK)
            val payload = mapper.readTree(env.events(entity).single())
            assertThat(payload["constitution"].asString()).isEqualTo(agent.code)
            assertThat(payload).isEqualTo(expected(entity, agent, from, change.changeRef))
            assertThat(env.storedVersions(entity, sole)).isEqualTo(soleBefore)
        }

    @Test
    fun `INV-AC-7823 an append that fails leaves the change pending and no version written`() =
        run { env ->
            val entity = unique("ENT-RB")
            env.configured(entity, agent, LocalDate.of(2026, 1, 1))
            val before = env.storedVersions(entity, agent)
            val change = env.proposed(entity, agent, env.today.minusDays(10), 2)
            FailingCorrectionWriter.armed.set(true)
            try {
                val failure =
                    runCatching {
                        env.asUser(confirmer) {
                            val command = ConfirmCommand(EntityRef(entity), change.changeRef, PersonRef(confirmer))
                            env.confirm.handle(command)
                        }
                    }.exceptionOrNull()
                assertThat(failure).isNotNull()
            } finally {
                FailingCorrectionWriter.armed.set(false)
            }
            assertThat(env.changeState(entity, change.changeRef)).isEqualTo("PENDING_CONFIRMATION")
            assertThat(env.storedVersions(entity, agent)).isEqualTo(before)
            assertThat(env.events(entity)).isEmpty()
            assertThat(env.confirmedAs(confirmer, entity, change.changeRef)).isEqualTo(HttpStatus.OK)
            assertThat(env.events(entity)).hasSize(1)
        }

    @Test
    fun `the channel is published and the emission guard accepts the v1 type and refuses the v2 look-alike`() =
        run { env ->
            assertThat(PublishedChannels.ALL).contains(IdentityTripleCorrectedOutbox.CHANNEL)
            val catalogue = env.context.getBean(EventCatalogue::class.java)
            val v1 = "com.dynokore.gst.masterdata.IdentityTripleCorrected.v1"
            val v2 = "com.dynokore.gst.masterdata.IdentityTripleCorrected.v2"
            assertThat(IdentityTripleCorrectedOutbox.EVENT_TYPE).isEqualTo(v1)
            assertThat(catalogue.find(v1)?.isProducedBy(MasterdataEventCatalogue.SERVICE)).isTrue()
            assertThat(catalogue.find(v2)).isNull()
            val writer = env.context.getBean(OutboxWriter::class.java)
            val tx = env.context.getBean(TransactionRunner::class.java)
            val context =
                EventContext(
                    tenant = tenant,
                    entity = OutboxEntityRef("ENT-GUARD"),
                    docUuid = null,
                    transactionDate = null,
                    aggregateKey = "ITC-guard",
                    aggregateVersion = null,
                    correlation = CorrelationId("c-1"),
                    causation = CausationId("c-1"),
                )
            val refused =
                runCatching {
                    val event = listOf(OutboundEvent(v2, "{}"))
                    env.asUser(proposer) { tx.inTransaction { writer.append(event, context) } }
                }.exceptionOrNull()
            assertThat(refused).isInstanceOf(EmissionNotPermitted::class.java)
            assertThat(env.events("ENT-GUARD")).isEmpty()
        }
}
