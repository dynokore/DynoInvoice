package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.OffboardingExportRequestedOutbox
import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.application.offboarding.TenantExport
import com.dynokore.gst.tenancy.application.offboarding.UnsubmittedDocuments
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.OpenTenantRequest
import com.dynokore.gst.tenancy.domain.ElectionRoles
import com.dynokore.gst.tenancy.domain.ExportContent
import com.dynokore.gst.tenancy.syncedge.ContractDocument
import com.dynokore.gst.tenancy.syncedge.EntityFactsIdentity
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val CALLER = "identity:USR-004"
private const val SECOND = "identity:USR-0040"
private const val HOLDER = """{"name":"Ledger Custody Pte Ltd","contactEmail":"custody@ledger.example"}"""

// Beans of the test context. A plain class, not @Configuration, so the component scan never picks it up elsewhere.
class AsyncExportFixture {
    @Bean
    @Primary
    fun exportResultLog(): EventLogReader = ASYNC_LOG

    /** The unsubmitted-count stub at 0. */
    @Bean
    @Primary
    fun zeroCount(): UnsubmittedDocuments = UnsubmittedDocuments { _, _ -> 0 }
}

private val ASYNC_LOG = TopicLog()

/**
 * INV-AC-22961 through the HTTP boundary: the application as in production (real PostgreSQL, the outbox publisher as
 * the export port, the real consumer of the result event); only the event-log read is a list in memory.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OffboardingExportAsyncHttpTest {
    private val http = HttpClient.newHttpClient()
    private lateinit var dir: Path
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var env: ProfileEnv

    private class Answer(
        val status: Int,
        val body: String,
    )

    @BeforeAll
    fun start() {
        dir = Files.createTempDirectory("inv-t-22967-")
        context =
            BoundaryFixture.startServlet(
                Files.createTempDirectory(dir, "ctx-"),
                listOf(EntityFactsIdentity::class.java, AsyncExportFixture::class.java),
                arrayOf(
                    "service-runtime.workload-identity.callers[0].service=console-bff",
                    "service-runtime.workload-identity.callers[0].subject=sa-console-bff",
                    "service-runtime.workload-identity.callers[1].service=control-plane-svc",
                    "service-runtime.workload-identity.callers[1].subject=sa-control-plane-svc",
                    "management.server.port=0",
                ),
                javaClass.classLoader,
            )
        env = ProfileEnv(context)
    }

    @AfterAll
    fun stop() {
        context.close()
        dir.toFile().deleteRecursively()
    }

    private val closeCaller: String by lazy {
        ContractDocument
            .operations(ContractDocument.text(javaClass.classLoader))
            .single { it.template.endsWith("/tenants/{tenantRef}/close") }
            .callerFields
            .single()
    }

    private fun post(
        path: String,
        body: String,
        workload: String = "console-bff",
        endUser: Boolean = true,
    ): Answer {
        val port = env.context.environment.getProperty("local.server.port")
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

    private fun scalar(
        sql: String,
        arg: Any,
    ): String? =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement(sql).use { s ->
                s.setObject(1, arg)
                s.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun tenantState(tenant: TenantId) =
        scalar("select state from tenancy.tenant where tenant_id = ?", tenant.value)

    private fun recordColumn(
        tenant: TenantId,
        column: String,
    ) = scalar(
        "select $column from tenancy.offboarding_record where tenant_id = ? order by created_at desc limit 1",
        tenant.value,
    )

    private fun records(tenant: TenantId) =
        scalar("select count(*) from tenancy.offboarding_record where tenant_id = ?", tenant.value)!!.toLong()

    /** Opens the fixture tenant (SCHEDULED) and calls closeTenant as control-plane-svc. */
    private fun closeTenant(tenant: TenantId): Answer =
        post(
            "/internal/v1/tenancy/tenants/${tenant.value}/close",
            """{"scheduleRef":"SCH-7A21C4","retentionCustodyHolder":$HOLDER}""",
            workload = closeCaller,
            endUser = false,
        )

    private fun openTenant(tenant: TenantId) {
        val ref = scalar("select ref from tenancy.tenant where tenant_id = ?", tenant.value)!!
        env.tenantsApi.openTenant(ref, UUID.randomUUID(), OpenTenantRequest("SCH-1"))
    }

    private fun entityOffboard(
        tenant: TenantId,
        entity: EntityResponse,
    ) = post(
        "/internal/v1/tenancy/tenants/${tenant.value}/offboarding",
        """{"scope":"ENTITY","entityRef":"${entity.entityRef}","retentionCustodyHolder":$HOLDER,""" +
            """"secondConfirmationBy":"$SECOND"}""",
    )

    /** Delivers the result envelope of the latest record of [tenant] through the real inbox consumer. */
    private fun deliver(
        tenant: TenantId,
        ready: Boolean,
    ) {
        val ref = recordColumn(tenant, "offboarding_ref")!!
        val tenantRef = scalar("select ref from tenancy.tenant where tenant_id = ?", tenant.value)!!
        val data =
            if (ready) {
                val contents = ExportContent.entries.joinToString(",") { "\"${it.name}\"" }
                """{"tenantRef":"$tenantRef","offboardingRef":"$ref","exportRef":"EXP-$ref","state":"READY",""" +
                    """"contents":[$contents]}"""
            } else {
                """{"tenantRef":"$tenantRef","offboardingRef":"$ref","exportRef":"EXP-$ref","state":"FAILED",""" +
                    """"failureReason":"no source for AUDIT_LOG"}"""
            }
        val envelope =
            Envelope(
                id = EventId("ev-${UUID.randomUUID()}"),
                source = "/gst/evidence-svc",
                type = TenancyEventCatalogue.OFFBOARDING_EXPORT_FINISHED_TYPE,
                subject = ref,
                time = Instant.now(),
                tenant = tenant,
                entity = EntityRef.TENANT_WIDE,
                docUuid = null,
                transactionDate = null,
                docSequence = null,
                instanceId = "evidence-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = data,
            )
        ASYNC_LOG.add(OffboardingExportFinishedConsumerConfiguration.TOPIC, EnvelopeJson().write(envelope))
        val consumer =
            context.getBean("offboardingExportFinishedConsumer", JdbcInboxConsumer::class.java)
        assertThat(consumer.pollOnce()).isEqualTo(PollResult(1, 0, 0, 0, 0))
    }

    private fun withEntity(block: (TenantId, EntityResponse) -> Unit) =
        env.withEntity { tenant, entity ->
            val people = env.context.getBean(TestPeople::class.java)
            people.caller = CALLER
            people.grant(CALLER, entity.entityRef, ElectionRoles.ENTITY_ADMIN)
            people.grant(SECOND, entity.entityRef, ElectionRoles.TAX_APPROVER)
            EntityFactsIdentity.principal = EndUserPrincipal("user-a", tenant, setOf(EntityRef(entity.entityRef)))
            block(tenant, entity)
        }

    @Test
    fun `INV-AC-22961 closeTenant answers 202 with one REQUESTED record and one outbox row, tenant stays OPEN`() {
        env.withTenant { tenant ->
            openTenant(tenant)
            val answer = closeTenant(tenant)
            assertThat(answer.status).describedAs(answer.body).isEqualTo(202)
            assertThat(records(tenant)).isEqualTo(1)
            assertThat(recordColumn(tenant, "state")).isEqualTo("REQUESTED")
            val ref = recordColumn(tenant, "offboarding_ref")!!
            assertThat(BoundaryFixture.outboxRowsMentioning(ref)).isEqualTo(1)
            assertThat(tenantState(tenant)).isEqualTo("OPEN")
        }
    }

    @Test
    fun `INV-AC-22961 the READY envelope makes the record COMPLETED and the tenant CLOSED`() {
        env.withTenant { tenant ->
            openTenant(tenant)
            assertThat(closeTenant(tenant).status).isEqualTo(202)
            assertThat(tenantState(tenant)).isEqualTo("OPEN")
            deliver(tenant, ready = true)
            assertThat(recordColumn(tenant, "state")).isEqualTo("COMPLETED")
            assertThat(tenantState(tenant)).isEqualTo("CLOSED")
        }
    }

    @Test
    fun `INV-AC-22961 after a FAILED envelope the tenant stays OPEN and a new closeTenant is accepted`() {
        env.withTenant { tenant ->
            openTenant(tenant)
            assertThat(closeTenant(tenant).status).isEqualTo(202)
            deliver(tenant, ready = false)
            assertThat(recordColumn(tenant, "state")).isEqualTo("FAILED")
            assertThat(tenantState(tenant)).isEqualTo("OPEN")
            val again = closeTenant(tenant)
            assertThat(again.status).describedAs(again.body).isEqualTo(202)
            assertThat(records(tenant)).isEqualTo(2)
            assertThat(recordColumn(tenant, "state")).isEqualTo("REQUESTED")
        }
    }

    @Test
    fun `INV-AC-22961 offboardTenant for E1 answers 202 and the entity is unchanged until a result`() {
        withEntity { tenant, entity ->
            val before = env.stored(entity)
            val answer = entityOffboard(tenant, entity)
            assertThat(answer.status).describedAs(answer.body).isEqualTo(202)
            assertThat(recordColumn(tenant, "state")).isEqualTo("REQUESTED")
            assertThat(env.stored(entity)).isEqualTo(before)
            deliver(tenant, ready = false)
            assertThat(recordColumn(tenant, "state")).isEqualTo("FAILED")
            assertThat(env.stored(entity)).isEqualTo(before)
        }
    }

    @Test
    fun `INV-AC-22961 with the property absent the limit is PT24H and the one TenantExport bean is the outbox`() {
        assertThat(context.environment.getProperty("tenancy.offboarding.export-timeout")).isEqualTo("PT24H")
        assertThat(context.getBean(OffboardingExportProperties::class.java).limit()).isEqualTo(Duration.ofHours(24))
        val exports = context.getBeansOfType(TenantExport::class.java)
        assertThat(exports).hasSize(1)
        assertThat(exports.values.single()).isInstanceOf(OffboardingExportRequestedOutbox::class.java)
    }

    @Test
    fun `INV-AC-22961 a property value of P0D fails the context start`() {
        fun failure(value: String): Throwable? {
            var failure: Throwable? = null
            BoundaryFixture
                .runner()
                .withBean(EventLogReader::class.java, { TopicLog() })
                .withPropertyValues("tenancy.offboarding.export-timeout=$value")
                .run { failure = it.startupFailure }
            return failure
        }
        listOf("P0D", "-PT1H", "not-a-duration").forEach { assertThat(failure(it)).describedAs(it).isNotNull() }
    }
}
