package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.IdentityTripleChangedOutbox
import com.dynokore.gst.masterdata.adapters.http.ApiRefusal
import com.dynokore.gst.masterdata.adapters.http.IdentityTriplesController
import com.dynokore.gst.masterdata.application.identity.ConfirmCommand
import com.dynokore.gst.masterdata.application.identity.ConfirmIdentityTripleChange
import com.dynokore.gst.masterdata.application.identity.ProposeCommand
import com.dynokore.gst.masterdata.application.identity.ProposeIdentityTripleChange
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PendingChange
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
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

/** Makes the append of `IdentityTripleChanged` fail while [armed] (the rollback probe). */
class FailingChangedWriter(
    private val delegate: OutboxWriter,
) : OutboxWriter {
    override fun append(
        events: List<OutboundEvent>,
        context: EventContext,
    ): List<Envelope> {
        check(!(armed.get() && events.any { it.type == IdentityTripleChangedOutbox.EVENT_TYPE })) {
            "append made to fail"
        }
        return delegate.append(events, context)
    }

    companion object {
        val armed = AtomicBoolean(false)
    }
}

/**
 * INV-T-16430 (INV-REQ-1135, INV-Q-16401 A): every confirmed identity triple change appends exactly one
 * `IdentityTripleChanged` in the confirmation transaction. The subject of every count is the rows the outbox holds.
 */
class IdentityTripleChangedTest {
    private val tenant = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val proposer = "IDN-ADMIN"
    private val confirmer = "IDN-ADMIN-2"
    private val agent = Constitution("SECTION_33_2_AGENT")
    private val changedTopic = "gst.masterdata.identity-triple-changed.v1"
    private val correctedTopic = "gst.masterdata.identity-triple-corrected.v1"
    private val mapper = JsonMapper.builder().build()

    private fun run(block: (Env) -> Unit) {
        FailingChangedWriter.armed.set(false)
        BoundaryFixture
            .runner()
            .withBean(Poster::class.java, { Poster() })
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(context))
            }
    }

    class Poster : BeanPostProcessor {
        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any = if (bean is OutboxWriter && bean !is FailingChangedWriter) FailingChangedWriter(bean) else bean
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

        fun proposed(
            entity: String,
            from: LocalDate,
            n: Int = 1,
        ): PendingChange =
            asUser(proposer) {
                propose.handle(
                    ProposeCommand(
                        EntityRef(entity),
                        agent,
                        IdentityTriple("0195:U-$n", "U-$n", "G-$n"),
                        from,
                        PersonRef(proposer),
                    ),
                )
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

        fun configured(
            entity: String,
            from: LocalDate,
            n: Int = 1,
        ): PendingChange {
            val change = proposed(entity, from, n)
            assertThat(confirmedAs(confirmer, entity, change.changeRef)).isEqualTo(HttpStatus.OK)
            return change
        }

        fun rows(
            topic: String,
            entity: String,
        ): List<String> =
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

        fun changed(entity: String) = rows(changedTopic, entity)

        fun corrected(entity: String) = rows(correctedTopic, entity)

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
        from: LocalDate,
        changeRef: String,
    ) = mapper.readTree(
        """{"entityRef":"$entity","constitution":"${agent.code}","effectiveFrom":"$from","changeRef":"$changeRef"}""",
    )

    @Test
    fun `INV-AC-16047 a forward-dated confirmation appends one Changed, zero Corrected, confirmed payload`() =
        run { env ->
            val entity = unique("ENT-FWD")
            val from = env.today.plusDays(10)
            val change = env.proposed(entity, from)
            assertThat(env.changed(entity)).describedAs("the proposal alone").isEmpty()
            assertThat(env.confirmedAs(confirmer, entity, change.changeRef)).isEqualTo(HttpStatus.OK)
            val rows = env.changed(entity)
            assertThat(rows).hasSize(1)
            assertThat(mapper.readTree(rows.single())).isEqualTo(expected(entity, from, change.changeRef))
            assertThat(env.corrected(entity)).isEmpty()
        }

    @Test
    fun `INV-AC-16047 a back-dated confirmation appends one Changed and one Corrected`() =
        run { env ->
            val entity = unique("ENT-BACK")
            env.configured(entity, LocalDate.of(2026, 1, 1))
            assertThat(env.changed(entity)).hasSize(1)
            val from = env.today.minusDays(30)
            val change = env.proposed(entity, from, 2)
            assertThat(env.confirmedAs(confirmer, entity, change.changeRef)).isEqualTo(HttpStatus.OK)
            assertThat(env.changed(entity)).hasSize(2)
            assertThat(mapper.readTree(env.changed(entity).last())).isEqualTo(expected(entity, from, change.changeRef))
            assertThat(env.corrected(entity)).hasSize(1)
        }

    @Test
    fun `INV-AC-16047 a confirmation by the proposer is refused and appends no event`() =
        run { env ->
            val entity = unique("ENT-SELF")
            val change = env.proposed(entity, env.today.plusDays(1))
            assertThat(env.confirmedAs(proposer, entity, change.changeRef)).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(env.changed(entity)).isEmpty()
            assertThat(env.corrected(entity)).isEmpty()
        }

    @Test
    fun `INV-AC-16047 a transaction that fails after the append leaves no event and the change pending`() =
        run { env ->
            val entity = unique("ENT-RB")
            val change = env.proposed(entity, env.today.plusDays(2))
            FailingChangedWriter.armed.set(true)
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
                FailingChangedWriter.armed.set(false)
            }
            assertThat(env.changed(entity)).isEmpty()
            assertThat(env.changeState(entity, change.changeRef)).isEqualTo("PENDING_CONFIRMATION")
        }

    @Test
    fun `INV-AC-16047 a second confirmation of the same change reference never appends a second Changed`() =
        run { env ->
            val entity = unique("ENT-TWICE")
            val change = env.configured(entity, env.today.plusDays(3))
            assertThat(env.confirmedAs(confirmer, entity, change.changeRef)).isEqualTo(ClosedErrorCode.STALE_AGGREGATE)
            assertThat(env.changed(entity)).hasSize(1)
        }

    @Test
    fun `INV-AC-16047 a change for entity E2 does not append an event with the entityRef of E1`() =
        run { env ->
            val e1 = unique("ENT-E1")
            val e2 = unique("ENT-E2")
            env.configured(e2, env.today.plusDays(4))
            assertThat(env.changed(e1)).isEmpty()
            assertThat(env.changed(e2)).hasSize(1)
            assertThat(mapper.readTree(env.changed(e2).single())["entityRef"].asString()).isEqualTo(e2)
        }
}
