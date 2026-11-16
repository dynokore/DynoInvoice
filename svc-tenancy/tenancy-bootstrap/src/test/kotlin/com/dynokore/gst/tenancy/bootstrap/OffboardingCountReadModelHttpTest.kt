package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.adapters.events.UnsubmittedCountMeasuredHandler
import com.dynokore.gst.tenancy.adapters.offboarding.JooqTenantEntityCheck
import com.dynokore.gst.tenancy.adapters.offboarding.JooqUnsubmittedCountReplica
import com.dynokore.gst.tenancy.adapters.offboarding.ReplicaUnsubmittedDocuments
import com.dynokore.gst.tenancy.application.lifecycle.TriggerCoverage
import com.dynokore.gst.tenancy.application.offboarding.ExportOrder
import com.dynokore.gst.tenancy.application.offboarding.TenantExport
import com.dynokore.gst.tenancy.application.offboarding.UnsubmittedDocuments
import com.dynokore.gst.tenancy.contract.model.CreateGroupRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.OpenTenantRequest
import com.dynokore.gst.tenancy.domain.ElectionRoles
import com.dynokore.gst.tenancy.domain.ExportContent
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.syncedge.ContractDocument
import com.dynokore.gst.tenancy.syncedge.EntityFactsIdentity
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val CALLER = "identity:USR-004"
private const val SECOND = "identity:USR-0040"
private const val HOLDER = """{"name":"Ledger Custody Pte Ltd","contactEmail":"custody@ledger.example"}"""
private const val INPUT = "unsubmitted-documents-count"
private val MAX_AGE: Duration = Duration.ofMinutes(15)

// Beans of the test contexts. They are plain classes, not @Configuration, so the component scan of the application
// (which covers this package) never picks them up for another test.
private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

class CountFixedClock {
    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    @Bean
    @Primary
    fun readyExport(): TenantExport =
        TenantExport { order: ExportOrder ->
            ExportManifest("EXP-${order.offboardingRef}", ExportState.READY, ExportContent.entries)
        }
}

/** The adapter of production over an entity check that fails on demand: the only fake of the file. */
class CountFailingEntityRead {
    @Bean
    @Primary
    fun failingEntities(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): UnsubmittedDocuments {
        val replica = JooqUnsubmittedCountReplica(dsl)
        val real = JooqTenantEntityCheck(dsl, tenants)
        return ReplicaUnsubmittedDocuments(
            { replica.read(tenants.current()) },
            { if (FAIL.get()) error("entity rows unreadable") else real.hasAnyEntity() },
            clock,
            MAX_AGE,
        )
    }

    companion object {
        val FAIL = AtomicBoolean(false)
    }
}

