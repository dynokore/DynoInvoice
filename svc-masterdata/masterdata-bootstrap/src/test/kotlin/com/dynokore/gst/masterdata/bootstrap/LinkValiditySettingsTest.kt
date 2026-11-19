package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.masterdata.adapters.request.PolicyTenantLinkValidity
import com.dynokore.gst.masterdata.application.request.LinkValidityLimits
import com.dynokore.gst.masterdata.application.request.TenantLinkValidity
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetterPermission
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

private val TENANT_A = TenantId(UUID.randomUUID())
private val TENANT_B = TenantId(UUID.randomUUID())
private val SETTING_KEY = VariationPointKey("VP-83")

/** The instant every clock read of the context gives, so the expiry of a send is compared by equality. */
object PinnedInstant {
    @Volatile var at: Instant? = null

    /** What the policy reader double of the bad-stored-value cases gives; `null` leaves the real reader. */
    @Volatile var stored: SelectedOption? = null
}

/** Pins the context clock and, on demand, puts a reader double under the real VP-83 adapter. */
class LinkValidityPostProcessor(
    private val context: () -> AssertableWebApplicationContext?,
) : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any =
        if (bean is Clock && beanName == "clock") {
            object : Clock() {
                override fun getZone() = ZoneOffset.UTC

                override fun withZone(zone: ZoneId?): Clock = this

                override fun instant(): Instant = PinnedInstant.at ?: bean.instant()
            }
        } else if (bean is TenantLinkValidity && beanName == "tenantLinkValidity") {
            TenantLinkValidity {
                val bad = PinnedInstant.stored
                if (bad == null) {
                    bean.days()
                } else {
                    val double =
                        java.lang.reflect.Proxy.newProxyInstance(
                            javaClass.classLoader,
                            arrayOf(PolicyReader::class.java),
                        ) { _, _, args ->
                            ResolvedSetting(
                                args[0].let { if (it is String) VariationPointKey(it) else it as VariationPointKey },
                                args[2] as java.time.LocalDate,
                                listOf(bad),
                                PolicySource.CatalogueDefault,
                            )
                        } as PolicyReader
                    val ctx = checkNotNull(context()).getBean(TenantContext::class.java)
                    PolicyTenantLinkValidity(double, ctx, Clock.systemUTC()).days()
                }
            }
        } else {
            bean
        }
}

/**
 * INV-T-24785 (INV-REQ-24775, VP-83): the tenant sets the counterparty request link validity through the real
 * `setPolicy` servlet of service-runtime over the pinned gst-catalogue; masterdata reads it with the real
 * PolicyReader on every send. Permissions are scoped keys decided per request (INV-Q-10027), never role names.
 */
class LinkValiditySettingsTest {
    private val principals =
        EndUserPrincipalResolver { header ->
            when (header) {
                "Bearer A-SETTER" -> EndUserPrincipal("IDN-SETTER", TENANT_A, setOf(EntityRef("E-001")))
                "Bearer A-NOSET" -> EndUserPrincipal("IDN-NOSET", TENANT_A, setOf(EntityRef("E-001")))
                "Bearer A-OP" -> EndUserPrincipal("IDN-OPERATOR", TENANT_A, setOf(EntityRef("E-001")))
                "Bearer B-OP" -> EndUserPrincipal(TestPermissions.OTHER_OPERATOR, TENANT_B, setOf(EntityRef("E-001")))
                else -> null
            }
        }

    private val setters =
        SetterPermission { actor, _, _, _, _, key -> actor.principal == "IDN-SETTER" && key == "vp-83.set" }

    private fun run(block: (Env) -> Unit) {
        var holder: AssertableWebApplicationContext? = null
        PinnedInstant.at = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        PinnedInstant.stored = null
        BoundaryFixture
            .runner()
            .withBean(EndUserPrincipalResolver::class.java, { principals })
            .withBean(SetterPermission::class.java, { setters })
            .withPropertyValues("service-runtime.idempotency.schema=masterdata")
            .withBean(LinkValidityPostProcessor::class.java, { LinkValidityPostProcessor { holder } })
            .run { context ->
                context.startupFailure?.let { throw it }
                holder = context
                try {
                    block(Env(context))
                } finally {
                    PinnedInstant.at = null
                    PinnedInstant.stored = null
                }
            }
    }

    private class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val captured: CapturedEvents = context.getBean(CapturedEvents::class.java)
        val now: Instant = checkNotNull(PinnedInstant.at)
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

