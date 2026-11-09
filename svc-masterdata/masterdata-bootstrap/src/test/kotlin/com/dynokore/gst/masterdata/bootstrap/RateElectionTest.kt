package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.masterdata.adapters.rate.ApprovedSource
import com.dynokore.gst.masterdata.adapters.rate.ScopePackStore
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.SetterPermission
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * INV-T-18775, the end-to-end election test (INV-REQ-506): `importRates` through the real HTTP controller, the real
 * use case, the real `PolicyReader` and the real elected-source adapter. VP-24 is written through the real `setPolicy`
 * servlet and `PolicyWriter` (the precedent is `RegistrySettingsTest`). The only fixture is the check of the owning
 * service (tenancy-svc, another service): a `ValueConstraints` bean that accepts a permitted election, so the writer
 * does not refuse with POL-014. No `PolicyReader` is wrapped or stubbed.
 */
class RateElectionTest {
    private val tenant = AtomicReference<TenantId>()
    private val entities = CopyOnWriteArrayList<EntityRef>()
    private val mapper = JsonMapper.builder().build()

    private val principals =
        EndUserPrincipalResolver { header ->
            val actor = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            actor?.takeIf { it.startsWith("IDN-") }?.let { EndUserPrincipal(it, tenant.get(), entities.toSet()) }
        }

    /** Who may set VP-24 (INV-Q-10027): decided by the permission key the service-runtime port asks. */
    private val setters =
        SetterPermission { actor, _, _, _, _, key -> actor.principal == ELECTOR && key == "vp-24.set" }

    /** Stands for tenancy-svc's check of an election: every election the test writes is a permitted one. */
    private val owningService = ValueConstraints { _, _, _, _ -> ConstraintVerdict.Accept }

    private val policySchema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(
            File(POLICY_SCHEMA).readText(),
        )

    private fun run(block: (RegistryEnv) -> Unit) =
        runRegistry(
            customize = {
                it
                    .withBean(EndUserPrincipalResolver::class.java, { principals })
                    .withBean(SetterPermission::class.java, { setters })
                    .withBean(ValueConstraints::class.java, { owningService })
            },
        ) { env ->
            tenant.set(env.ce.tenant)
            block(env)
        }

    private fun RegistryEnv.elect(
        entity: String,
        source: String,
    ): MockHttpServletResponse {
        entities += EntityRef(entity)
        val request = MockHttpServletRequest("POST", "/internal/v1/policies/VP-24")
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer $ELECTOR")
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        request.setContent(
            (
                """{"scopeLevel":"ENTITY","scopeRef":"$entity","entityRef":"$entity","selected":""" +
                    """[{"option":"APPROVED_SOURCE","value":{"source":"$source","basis":"DAILY"}}],""" +
                    """"effectiveDate":"${businessDate()}","reason":"INV-T-18775 election test"}"""
            ).toByteArray(),
        )
        val response = MockHttpServletResponse()
        val servlet =
            context
                .getBeansOfType(ServletRegistrationBean::class.java)
                .values
                .single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }
                .servlet as HttpServlet
        servlet.service(request, response)
        return response
    }

    private fun RegistryEnv.stored(entity: String): String? =
        ce.inTenant {
            context.getBean(TransactionRunner::class.java).inTransaction {
                context
                    .getBean(PolicyReader::class.java)
                    .resolve(VariationPointKey("VP-24"), PolicyScope.entity(ce.tenant, entity), businessDate())
                    .selected
                    .single()
                    .valueJson
            }
        }

    private fun RegistryEnv.approve(vararg sources: String) =
        ce.inTenant {
            val packs = context.getBean(ScopePackStore::class.java)
            context.getBean(TransactionRunner::class.java).inTransaction {
                val now = Instant.parse("2026-01-01T00:00:00Z")
                packs.stage("sg-scope", "1.0.0", sources.map { ApprovedSource(it, setOf("DAILY")) }, now)
                packs.activate("sg-scope", "1.0.0", businessDate(), now)
            }
        }

    private fun RegistryEnv.importRates(
        entity: String,
        source: String,
    ): Pair<Int, String?> =
        ce.inTenant(OPERATOR) {
            val day = businessDate().plusDays(1)
            val response =
                MockMvcBuilders
                    .webAppContextSetup(context.sourceApplicationContext as WebApplicationContext)
                    .build()
                    .perform(
                        MockMvcRequestBuilders
                            .post("/internal/v1/masterdata/exchange-rates/import")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """{"source":"$source","entityRef":"$entity",""" +
                                    """"dateRange":{"from":"${businessDate()}",""" +
                                    """"to":"${businessDate().plusDays(30)}"},""" +
                                    """"currencyPairs":["USD/SGD"],"rowCount":1,""" +
                                    """"rows":[{"currency":"USD","date":"$day","rate":1.35}]}""",
                            ),
                    ).andReturn()
                    .response
            response.status to mapper.readTree(response.contentAsString).get("code")?.asString()
        }

    @Test
    fun `INV-AC-8626 an election written through setPolicy is stored, announced and read back by the real reader`() =
        run { env ->
            val entity = env.entity()
            val set = env.elect(entity, "MAS")
            assertThat(set.status).describedAs(set.contentAsString).isBetween(200, 299)
            assertThat(mapper.readTree(env.stored(entity)).get("source").asString()).isEqualTo("MAS")
            val changed =
                env.ce.captured.envelopes
                    .filter { it.type.contains(".PolicyChanged.") }
            assertThat(changed).isNotEmpty
            changed.forEach { assertThat(policySchema.validate(it.dataJson, InputFormat.JSON)).isEmpty() }
        }

    @Test
    fun `INV-AC-8626 an import naming the elected source loads and one naming another source is MDM-012`() =
        run { env ->
            val entity = env.entity()
            env.approve("MAS", "MAS2")
            assertThat(env.elect(entity, "MAS").status).isBetween(200, 299)
            assertThat(env.importRates(entity, "MAS").first).isEqualTo(201)
            val other = env.importRates(entity, "MAS2")
            assertThat(other.first).isEqualTo(422)
            assertThat(other.second).isEqualTo(ErrorCode.MDM_012.code)
            val sql =
                "select count(*) from masterdata.exchange_rate where tenant_id = '{tenant}'::uuid and source = 'MAS2'"
            assertThat(env.count(sql)).isZero()
        }

    @Test
    fun `INV-AC-8626 an election written for entity E1 is not read for E2`() =
        run { env ->
            val e1 = env.entity()
            val e2 = env.entity()
            env.approve("MAS", "MAS2")
            assertThat(env.elect(e1, "MAS").status).isBetween(200, 299)
            assertThat(env.elect(e2, "MAS2").status).isBetween(200, 299)
            val wrong = env.importRates(e2, "MAS")
            assertThat(wrong.first).isEqualTo(422)
            assertThat(wrong.second).isEqualTo(ErrorCode.MDM_012.code)
            assertThat(env.importRates(e1, "MAS").first).isEqualTo(201)
            assertThat(env.importRates(e2, "MAS2").first).isEqualTo(201)
        }

    private companion object {
        const val ELECTOR = "IDN-ELECTOR"

        /** The pinned bundle's schema, unpacked by masterdata-contract at configuration time. */
        const val POLICY_SCHEMA =
            "../masterdata-contract/build/contracts-repo/.bundle/service-runtime/schemas/" +
                "policy-changed-payload.schema.json"
    }
}
