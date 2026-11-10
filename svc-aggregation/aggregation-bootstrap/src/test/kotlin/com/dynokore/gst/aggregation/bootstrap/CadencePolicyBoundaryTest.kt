package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.http.IntakeBodyCachingFilter
import com.dynokore.gst.aggregation.adapters.http.IntakeController
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
import com.dynokore.gst.aggregation.adapters.policy.PolicyCadenceSource
import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.SetterPermission
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.logging.CorrelationId
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServlet
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/**
 * VP-55 while it is planned (INV-REQ-1316, INV-REQ-1251): the platform refuses to set a weekly cadence, the entity
 * resolves to DEFAULT_CADENCE and the windows never span the period end. The setter is the holder of the scoped
 * permission `vp-55.set`, which the platform decides; the test's SetterPermission bean stands for that decision,
 * records every key it is asked and the service checks no role.
 */
class CadencePolicyBoundaryTest {
    private val askedKeys = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val actor = "user:integration-engineer"
    private val tenant = TenantId(UUID.randomUUID())
    private val principal = EndUserPrincipal(actor, tenant, setOf(EntityRef("E-001")))

    private fun run(
        dir: Path,
        block: (ApplicationContext) -> Unit,
    ) {
        AggregationContext
            .online(dir)
            .withBean(
                EndUserPrincipalResolver::class.java,
                { EndUserPrincipalResolver { header -> if (header == "Bearer token") principal else null } },
            ).withBean(
                SetterPermission::class.java,
                { SetterPermission { _, _, _, _, _, key -> askedKeys.add(key).let { true } } },
            ).run { context ->
                context.startupFailure?.let { throw it }
                block(context)
            }
    }

    private fun setPolicy(
        context: ApplicationContext,
        option: String,
    ): MockHttpServletResponse {
        val servlet =
            context
                .getBeansOfType(ServletRegistrationBean::class.java)
                .values
                .single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }
                .servlet as HttpServlet
        val request = MockHttpServletRequest("POST", "/internal/v1/policies/VP-55")
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer token")
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        request.setContent(
            (
                """{"scopeLevel":"ENTITY","scopeRef":"E-001","entityRef":"E-001","selected":[{"option":"$option"}],""" +
                    """"effectiveDate":"${LocalDate.now()}","reason":"weekly"}"""
            ).toByteArray(),
        )
        return MockHttpServletResponse().also { servlet.service(request, it) }
    }

    private fun intake(
        context: ApplicationContext,
        periodEnd: String,
        sourceKey: String,
    ): Int {
        val mvc: MockMvc =
            MockMvcBuilders
                .standaloneSetup(IntakeController(context.getBean(IntakeAggregate::class.java)))
                .setControllerAdvice(NotAggregatableAdvice())
                .addFilters<StandaloneMockMvcBuilder>(IntakeBodyCachingFilter())
                .build()
        return context.getBean(TenantScope::class.java).bind(tenant, actor) {
            CorrelationContext.withCorrelation(CorrelationId("3fa85f64-5717-4562-b3fc-2c963f66afa6")) {
                mvc
                    .perform(
                        post("/internal/v1/aggregation/batches")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content(
                                """{"entityRef":"E-001","businessUnitRef":"BU-01","aggregateType":"POS",
                                |"periodStart":"$periodEnd","periodEnd":"$periodEnd","underlyingRef":"BATCH-1",
                                |"sourceKey":"$sourceKey","sourceRevision":1,
                                |"linesByInternalTaxCode":[{"internalTaxCode":"SR-STD","amount":"10.00"}]}
                                """.trimMargin(),
                            ),
                    ).andReturn()
                    .response.status
            }
        }
    }

    private fun windows(): List<Pair<LocalDate, LocalDate>> =
        AggregationDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "select window_start, window_end from aggregation.aggregate_window where tenant_id = ? order by 1",
                ).use { statement ->
                    statement.setObject(1, tenant.value)
                    statement.executeQuery().use { rows ->
                        generateSequence {
                            if (rows.next()) {
                                rows.getObject(1, LocalDate::class.java) to rows.getObject(2, LocalDate::class.java)
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }

    @Test
    fun `INV-AC-2304 weekly is refused with POL-006 and the daily windows split at the period end`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { context ->
            listOf("E-001" to StructureNodeKind.ENTITY, "BU-01" to StructureNodeKind.BUSINESS_UNIT).forEach {
                context.getBean(StructureReplica::class.java).accept(
                    OrganisationStructureChanged(
                        tenant,
                        it.first,
                        StructureChange.UPDATED,
                        StructureNode(it.second, null, false, it.first, code(it.first), emptyList(), 2),
                    ),
                )
            }
            listOf("POS_STI_WEEKLY", "PCP_WEEKLY").forEach { option ->
                val response = setPolicy(context, option)
                response.status shouldBe 422
                response.contentAsString.contains("POL-006") shouldBe true
            }
            askedKeys.all { it == "vp-55.set" } shouldBe true
            val scope = context.getBean(TenantScope::class.java)
            val reader = context.getBean(PolicyReader::class.java)
            val resolved =
                scope.bind(tenant, actor) {
                    scope.transactions.inTransaction {
                        reader.resolve(PolicyCadenceSource.VP_55, PolicyScope.entity(tenant, "E-001"), LocalDate.now())
                    }
                }
            resolved.selected.single().key shouldBe "DEFAULT_CADENCE"
            val cadences = context.getBean(CadenceSource::class.java)
            scope.bind(tenant, actor) {
                cadences.cadence("E-001", AggregateType.POS, LocalDate.of(2027, 3, 31)) shouldBe Cadence.DAILY
                cadences.cadence("E-001", AggregateType.PCP, LocalDate.of(2027, 3, 31)) shouldBe Cadence.MONTHLY
            }
            intake(context, "2027-03-31", "K-31") shouldBe 202
            intake(context, "2027-04-01", "K-01") shouldBe 202
            windows() shouldBe
                listOf(
                    LocalDate.of(2027, 3, 31) to LocalDate.of(2027, 3, 31),
                    LocalDate.of(2027, 4, 1) to LocalDate.of(2027, 4, 1),
                )
        }

    private fun code(ref: String) = if (ref == "E-001") "E" else "B"
}
