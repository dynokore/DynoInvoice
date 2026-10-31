package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionView
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.Lag
import com.dynokore.platform.serviceruntime.replica.Lookup
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

private val WIRE_TENANT = TenantId.of("0a000000-0000-0000-0000-00000000000a")

/** The machine token filter and the entity-ref filter as the auto-configuration builds them, in the TPL-18 `@Import` order (P109). */
class MachineTokenWiringTest {
    private val clock = Clock.systemUTC()
    private val structure = StructureReplica(clock) { _, _ -> FallbackResult.NotFound }

    private fun node(kind: StructureNodeKind) = StructureNode(kind, null, false, "n", null, emptyList(), 1)

    private fun runner(dir: Path): ApplicationContextRunner {
        fun write(
            name: String,
            text: String,
        ) = Files.createDirectories(dir.resolve("META-INF/gst")).resolve(name).let { Files.writeString(it, text) }
        write("machine-principal-operations.tsv", "POST\t/v1/ingest/a\tingest\t-\n")
        write("entity-ref-operations.tsv", "POST\t/v1/ingest/a\tentityRef\n")
        return ApplicationContextRunner()
            .withClassLoader(URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader))
            .withBean(Clock::class.java, { clock })
            .withBean(StructureReplica::class.java, { structure })
            .withBean(AuthorityReplica::class.java, { AuthorityReplica(clock) })
            .withBean(BreakGlassSessionView::class.java, { BreakGlassSessionView { _, _, _ -> null } })
            .withBean(TenantContextImpl::class.java, { TenantContextImpl() })
            .withConfiguration(AutoConfigurations.of(EndUserIdentityAutoConfiguration::class.java))
    }

    private fun post(
        filter: jakarta.servlet.Filter,
        machine: Principal?,
    ): Int {
        val request = MockHttpServletRequest("POST", "/v1/ingest/a")
        request.setContent("""{"entityRef":"E1"}""".toByteArray())
        machine?.let { request.setAttribute(MachineTokenFilter.PRINCIPAL_ATTRIBUTE, it) }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, FilterChain { _, res -> (res as MockHttpServletResponse).status = 200 })
        return response.status
    }

    @Suppress("UNCHECKED_CAST")
    private fun filterOf(
        context: org.springframework.context.ApplicationContext,
        bean: String,
    ) = context.getBean(bean, FilterRegistrationBean::class.java) as FilterRegistrationBean<jakarta.servlet.Filter>

    @Test
    fun `the machine token filter is registered at -65 with the bound issuer and audience and refuses a missing token`(
        @TempDir dir: Path,
    ) {
        runner(dir)
            .withPropertyValues(
                "service-runtime.machine-identity.issuer=https://access.example/",
                "service-runtime.machine-identity.audience=gst-public-api",
            ).run { context ->
                context.startupFailure shouldBe null
                context.getBean(MachineIdentityProperties::class.java) shouldBe
                    MachineIdentityProperties("https://access.example/", "gst-public-api")
                val registration = filterOf(context, "machineTokenFilter")
                registration.order shouldBe -65
                post(registration.filter!!, null) shouldBe 401
            }
    }

    @Test
    fun `the entity-ref filter built by the auto-configuration reads the StructureReplica bean for tenant coverage`(
        @TempDir dir: Path,
    ) {
        structure.accept(
            OrganisationStructureChanged(WIRE_TENANT, "E1", StructureChange.CREATED, node(StructureNodeKind.ENTITY)),
        )
        runner(dir).run { context ->
            context.startupFailure shouldBe null
            val filter = filterOf(context, "entityRefScopeFilter").filter!!
            val tenant = Principal.machine("m", WIRE_TENANT, MachineEntityCoverage.Tenant(WIRE_TENANT.toString()))
            post(filter, tenant) shouldBe 200
            post(
                filter,
                Principal.machine(
                    "m",
                    WIRE_TENANT,
                    MachineEntityCoverage.Tenant("0b000000-0000-0000-0000-00000000000b"),
                ),
            ) shouldBe
                403
        }
    }

    @Test
    fun `a replica answer is an entity only for kind ENTITY, held, filled or last known`() {
        val entity = node(StructureNodeKind.ENTITY)
        val group = node(StructureNodeKind.GROUP)
        EntityRefScopeFilter.isEntityNode(Lookup.Local(entity, Lag.Unknown)) shouldBe true
        EntityRefScopeFilter.isEntityNode(Lookup.Filled(entity)) shouldBe true
        EntityRefScopeFilter.isEntityNode(Lookup.LastKnown(entity, Lag.Unknown)) shouldBe true
        EntityRefScopeFilter.isEntityNode(Lookup.LastKnown(group, Lag.Unknown)) shouldBe false
        EntityRefScopeFilter.isEntityNode(Lookup.Local(group, Lag.Unknown)) shouldBe false
        EntityRefScopeFilter.isEntityNode(Lookup.Filled(group)) shouldBe false
        EntityRefScopeFilter.isEntityNode(Lookup.Absent) shouldBe false
        EntityRefScopeFilter.isEntityNode(Lookup.Unavailable) shouldBe false
    }
}