        fun setPolicy(
            token: String,
            tenant: TenantId,
            value: String,
        ): MockHttpServletResponse {
            val request = MockHttpServletRequest("POST", "/internal/v1/policies/VP-83")
            request.contentType = "application/json"
            request.addHeader("Authorization", "Bearer $token")
            request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
            val date = now.atZone(ZoneId.of("Asia/Singapore")).toLocalDate()
            request.setContent(
                (
                    """{"scopeLevel":"TENANT","scopeRef":"$tenant","selected":[""" +
                        """{"option":"CUSTOM","value":$value}],"effectiveDate":"$date","reason":"INV-T-24785"}"""
                ).toByteArray(),
            )
            val response = MockHttpServletResponse()
            (
                context
                    .getBeansOfType(ServletRegistrationBean::class.java)
                    .values
                    .single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }
                    .servlet as HttpServlet
            ).service(request, response)
            return response
        }

        /** The option key and value JSON `PolicyReader.resolve` gives for VP-83 of [tenant] on the business date. */
        fun stored(tenant: TenantId): Pair<String, String?> =
            context.getBean(TenantContext::class.java).bind(tenant, "IDN-SETTER") {
                context.getBean(TransactionRunner::class.java).inTransaction {
                    val date = now.atZone(ZoneId.of("Asia/Singapore")).toLocalDate()
                    val option =
                        context
                            .getBean(PolicyReader::class.java)
                            .resolve(SETTING_KEY, PolicyScope.tenant(tenant), date)
                            .selected
                            .single()
                    option.key to option.valueJson
                }
            }

        fun send(
            token: String,
            party: String,
        ): Pair<Int, String> {
            val request =
                MockMvcRequestBuilders
                    .post("/internal/v1/masterdata/counterparties/$party/requests")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"entityRef":"E-001","contactEmail":"ap@example.com.sg"}""")
            val response = mvc.perform(request).andReturn().response
            return response.status to response.contentAsString
        }

        /** The expiry of the one SignedLinkRequested of the request answered in [answer]. */
        fun expiry(answer: String): Instant {
            val id = JsonMapper().readTree(answer)["requestId"].asString()
            val event = captured.envelopes.single { it.type.endsWith("SignedLinkRequested.v1") && id in it.dataJson }
            return Instant.parse(JsonMapper().readTree(event.dataJson)["expiry"].asString())
        }

        fun requestRows(): Int =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().executeQuery("select count(*) from masterdata.counterparty_request").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }

        fun state(): Pair<Int, Int> = requestRows() to captured.envelopes.size
    }

    @Test
    fun `INV-AC-24782 the tenant sets 1, 14 and 30 days and each send expires at the send instant plus them`() =
        run { env ->
            listOf(1L, 14L, 30L).forEach { days ->
                val set = env.setPolicy("A-SETTER", TENANT_A, "$days")
                assertThat(set.status).describedAs(set.contentAsString).isBetween(200, 299)
                assertThat(env.stored(TENANT_A)).describedAs("$days").isEqualTo("CUSTOM" to "$days")
                val (status, answer) = env.send("A-OP", unique("CTP-V"))
                assertThat(status).describedAs(answer).isEqualTo(201)
                assertThat(env.expiry(answer)).describedAs("$days days").isEqualTo(env.now.plus(Duration.ofDays(days)))
            }
        }

    @Test
    fun `INV-AC-24782 a second tenant that set nothing has 7 days`() =
        run { env ->
            assertThat(env.setPolicy("A-SETTER", TENANT_A, "30").status).isBetween(200, 299)
            val (status, answer) = env.send("B-OP", unique("CTP-B"))
            assertThat(status).describedAs(answer).isEqualTo(201)
            assertThat(env.expiry(answer)).isEqualTo(env.now.plus(Duration.ofDays(7)))
            assertThat(env.stored(TENANT_B).first).isEqualTo("DAYS_7")
        }

    @Test
    fun `INV-AC-24782 writes of 0, -1, 31 and 2_5 are refused by setPolicy and the stored value is unchanged`() =
        run { env ->
            assertThat(env.setPolicy("A-SETTER", TENANT_A, "14").status).isBetween(200, 299)
            val before = env.stored(TENANT_A)
            listOf("0", "-1", "31", "2.5").forEach { value ->
                val response = env.setPolicy("A-SETTER", TENANT_A, value)
                assertThat(response.status).describedAs("$value: ${response.contentAsString}").isEqualTo(422)
                assertThat(env.stored(TENANT_A)).describedAs(value).isEqualTo(before)
            }
        }

    @Test
    fun `INV-AC-24782 a principal without vp-83_set is refused and nothing is stored`() =
        run { env ->
            val before = env.stored(TENANT_A)
            val response = env.setPolicy("A-NOSET", TENANT_A, "14")
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(403)
            assertThat(env.stored(TENANT_A)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-24782 a bad stored value (no number, 31, 0, unknown option) refuses the send, nothing written`() =
        run { env ->
            val before = env.state()
            listOf(
                SelectedOption("CUSTOM", null),
                SelectedOption("CUSTOM", "31"),
                SelectedOption("CUSTOM", "0"),
                SelectedOption("DAYS_07", null),
            ).forEach { bad ->
                PinnedInstant.stored = bad
                // A value the use case refuses (0, 31) answers 503 SRT-013; a reader error (no number, unknown
                // option) is thrown through the request, so the send is refused either way and nothing is written.
                val outcome = runCatching { env.send("A-OP", unique("CTP-BAD")) }
                outcome.onSuccess { (status, answer) ->
                    assertThat(status).describedAs("$bad: $answer").isEqualTo(503)
                    assertThat(answer).contains("\"code\":\"SRT-013\"")
                }
                assertThat(env.state()).describedAs("$bad").isEqualTo(before)
            }
        }

    @Test
    fun `INV-AC-24782 the configured default and maximum equal the typed value of the catalogue row VP-83`() =
        run { env ->
            val specs = checkNotNull(VariationPoint.VP_83.optionSpecs)
            val custom = checkNotNull(specs.single { it.key == "CUSTOM" }.typedValue)
            val limits = env.context.getBean(LinkValidityLimits::class.java)
            assertThat(limits.defaultDays).isEqualTo(checkNotNull(custom.default?.number).longValueExact())
            assertThat(limits.maxDays).isEqualTo(checkNotNull(custom.max?.number).longValueExact())
            assertThat(limits.defaultDays).isEqualTo(7L)
            assertThat(limits.maxDays).isEqualTo(30L)
        }
}
