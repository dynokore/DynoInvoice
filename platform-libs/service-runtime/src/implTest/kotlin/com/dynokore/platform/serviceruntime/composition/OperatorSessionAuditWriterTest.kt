package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.auditlog.LocalAuditWriter
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.identity.CurrentOperatorSession
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.OperatorSession
import com.dynokore.platform.serviceruntime.identity.OperatorSessionBindingFilter
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jose.PlainHeader
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID
import javax.sql.DataSource

/**
 * Coordinator ruling INV-Q-4521 (INV-REQ-862 b4, INV-REQ-861 b2, INV-REQ-1180): every audit entry recorded under an
 * operator session reads `case <case>`, or `case <case>: <own reason>` with its own reason kept in full; without a
 * session it is recorded unchanged. The binding is per request ([OperatorSessionBindingFilter]).
 */
class OperatorSessionAuditWriterTest {
    private val session = OperatorSession("platform.support", "CASE-5521")
    private val recorded = mutableListOf<AuditEntry>()
    private val own =
        object : AuditWriter {
            override fun record(
                entry: AuditEntry,
                context: EventContext,
            ): Envelope {
                recorded += entry
                return mockk(relaxed = true)
            }
        }
    private val writer = OperatorSessionAuditWriter(own)
    private val entry = AuditEntry("op-1", "readInvoice", "INV-1")

    private fun recordedReason(entry: AuditEntry): String? {
        writer.record(entry, mockk(relaxed = true))
        return recorded.last().reason
    }

    @Test
    fun `under a session an entry without a reason reads case, and one with its own reason keeps it in full`() {
        CurrentOperatorSession.callWith(session) {
            recordedReason(entry) shouldBe "case CASE-5521"
            val override = "Customer confirmed the UEN by phone: ticket 77 (re-checked) "
            recordedReason(entry.copy(reason = override)) shouldBe "case CASE-5521: $override"
            recorded.last().copy(reason = null) shouldBe entry // nothing else changes
        }
    }

    @Test
    fun `a caller-supplied reason cannot spoof another case - the session's case is always the prefix`() {
        CurrentOperatorSession.callWith(session) {
            recordedReason(entry.copy(reason = "case CASE-9999")) shouldBe "case CASE-5521: case CASE-9999"
            recordedReason(entry.copy(reason = "case CASE-9999: x")) shouldBe "case CASE-5521: case CASE-9999: x"
            recordedReason(entry.copy(reason = "case CASE-5521")) shouldBe "case CASE-5521: case CASE-5521"
        }
        recorded.map { it.reason }.forEach { reason -> reason!!.startsWith("case CASE-5521: ") shouldBe true }
    }

    @Test
    fun `without a session the entry is recorded unchanged`() {
        recordedReason(entry) shouldBe null
        recordedReason(entry.copy(reason = "own")) shouldBe "own"
        CurrentOperatorSession.current() shouldBe null
    }

    @Test
    fun `the request filter binds only a verified operator session, for that request only`() {
        val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
        var resolved = 0
        var verdict: EndUserPrincipal? = EndUserPrincipal("op-1", tenant, emptySet(), operatorSession = session)
        val resolver =
            EndUserPrincipalResolver {
                resolved++
                verdict
            }
        val filter = OperatorSessionBindingFilter(providerOf(resolver))

        fun reasonFor(authorization: String?): String? {
            val request = MockHttpServletRequest().apply { authorization?.let { addHeader("Authorization", it) } }
            var reason: String? = null
            val chain = FilterChain { _, _ -> reason = recordedReason(entry) }
            filter.doFilter(request, MockHttpServletResponse(), chain)
            return reason
        }

        reasonFor("Bearer " + jwt(act = true)) shouldBe "case CASE-5521"
        resolved shouldBe 1
        reasonFor("Bearer " + jwt(act = false)) shouldBe null // no act: never resolved here
        reasonFor(null) shouldBe null
        reasonFor("Bearer not-a-jwt") shouldBe null
        resolved shouldBe 1
        verdict = null // the resolver refuses the act token
        reasonFor("Bearer " + jwt(act = true)) shouldBe null
        CurrentOperatorSession.current() shouldBe null
    }

    @Test
    fun `the audit wrapper never sees an unvalidated case - a service's own resolver cannot build one`() {
        val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
        val filter =
            OperatorSessionBindingFilter(
                providerOf {
                    EndUserPrincipal(
                        "op-1",
                        tenant,
                        emptySet(),
                        operatorSession = OperatorSession("platform.support", "A: B"),
                    )
                },
            )
        val request = MockHttpServletRequest().apply { addHeader("Authorization", "Bearer " + jwt(act = true)) }
        shouldThrow<IllegalArgumentException> {
            filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> recordedReason(entry) })
        }
        recorded shouldBe emptyList()
        CurrentOperatorSession.current() shouldBe null
    }

    @Test
    fun `wired by AuditLogWiring - a service's own and the platform-libs audit writer are both decorated`() {
        val base =
            ApplicationContextRunner()
                .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
                .withConfiguration(
                    AutoConfigurations.of(
                        ServiceRuntimeConfiguration::class.java,
                        L1CompositionAutoConfiguration::class.java,
                    ),
                ).withPropertyValues("service-runtime.workload-identity.audience=svc")
        base.withBean(AuditWriter::class.java, { own }).run { context ->
            val wired = context.getBean(AuditWriter::class.java)
            wired.shouldBeInstanceOf<OperatorSessionAuditWriter>().delegate shouldBe own
            CurrentOperatorSession.callWith(session) { wired.record(entry.copy(reason = "own"), mockk(relaxed = true)) }
            recorded.last().reason shouldBe "case CASE-5521: own"
        }
        base
            .withBean("dataSource", DataSource::class.java, { mockk<DataSource>(relaxed = true) })
            .withPropertyValues(
                "gst.service=control-plane-svc",
                "gst.instance-id=cp-0",
                "service-runtime.modules.schema=control_plane",
            ).run { context ->
                context
                    .getBean(
                        AuditWriter::class.java,
                    ).shouldBeInstanceOf<OperatorSessionAuditWriter>()
                    .delegate
                    .shouldBeInstanceOf<LocalAuditWriter>()
            }
    }

    private fun jwt(act: Boolean): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject("op-1")
                .apply {
                    if (act) {
                        claim(
                            "act",
                            mapOf(
                                "role" to "r",
                                "case" to "c",
                            ),
                        )
                    }
                }.build()
        return PlainJWT(PlainHeader(), claims).serialize()
    }

    private fun providerOf(resolver: EndUserPrincipalResolver): ObjectProvider<EndUserPrincipalResolver> =
        object : ObjectProvider<EndUserPrincipalResolver> {
            override fun getObject(vararg args: Any?): EndUserPrincipalResolver = resolver

            override fun getIfAvailable(): EndUserPrincipalResolver = resolver

            override fun getIfUnique(): EndUserPrincipalResolver = resolver

            override fun getObject(): EndUserPrincipalResolver = resolver
        }
}