/**
 * INV-AC-22872 through the HTTP boundary and the controller: the application with the replica stored through the real
 * handler of UnsubmittedDocumentCountMeasured, the real [ReplicaUnsubmittedDocuments] bound by
 * [OffboardingCountConfiguration] and a fixed clock; the status and code of each call and the stored offboarding
 * records are compared by equality.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OffboardingCountReadModelHttpTest {
    private val http = HttpClient.newHttpClient()
    private lateinit var dir: Path
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var env: ProfileEnv

    private class Answer(
        val status: Int,
        val body: String,
    ) {
        val code: String? get() = Regex(""""code"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
    }

    @BeforeAll
    fun start() {
        dir = Files.createTempDirectory("inv-t-23032-")
        context = serve(CountFixedClock::class.java)
        env = ProfileEnv(context)
    }

    @AfterAll
    fun stop() {
        context.close()
        dir.toFile().deleteRecursively()
    }

    private fun serve(vararg extra: Class<*>): ConfigurableApplicationContext =
        BoundaryFixture.startServlet(
            Files.createTempDirectory(dir, "ctx-"),
            listOf(EntityFactsIdentity::class.java, *extra),
            arrayOf(
                "service-runtime.workload-identity.callers[0].service=console-bff",
                "service-runtime.workload-identity.callers[0].subject=sa-console-bff",
                "service-runtime.workload-identity.callers[1].service=control-plane-svc",
                "service-runtime.workload-identity.callers[1].subject=sa-control-plane-svc",
                "management.server.port=0",
            ),
            javaClass.classLoader,
        )

    private val closeCaller: String by lazy {
        ContractDocument
            .operations(ContractDocument.text(javaClass.classLoader))
            .single { it.template.endsWith("/tenants/{tenantRef}/close") }
            .callerFields
            .single()
    }

    /** Stores a measurement through the real handler; the receipt time is the handler clock, [ago] before [NOW]. */
    private fun measured(
        of: ProfileEnv,
        tenant: TenantId,
        ago: Duration,
        items: Map<String, Int>,
    ) {
        val received = NOW.minus(ago)
        val handler =
            UnsubmittedCountMeasuredHandler(
                JooqUnsubmittedCountReplica(of.context.getBean(DSLContext::class.java)),
                of.context.getBean(ObjectMapper::class.java),
                Clock.fixed(received, ZoneOffset.UTC),
            )
        val entities =
            items.entries.joinToString(",") { """{"entityRef":"${it.key}","unsubmittedDocumentCount":${it.value}}""" }
        val envelope = countEnvelope(tenant, received, "[$entities]", items.values.sum())
        of.tenants.bind(tenant, "user:test") {
            of.context.getBean(TransactionRunner::class.java).inTransaction { handler.handle(envelope) }
        }
    }

    private fun post(
        of: ProfileEnv,
        path: String,
        body: String,
        workload: String = "console-bff",
        endUser: Boolean = true,
    ): Answer {
        val port = of.context.environment.getProperty("local.server.port")
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-Flow-Context", FIXED_FLOW_CONTEXT)
                .header("X-Workload-Token", "token-$workload")
                .apply { if (endUser) header("Authorization", "Bearer ${EntityFactsIdentity.TOKEN}") }
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString()).let { Answer(it.statusCode(), it.body()) }
    }

    private fun entityOffboard(
        of: ProfileEnv,
        tenant: TenantId,
        entity: EntityResponse,
    ) = post(
        of,
        "/internal/v1/tenancy/tenants/${tenant.value}/offboarding",
        """{"scope":"ENTITY","entityRef":"${entity.entityRef}","retentionCustodyHolder":$HOLDER,""" +
            """"secondConfirmationBy":"$SECOND"}""",
    )

    private fun tenantClose(
        of: ProfileEnv,
        tenant: TenantId,
    ): Answer {
        val ref =
            BoundaryFixture.asSuperuser { c ->
                c.prepareStatement("select ref from tenancy.tenant where tenant_id = ?").use { s ->
                    s.setObject(1, tenant.value)
                    s.executeQuery().use {
                        it.next()
                        it.getString(1)
                    }
                }
            }
        of.tenantsApi.openTenant(ref, UUID.randomUUID(), OpenTenantRequest("SCH-1"))
        return post(
            of,
            "/internal/v1/tenancy/tenants/${tenant.value}/close",
            """{"scheduleRef":"SCH-7A21C4","retentionCustodyHolder":$HOLDER}""",
            workload = closeCaller,
            endUser = false,
        )
    }

    private fun records(tenant: TenantId): Long =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("select count(*) from tenancy.offboarding_record where tenant_id = ?").use { s ->
                s.setObject(1, tenant.value)
                s.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    /** A tenant with one entity; the caller holds entity.admin and a second person tax.approver on it. */
    private fun withEntity(
        of: ProfileEnv = env,
        block: (TenantId, EntityResponse) -> Unit,
    ) = of.withEntity { tenant, entity ->
        val people = of.context.getBean(TestPeople::class.java)
        people.caller = CALLER
        people.grant(CALLER, entity.entityRef, ElectionRoles.ENTITY_ADMIN)
        people.grant(SECOND, entity.entityRef, ElectionRoles.TAX_APPROVER)
        EntityFactsIdentity.principal = EndUserPrincipal("user-a", tenant, setOf(EntityRef(entity.entityRef)))
        block(tenant, entity)
    }

    private fun refusedUnavailable(
        answer: Answer,
        tenant: TenantId,
    ) {
        assertThat(answer.status).describedAs(answer.body).isEqualTo(422)
        assertThat(answer.code).isEqualTo("POL-014")
        assertThat(answer.body).contains(INPUT)
        assertThat(records(tenant)).isEqualTo(0)
    }

    @Test
    fun `INV-AC-22872 the production binding of the port is the replica adapter`() {
        assertThat(
            context.getBean(UnsubmittedDocuments::class.java),
        ).isInstanceOf(ReplicaUnsubmittedDocuments::class.java)
        assertThat(context.getBeansOfType(UnsubmittedDocuments::class.java)).hasSize(1)
    }

    @Test
    fun `INV-AC-22872 a current row with total 2 and no override is 409 TNC-008 and no record is stored`(): Unit =
        withEntity { tenant, entity ->
            measured(env, tenant, Duration.ofMinutes(1), mapOf(entity.entityRef to 2))
            val answer = entityOffboard(env, tenant, entity)
            assertThat(answer.status).describedAs(answer.body).isEqualTo(409)
            assertThat(answer.code).isEqualTo("TNC-008")
            assertThat(records(tenant)).isEqualTo(0)
        }

    @Test
    fun `INV-AC-22872 a current row with total 0 is 202`(): Unit =
        withEntity { tenant, entity ->
            measured(env, tenant, Duration.ofMinutes(1), emptyMap())
            val answer = entityOffboard(env, tenant, entity)
            assertThat(answer.status).describedAs(answer.body).isEqualTo(202)
            assertThat(records(tenant)).isEqualTo(1)
        }

    @Test
    fun `INV-AC-22872 a row exactly 15 minutes old is current and one 15 minutes 1 second old is 422 POL-014`() {
        withEntity { tenant, entity ->
            measured(env, tenant, MAX_AGE, emptyMap())
            assertThat(entityOffboard(env, tenant, entity).status).isEqualTo(202)
        }
        withEntity { tenant, entity ->
            measured(env, tenant, MAX_AGE.plusSeconds(1), emptyMap())
            refusedUnavailable(entityOffboard(env, tenant, entity), tenant)
        }
    }

    @Test
    fun `INV-AC-22872 a row older than the maximum age is 422 POL-014 and no record is stored`(): Unit =
        withEntity { tenant, entity ->
            measured(env, tenant, Duration.ofHours(2), emptyMap())
            refusedUnavailable(entityOffboard(env, tenant, entity), tenant)
        }

    @Test
    fun `INV-AC-22872 a row received after the clock instant is 422 POL-014 naming the input`(): Unit =
        withEntity { tenant, entity ->
            measured(env, tenant, Duration.ofSeconds(-1), emptyMap())
            refusedUnavailable(entityOffboard(env, tenant, entity), tenant)
        }

    @Test
    fun `INV-AC-22872 no row is 422 POL-014 for a tenant that has an entity row`(): Unit =
        withEntity { tenant, entity ->
            refusedUnavailable(entityOffboard(env, tenant, entity), tenant)
            refusedUnavailable(tenantClose(env, tenant), tenant)
        }

    @Test
    fun `INV-AC-22872 a tenant with no entity row of any status and no count row is 202 for offboardTenant`(): Unit =
        env.withTenant { tenant ->
            val answer = tenantClose(env, tenant)
            assertThat(answer.status).describedAs(answer.body).isEqualTo(202)
            assertThat(records(tenant)).isEqualTo(1)
        }

    private fun lab(
        vararg extra: Class<*>,
        block: (Lab) -> Unit,
    ) {
        val log = TopicLog()
        val ports = Ports()
        val clock = TickingClock(Instant.now())
        BoundaryFixture
            .runner()
            .withUserConfiguration(*extra)
            .withBean(Clock::class.java, { clock }, primaryBean())
            .withBean(EventLogReader::class.java, { log })
            .withBean(TriggerCoverage::class.java, { TriggerCoverage { ports.uncovered } }, primaryBean())
            .withBean(TenantExport::class.java, {
                TenantExport { order: ExportOrder ->
                    ExportManifest("EXP-${order.offboardingRef}", ExportState.READY, ExportContent.entries)
                }
            }, primaryBean())
            .run { context ->
                context.startupFailure?.let { throw it }
                val e = ProfileEnv(context)
                block(Lab(e, CertificationEnv(e, log), ElectionEnv(e), ports, clock))
            }
    }

    @Test
    fun `INV-AC-22872 a current row where E1 has 3 refuses deregisterEntity of E1 and accepts E2 with 0`(): Unit =
        lab { l ->
            l.env.withGroup(2, CreateGroupRequest.GroupKind.DIVISIONAL) { tenant, _, entities ->
                val (e1, e2) = entities
                entities.forEach {
                    lifecycleReady(l, it)
                    lifecycleRegistered(l, it)
                }
                storeCount(l, tenant, """[{"entityRef":"${e1.entityRef}","unsubmittedDocumentCount":3}]""", 3)
                l.people.caller = ADMIN
                val refused = callDeregister(l.env, e1)
                assertThat(listOf(refused.status, refused.code)).isEqualTo(listOf(409, "TNC-008"))
                assertThat(storedState(e1)).isNotEqualTo("DEREGISTERED")
                assertThat(callDeregister(l.env, e2).status).isEqualTo(200)
                assertThat(storedState(e2)).isEqualTo("DEREGISTERED")
            }
        }

    @Test
    fun `INV-AC-22872 an entity read that throws is 422 POL-014 naming the input and nothing is deregistered`(): Unit =
        lab(CountFailingEntityRead::class.java) { l ->
            l.env.withGroup(1, CreateGroupRequest.GroupKind.DIVISIONAL) { tenant, _, entities ->
                val e1 = entities.single()
                lifecycleReady(l, e1)
                lifecycleRegistered(l, e1)
                storeCount(l, tenant, "[]", 0)
                CountFailingEntityRead.FAIL.set(true)
                try {
                    val refused = callDeregister(l.env, e1)
                    assertThat(listOf(refused.status, refused.code)).isEqualTo(listOf(422, "POL-014"))
                    assertThat(refused.detail).contains(INPUT)
                    assertThat(storedState(e1)).isNotEqualTo("DEREGISTERED")
                } finally {
                    CountFailingEntityRead.FAIL.set(false)
                }
                assertThat(callDeregister(l.env, e1).status).isEqualTo(200)
            }
        }

    @Test
    fun `INV-AC-22872 a missing, an unparsable and a non-positive maximum age each fail the start`() {
        fun failure(value: String?): Throwable? {
            var failure: Throwable? = null
            BoundaryFixture
                .runner()
                .withBean(EventLogReader::class.java, { TopicLog() })
                .withPropertyValues(
                    *listOfNotNull(value?.let { "inv.offboarding.unsubmitted-count.max-age=$it" }).toTypedArray(),
                ).run { failure = it.startupFailure }
            return failure
        }
        listOf("", "not-a-duration", "PT0S", "-PT1M", "0").forEach { value ->
            assertThat(failure(value)).describedAs("max-age='$value'").isNotNull()
        }
        assertThat(failure(null)).describedAs("the installation value PT15M").isNull()
    }

    private fun storeCount(
        l: Lab,
        tenant: TenantId,
        entities: String,
        total: Int,
    ) {
        val at = l.clock.instant().minusSeconds(60)
        val handler =
            UnsubmittedCountMeasuredHandler(
                JooqUnsubmittedCountReplica(l.env.context.getBean(DSLContext::class.java)),
                l.env.context.getBean(ObjectMapper::class.java),
                Clock.fixed(at, ZoneOffset.UTC),
            )
        val envelope = countEnvelope(tenant, at, entities, total)
        l.env.tenants.bind(tenant, "user:test") {
            l.env.context
                .getBean(TransactionRunner::class.java)
                .inTransaction { handler.handle(envelope) }
        }
    }

    private fun countEnvelope(
        tenant: TenantId,
        at: Instant,
        entities: String,
        total: Int,
    ) = Envelope(
        id = EventId("ev-${UUID.randomUUID()}"),
        source = "/gst/submission-svc",
        type = TenancyEventCatalogue.UNSUBMITTED_COUNT_MEASURED_TYPE,
        subject = "count-1",
        time = at,
        tenant = tenant,
        entity = EntityRef.TENANT_WIDE,
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "submission-svc-0",
        correlation = CorrelationId("corr-1"),
        causation = CausationId("cause-1"),
        dataJson = """{"measuredAt":"$at","unsubmittedDocumentCount":$total,"entities":$entities}""",
    )
}
