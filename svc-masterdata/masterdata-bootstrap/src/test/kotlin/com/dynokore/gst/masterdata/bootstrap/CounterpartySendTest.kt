package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.persistence.JooqLinkBindings
import com.dynokore.gst.masterdata.application.request.LinkBindings
import com.dynokore.gst.masterdata.application.request.SendMethodPolicy
import com.dynokore.gst.masterdata.domain.request.LinkBinding
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.Filter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val SEND_TENANT = TenantId(UUID.randomUUID())
private const val LINK_REF_LENGTH = 22

/** What VP-30 answers to the test; `null` leaves the real reader of the context in place. */
object SendOption {
    @Volatile var override: String? = null
}

/** Wraps the VP-30 policy of the context so that a test can make it answer CSV_ONLY, OFF or an unknown option. */
class SendOptionPostProcessor : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any =
        if (bean is SendMethodPolicy && beanName == "sendMethodPolicy") {
            SendMethodPolicy { SendOption.override ?: bean.option() }
        } else {
            bean
        }
}

/**
 * INV-AC-20201 (INV-T-20197, INV-REQ-901, INV-REQ-1291) at the HTTP boundary of `sendCounterpartyRequest`: the real
 * context over real PostgreSQL, the identity filters of service-runtime, the permission decided per request through
 * the permission port of the replica fixture, and the outbox and audit writers captured as they append.
 */
class CounterpartySendTest {
    private val resolver =
        EndUserPrincipalResolver { header ->
            when (header) {
                "Bearer T-OP" -> EndUserPrincipal("IDN-OPERATOR", SEND_TENANT, setOf(EntityRef("E-001")))
                "Bearer T-NOKEY" -> EndUserPrincipal("IDN-NOKEY", SEND_TENANT, setOf(EntityRef("E-001")))
                else -> null
            }
        }

    private fun run(block: (Env) -> Unit) {
        SendOption.override = null
        BoundaryFixture
            .runner()
            .withBean(EndUserPrincipalResolver::class.java, { resolver })
            .withPropertyValues("service-runtime.idempotency.schema=masterdata")
            .withBean(SendOptionPostProcessor::class.java)
            .run { context ->
                context.startupFailure?.let { throw it }
                try {
                    block(Env(context))
                } finally {
                    SendOption.override = null
                }
            }
    }

    private class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val captured: CapturedEvents = context.getBean(CapturedEvents::class.java)
        val clock: MovableClock = context.getBean(java.time.Clock::class.java) as MovableClock
        val party = unique("CTP-SND")
        private val mvc =
            MockMvcBuilders
                .webAppContextSetup(context.sourceApplicationContext as WebApplicationContext)
                .also { it.addFilters<DefaultMockMvcBuilder>(*filters()) }
                .build()

        private fun filters(): Array<Filter> =
            listOf("endUserPrincipalBindingFilter", "entityRefScopeFilter")
                .map { context.getBean(it, FilterRegistrationBean::class.java) }
                .sortedBy { it.order }
                .map { it.filter as Filter }
                .toTypedArray()

        fun body(
            entity: String? = "E-001",
            email: String? = "ap@example.com.sg",
        ): String {
            val members =
                listOfNotNull(
                    entity?.let { "\"entityRef\":\"$it\"" },
                    email?.let { "\"contactEmail\":\"$it\"" },
                )
            return "{" + members.joinToString(",") + "}"
        }

        fun send(
            body: String?,
            token: String? = "T-OP",
            party: String = this.party,
        ): Pair<Int, String> {
            val request =
                MockMvcRequestBuilders
                    .post("/internal/v1/masterdata/counterparties/$party/requests")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
            if (token != null) request.header("Authorization", "Bearer $token")
            if (body != null) request.content(body)
            val response = mvc.perform(request).andReturn().response
            return response.status to response.contentAsString
        }

        fun requestId(answer: String): String = JsonMapper().readTree(answer)["requestId"].asString()

