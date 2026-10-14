package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.serviceruntime.datasource.tpl18Service
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.ExecuteListener
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.Test
import org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import javax.sql.DataSource
import org.springframework.context.annotation.Configuration as SpringConfiguration

/**
 * INV-T-4134 (INV-ADR-3968 item 4a, owner rulings INV-Q-4113, INV-Q-4196): a TPL-18 service (P109: the composition of
 * [tpl18Service], every platform-libs auto-configuration) with a TENANT table, a PLATFORM_SCOPED table, the libraries'
 * own tenantless tables and, in the failing cases, an undeclared tenantless table. The database is never reached.
 */
class TenancyJooqWiringTest {
    private val service =
        tpl18Service().withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://localhost:5432/submission",
            "spring.datasource.username=submission_app",
            "service-runtime.modules.schema=${Tables.SCHEMA}",
            "inv.tenancy.platform-scoped-tables=${Tables.SCHEMA}.flag",
        )

    private fun failure(context: AssertableApplicationContext): String =
        generateSequence(
            context.startupFailure.shouldNotBeNull(),
        ) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `a TENANT, a PLATFORM_SCOPED and the libraries' tables start on tenancy-jooq's DSLContext`() {
        service.withUserConfiguration(SubmissionSchemaConfiguration::class.java).run { context ->
            context.startupFailure shouldBe null
            val tables = context.getBean(TenancyTables::class.java)
            tables.names shouldContain "submission.item"
            val item = tables.classOf(DSL.name("submission", "item"))
            item shouldBe TableClass.TENANT
            tables.classOf(DSL.name("submission", "flag")) shouldBe TableClass.PLATFORM_SCOPED
            // the libraries declare their own tenantless tables: the service declared none of these
            listOf("config_installation_setting", "consumer_offset", "shedlock", "job_retention", "lane_rate_window")
                .forEach { tables.classOf(DSL.name("submission", it)) shouldBe TableClass.PLATFORM_SCOPED }
            context.getBeansOfType(DSLContext::class.java).size shouldBe 1
            context.getBeansOfType(Configuration::class.java).size shouldBe 1
            // service-runtime creates no JdbcTemplate, TransactionTemplate or transaction manager (INV-Q-4196)
            listOf(
                "org.springframework.jdbc.core.JdbcTemplate",
                "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
                "org.springframework.transaction.support.TransactionTemplate",
                "org.springframework.transaction.PlatformTransactionManager",
            ).forEach { name ->
                context.getBeanNamesForType(Class.forName(name)).toList() shouldBe emptyList()
            }
        }
    }

    @Test
    fun `an undeclared tenantless table fails start-up`() {
        service.withUserConfiguration(StraySchemaConfiguration::class.java).run { context ->
            failure(context) shouldContain
                "submission.stray has no tenant_id column and is not declared PLATFORM_SCOPED"
        }
    }

    @Test
    fun `a declaration that disagrees with the generated schema fails start-up`() {
        val cases =
            mapOf(
                "inv.tenancy.platform-scoped-tables=submission.item" to "submission.item is declared PLATFORM_SCOPED",
                "inv.tenancy.reference-tables=submission.flag" to "tables declared twice",
                "inv.tenancy.platform-scoped-tables=submission.flag,submission.nowhere" to
                    "submission.nowhere is declared but missing",
                "inv.tenancy.reference-tables=submission.item,submission.item" to "declared twice",
            )
        cases.forEach { (property, message) ->
            service
                .withUserConfiguration(SubmissionSchemaConfiguration::class.java)
                .withPropertyValues(property)
                .run { context -> failure(context) shouldContain message }
        }
        service
            .withUserConfiguration(StraySchemaConfiguration::class.java)
            .withPropertyValues("inv.tenancy.reference-tables=submission.stray")
            .run { context ->
                failure(context) shouldContain "submission.stray is declared REFERENCE but has no tenant_id column"
            }
        // a library table is declared by its module; the service cannot reclassify it as REFERENCE
        service
            .withUserConfiguration(SubmissionSchemaConfiguration::class.java)
            .withPropertyValues("inv.tenancy.reference-tables=submission.shedlock")
            .run { context -> failure(context) shouldContain "submission.shedlock" }
    }

    @Test
    fun `a DSLContext made with DSL-using on the DataSource fails start-up`() {
        service
            .withUserConfiguration(SubmissionSchemaConfiguration::class.java, RogueDslContext::class.java)
            .run { context -> failure(context) shouldContain "bean 'rogueDsl'" }
    }

    @Test
    fun `an org-jooq-Configuration without the tenancy listeners fails start-up`() {
        service
            .withUserConfiguration(SubmissionSchemaConfiguration::class.java, RogueConfiguration::class.java)
            .run { context ->
                failure(context) shouldContain "bean 'rogueConfiguration'"
                failure(context) shouldContain "connection provider is not tenancy-jooq's"
            }
    }

    @Test
    fun `a configuration needs all of tenancy-jooq's parts and no listener after its statement check`() {
        service.withUserConfiguration(SubmissionSchemaConfiguration::class.java).run { context ->
            context.startupFailure shouldBe null
            val guard = TenancyJooqGuard()
            val owned = context.getBean(Configuration::class.java)
            guard.postProcessAfterInitialization(owned, "owned") shouldBe owned
            guard.postProcessAfterInitialization(context.getBean(DSLContext::class.java), "dsl")

            fun refused(configuration: Configuration): String =
                runCatching { guard.postProcessAfterInitialization(configuration, "partial") }
                    .exceptionOrNull()
                    .shouldNotBeNull()
                    .message
                    .orEmpty()

            val postgres = { DefaultConfiguration().set(SQLDialect.POSTGRES) }
            // no execute listener
            refused(postgres().set(owned.connectionProvider()).set(*owned.visitListenerProviders())) shouldContain
                "ExecuteListener"
            // no visit listener
            refused(postgres().set(owned.connectionProvider()).set(*owned.executeListenerProviders())) shouldContain
                "VisitListeners"
            // no connection provider of tenancy-jooq's
            refused(
                postgres().set(*owned.visitListenerProviders()).set(*owned.executeListenerProviders()),
            ) shouldContain
                "connection provider"
            // a listener after the statement check could change the statement
            val after = DefaultExecuteListenerProvider(mockk<ExecuteListener>(relaxed = true))
            refused(owned.derive().deriveAppending(after.provide())) shouldContain "ExecuteListener"
        }
    }

    @Test
    fun `Spring Boot's customizer installs the same listeners on its DefaultConfiguration`() {
        service.withUserConfiguration(SubmissionSchemaConfiguration::class.java).run { context ->
            context.startupFailure shouldBe null
            val boot = DefaultConfiguration()
            context.getBean(DefaultConfigurationCustomizer::class.java).customize(boot)
            TenancyJooqGuard().postProcessAfterInitialization(boot, "boot")
            // and a customizer-less Boot configuration is refused
            runCatching { TenancyJooqGuard().postProcessAfterInitialization(DefaultConfiguration(), "plain") }
                .isFailure shouldBe true
        }
    }

    @Test
    fun `tenancy-jooq's own configure result is what the guard accepts`() {
        service.withUserConfiguration(SubmissionSchemaConfiguration::class.java).run { context ->
            val configured =
                TenancyJooq.configure(
                    DefaultConfiguration().set(SQLDialect.POSTGRES),
                    context.getBean(TenancyTables::class.java),
                    context.getBean(TenantContext::class.java),
                    context.getBean(TransactionRunner::class.java),
                )
            TenancyJooqGuard().postProcessAfterInitialization(configured, "configured")
        }
    }

    @SpringConfiguration(proxyBeanMethods = false)
    class RogueDslContext {
        @Bean
        fun rogueDsl(dataSource: DataSource): DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)
    }

    @SpringConfiguration(proxyBeanMethods = false)
    class RogueConfiguration {
        @Bean
        fun rogueConfiguration(): Configuration = DefaultConfiguration().set(SQLDialect.POSTGRES)
    }
}
