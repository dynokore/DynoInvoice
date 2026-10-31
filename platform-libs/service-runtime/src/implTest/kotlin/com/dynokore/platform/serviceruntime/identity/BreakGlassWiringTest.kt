package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionChanged
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionView
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.ReplicaAutoConfiguration
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
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
import java.time.Instant

private val BG_TENANT = TenantId.of("0a000000-0000-0000-0000-00000000000a")

/** The restricted break-glass filter and its session view as the auto-configurations build them, in the TPL-18 `@Import` order (P109). */
class BreakGlassWiringTest {
    private val clock = Clock.systemUTC()
    private val structure = StructureReplica(clock) { _, _ -> FallbackResult.NotFound }

    private fun runner(dir: Path): ApplicationContextRunner {
        fun write(
            name: String,
            text: String,
        ) = Files.createDirectories(dir.resolve("META-INF/gst")).resolve(name).let { Files.writeString(it, text) }
        write("break-glass-actions.tsv", "GET\t/v1/submissions/{id}\tVIEW\n")
        write("break-glass-lifecycle-operations.tsv", "POST\t/v1/break-glass/unlock\n")
        return ApplicationContextRunner()
            .withClassLoader(URLClassLoader(arrayOf(dir.toUri().toURL()), javaClass.classLoader))
            .withBean(Clock::class.java, { clock })
            .withBean(StructureReplica::class.java, { structure })
            .withBean(TenantContextImpl::class.java, { TenantContextImpl() })
    }

    private fun status(
        context: org.springframework.context.ApplicationContext,
        method: String,
        path: String,
    ): Int {
        @Suppress("UNCHECKED_CAST")
        val registration =
            context.getBean("breakGlassRestrictionFilter", FilterRegistrationBean::class.java)
                as FilterRegistrationBean<jakarta.servlet.Filter>
        val request = MockHttpServletRequest(method, path)
        request.setAttribute(
            EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE,
            EndUserPrincipal("identity:USR-004", BG_TENANT, emptySet()),
        )
        val response = MockHttpServletResponse()
        checkNotNull(registration.filter).doFilter(
            request,
            response,
            FilterChain { _, res -> (res as MockHttpServletResponse).status = 200 },
        )
        return response.status
    }

    private fun open() =
        BreakGlassSessionChanged(
            "BG-1",
            BG_TENANT,
            "OPEN",
            Instant.now().plusSeconds(600),
            listOf("identity:USR-004", "identity:USR-005"),
        )

    @Test
    fun `the default session view is the authority replica`(
        @TempDir dir: Path,
    ) {
        runner(dir)
            .withConfiguration(
                AutoConfigurations.of(
                    ReplicaAutoConfiguration::class.java,
                    EndUserIdentityAutoConfiguration::class.java,
                ),
            ).withBean(AuthorityReplica::class.java, { AuthorityReplica(clock) })
            .run { context ->
                context.startupFailure shouldBe null
                val authority = context.getBean(AuthorityReplica::class.java)
                context.getBean(BreakGlassSessionView::class.java) shouldBeSameInstanceAs authority.breakGlassSessions
                val registration = context.getBean("breakGlassRestrictionFilter", FilterRegistrationBean::class.java)
                registration.order shouldBe EndUserIdentityAutoConfiguration.ORDER_BREAK_GLASS_RESTRICTION
                registration.order shouldBe -47
                registration.urlPatterns.toList() shouldBe listOf("/*")
                status(context, "POST", "/v1/other") shouldBe 200
                authority.breakGlassSessions.accept(open())
                status(context, "POST", "/v1/other") shouldBe 403
                status(context, "POST", "/v1/break-glass/unlock") shouldBe 200
            }
    }

    @Test
    fun `a service's own session view replaces the default`(
        @TempDir dir: Path,
    ) {
        val own = BreakGlassSessionView { _, _, _ -> null }
        runner(dir)
            .withConfiguration(
                AutoConfigurations.of(
                    ReplicaAutoConfiguration::class.java,
                    EndUserIdentityAutoConfiguration::class.java,
                ),
            ).withBean(AuthorityReplica::class.java, { AuthorityReplica(clock) })
            .withBean(BreakGlassSessionView::class.java, { own })
            .run { context ->
                context.startupFailure shouldBe null
                context.getBean(BreakGlassSessionView::class.java) shouldBeSameInstanceAs own
                context.getBean(AuthorityReplica::class.java).breakGlassSessions.accept(open())
                status(context, "POST", "/v1/other") shouldBe 200
            }
    }

    @Test
    fun `without any session view the context does not start, so the restriction is never silently off`(
        @TempDir dir: Path,
    ) {
        runner(dir)
            .withConfiguration(AutoConfigurations.of(EndUserIdentityAutoConfiguration::class.java))
            .withBean(AuthorityReplica::class.java, { AuthorityReplica(clock) })
            .run { context -> context.startupFailure.shouldNotBeNull() }
    }
}