        fun linkEvents(requestId: String) =
            captured.envelopes.filter { it.type.endsWith("SignedLinkRequested.v1") && requestId in it.dataJson }

        fun auditEvents(requestId: String) =
            captured.envelopes.filter { it.type.contains("AuditEntryRecorded") && requestId in it.dataJson }

        fun rows(party: String = this.party): Int =
            count("select count(*) from masterdata.counterparty_request where party_ref = '$party'")

        fun state(): Pair<Int, Int> = rows() to captured.envelopes.size

        fun count(sql: String): Int =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }

        /** Records a link of [requestId] and invalidates it at [at], as access-svc's events would. */
        fun invalidateLink(
            requestId: String,
            expiry: Instant,
            at: Instant,
        ) {
            val tenants = context.getBean(TenantContext::class.java)
            val tx = context.getBean(TransactionRunner::class.java)
            val bindings = context.getBean("linkBindings", JooqLinkBindings::class.java)
            val linkRef =
                "LNK-" +
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(LINK_REF_LENGTH)
            tenants.bind(SEND_TENANT, "IDN-OPERATOR") {
                tx.inTransaction {
                    (bindings as LinkBindings).save(
                        LinkBinding(linkRef, SEND_TENANT.toString(), requestId, expiry, at),
                    )
                }
            }
        }
    }

    @Test
    fun `INV-AC-20201 a send answers 201 SENT, stores the request with the entity and writes one event`() =
        run { env ->
            val (status, answer) = env.send(env.body())
            assertThat(status).describedAs(answer).isEqualTo(201)
            val json = JsonMapper().readTree(answer)
            assertThat(json["status"].asString()).isEqualTo("SENT")
            assertThat(json["counterpartyRef"].asString()).isEqualTo(env.party)
            val id = env.requestId(answer)
            assertThat(env.rows()).isEqualTo(1)
            assertThat(
                env.count(
                    "select count(*) from masterdata.counterparty_request " +
                        "where request_id = '$id' and entity = 'E-001'",
                ),
            ).isEqualTo(1)
            val event = env.linkEvents(id).single()
            assertThat(event.entity.value).isEqualTo("E-001")
        }

    @Test
    fun `INV-AC-20201 a call without body, contactEmail or entityRef answers 400, nothing written`() =
        run { env ->
            val before = env.state()
            val bodies = listOf(null, "{}", env.body(email = null), env.body(entity = null), env.body(email = ""))
            bodies.forEach { body ->
                val (status, answer) = env.send(body)
                assertThat(status).describedAs("$body: $answer").isEqualTo(400)
                assertThat(answer).describedAs("$body").contains("SRT-001")
                assertThat(env.state()).describedAs("$body").isEqualTo(before)
            }
        }

    @Test
    fun `INV-AC-20201 a blank entityRef never reaches the handler, the runtime gate refuses it 403 SRT-004`() =
        run { env ->
            val before = env.state()
            listOf(" ", "").forEach { entity ->
                val (status, answer) = env.send(env.body(entity = entity))
                assertThat(status).describedAs("'$entity': $answer").isEqualTo(403)
                assertThat(answer).contains("SRT-004")
                assertThat(env.state()).isEqualTo(before)
            }
        }

    @Test
    fun `INV-AC-20201 an entity outside the principal's set is 403 SRT-004 with nothing written`() =
        run { env ->
            val before = env.state()
            listOf("E-002", "e-001", "E-0010").forEach { entity ->
                val (status, answer) = env.send(env.body(entity = entity))
                assertThat(status).describedAs("$entity: $answer").isEqualTo(403)
                assertThat(answer).contains("SRT-004")
                assertThat(env.state()).describedAs(entity).isEqualTo(before)
            }
        }

    @Test
    fun `INV-AC-20201 a caller without the permission key gets 403 and nothing is written`() =
        run { env ->
            val before = env.state()
            val (status, answer) = env.send(env.body(), token = "T-NOKEY")
            assertThat(status).describedAs(answer).isEqualTo(403)
            assertThat(env.state()).isEqualTo(before)
            assertThat(env.send(env.body(), token = null).first).isEqualTo(401)
            assertThat(env.state()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-20201 VP-30 CSV_ONLY or OFF answer 409 MDM-011, SIGNED_LINK or BOTH send, unknown is refused`() =
        run { env ->
            val before = env.state()
            listOf("CSV_ONLY", "OFF").forEach { option ->
                SendOption.override = option
                val (status, answer) = env.send(env.body())
                assertThat(status).describedAs("$option: $answer").isEqualTo(409)
                assertThat(answer).contains("MDM-011")
                assertThat(env.state()).describedAs(option).isEqualTo(before)
            }
            listOf("signed_link", "").forEach { option ->
                SendOption.override = option
                val (status, answer) = env.send(env.body())
                assertThat(status).describedAs("unknown '$option': $answer").isGreaterThanOrEqualTo(400)
                assertThat(answer).doesNotContain("MDM-011")
                assertThat(env.state()).describedAs(option).isEqualTo(before)
            }
            listOf("BOTH", "SIGNED_LINK").forEach { option ->
                SendOption.override = option
                val party = unique("CTP-OPT")
                assertThat(env.send(env.body(), party = party).first).describedAs(option).isEqualTo(201)
                assertThat(env.rows(party)).describedAs(option).isEqualTo(1)
            }
        }

    @Test
    fun `INV-AC-20201 a second send while the link is live returns the same request and adds no event`() =
        run { env ->
            val first = env.send(env.body())
            val second = env.send(env.body())
            assertThat(first.first).isEqualTo(201)
            assertThat(second.first).describedAs(second.second).isEqualTo(201)
            assertThat(env.requestId(second.second)).isEqualTo(env.requestId(first.second))
            assertThat(env.linkEvents(env.requestId(first.second))).hasSize(1)
            assertThat(env.rows()).isEqualTo(1)
        }

    @Test
    fun `INV-AC-20201 a send after expiry adds one event for the same request, expiry is send plus validity`() =
        run { env ->
            val sentAt = env.clock.instant()
            val first = env.send(env.body())
            val id = env.requestId(first.second)
            val payload = JsonMapper().readTree(env.linkEvents(id).single().dataJson)
            val expiry = Instant.parse(payload["expiry"].asString())
            val validity = Duration.ofDays(7)
            assertThat(Duration.between(sentAt, expiry)).isBetween(validity, validity.plusSeconds(5))
            env.clock.advance(Duration.between(env.clock.instant(), expiry).plusSeconds(1))
            val again = env.send(env.body())
            assertThat(again.first).describedAs(again.second).isEqualTo(201)
            assertThat(env.requestId(again.second)).isEqualTo(id)
            assertThat(env.linkEvents(id)).hasSize(2)
            assertThat(env.rows()).isEqualTo(1)
        }

    @Test
    fun `INV-AC-20201 a send after the link was invalidated adds one event for the same request`() =
        run { env ->
            val first = env.send(env.body())
            val id = env.requestId(first.second)
            val now = env.clock.instant()
            env.invalidateLink(id, now.plusSeconds(3600), now.plusSeconds(1))
            env.clock.advance(Duration.ofSeconds(2))
            val again = env.send(env.body())
            assertThat(env.requestId(again.second)).isEqualTo(id)
            assertThat(env.linkEvents(id)).hasSize(2)
        }

    @Test
    fun `INV-AC-20201 the audit entry of a send names the acting user and the request and no role`() =
        run { env ->
            val first = env.send(env.body())
            val id = env.requestId(first.second)
            env.send(env.body())
            val entry = env.auditEvents(id).single()
            val json = JsonMapper().readTree(entry.dataJson)
            assertThat(json.toString()).contains("IDN-OPERATOR").contains(id)
            assertThat(json.toString().lowercase()).doesNotContain("tax.operator").doesNotContain("\"role\"")
        }
}
