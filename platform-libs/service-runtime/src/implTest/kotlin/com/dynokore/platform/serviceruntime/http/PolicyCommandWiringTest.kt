package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.jobs.JobCommands
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.ServletRegistrationBean
import java.lang.reflect.Proxy
import javax.sql.DataSource

/**
 * The Spring wiring of the policy servlet (P109), built as the job command tests build theirs: an
 * [ApplicationContextRunner] over [JobCommandHttpAutoConfiguration]. The servlet is mounted only when a [PolicyWriter]
 * bean exists, shares the idempotency store, picks up the service's [PolicyRoleResolver] when it declares one, and a
 * service with neither jobs nor a writer gets nothing.
 */
class PolicyCommandWiringTest {
    private val tenants = TenantContextImpl()
    private val tx = TransactionRunnerImpl(PGSimpleDataSource(), tenants)
    private val scope = TenantScope(tenants, tx, tx, "svc:wiring-test")
    private val writer = stub<PolicyWriter>()
    private val jobs = stub<JobCommands>()

    private inline fun <reified T : Any> stub(): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { _, _, _ -> null } as T

    private fun runner(): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JobCommandHttpAutoConfiguration::class.java))
            .withBean("dataSource", DataSource::class.java, { PGSimpleDataSource() }, { it.isPrimary = true })
            .withBean(TenantScope::class.java, { scope })
            .withBean(EndUserPrincipalResolver::class.java, { EndUserPrincipalResolver { null } })
            .withBean(java.time.Clock::class.java, { java.time.Clock.systemUTC() })
            .withPropertyValues("gst.service=wiring-test", "service-runtime.idempotency.schema=svc")

    private fun mappings(context: org.springframework.context.ApplicationContext) =
        context
            .getBeansOfType(ServletRegistrationBean::class.java)
            .values
            .flatMap { it.urlMappings }
            .sorted()

    @Test
    fun `a context with a PolicyWriter mounts the policy servlet and the idempotency store, and no job servlet`() {
        runner().withBean(PolicyWriter::class.java, { writer }).run { context ->
            mappings(context) shouldBe listOf(PolicyCommandServlet.MAPPING)
            context.getBeansOfType(IdempotencyStore::class.java).size shouldBe 1
            context.getBeansOfType(IdempotencyKeyPurgeJob::class.java).size shouldBe 0
        }
    }

    @Test
    fun `a context without a PolicyWriter mounts neither the servlet nor the store`() {
        runner().run { context ->
            mappings(context) shouldBe emptyList()
            context.getBeansOfType(IdempotencyStore::class.java).size shouldBe 0
        }
    }

    @Test
    fun `a service with jobs and a PolicyWriter mounts both servlets over one store`() {
        runner()
            .withBean(PolicyWriter::class.java, { writer })
            .withBean(JobCommands::class.java, { jobs })
            .run { context ->
                mappings(context) shouldBe listOf(JobCommandServlet.MAPPING, PolicyCommandServlet.MAPPING).sorted()
                context.getBeansOfType(IdempotencyStore::class.java).size shouldBe 1
            }
    }

    @Test
    fun `the service's PolicyRoleResolver is the one the servlet asks`() {
        val port = PolicyRoleResolver { _, _, _, _, _ -> setOf("tax.approver") }
        runner()
            .withBean(PolicyWriter::class.java, { writer })
            .withBean(PolicyRoleResolver::class.java, { port })
            .run { context ->
                mappings(context) shouldBe listOf(PolicyCommandServlet.MAPPING)
                context.getBean(PolicyRoleResolver::class.java) shouldBe port
            }
    }
}
