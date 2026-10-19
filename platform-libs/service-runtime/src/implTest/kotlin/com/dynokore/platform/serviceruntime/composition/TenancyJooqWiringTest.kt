package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.serviceruntime.datasource.tpl18Service
import com.dynokore.platform.serviceruntime.datasource.withBindingKey
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TableClasses
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
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
            "test.default-schema=false",
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
            // service-runtime creates no JdbcTemplate, NamedParameterJdbcTemplate, TransactionTemplate or transaction
            // manager (INV-Q-4196): no bean definition has one of these type names (spring-jdbc stays on the class path
            // while ShedLock waits for owner question INV-Q-5210, so the class files cannot be absent)
            val refused =
                setOf(
                    "org.springframework.jdbc.core.JdbcTemplate",
                    "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
                    "org.springframework.transaction.support.TransactionTemplate",
                    "org.springframework.transaction.PlatformTransactionManager",
                )
            val types = context.beanDefinitionNames.mapNotNull { context.getType(it)?.name }
            types.filter { it in refused } shouldBe emptyList()
        }
    }

    @Test
    fun `a service with a database and no Schema bean that holds a table fails start-up (INV-Q-4815)`() {
        // (1) no Schema bean, (2) a Schema bean without tables, (3) an org.jooq.Catalog look-alike, then a Table look-alike
        listOf(
            service,
            service.withUserConfiguration(EmptySchemaConfiguration::class.java),
            service.withUserConfiguration(CatalogOnlyConfiguration::class.java),
            service.withUserConfiguration(TableOnlyConfiguration::class.java),
        ).forEach { runner ->
            runner.run { context ->
                val message = failure(context)
                message shouldContain "org.jooq.Schema"
                message shouldContain "bootstrap module"
                message shouldContain "INV-Q-4815"
            }
        }
    }

    @Test
    fun `a service without a database needs no Schema bean and defines no DSLContext (INV-Q-4815)`() {
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBindingKey()
            .withUserConfiguration(Tpl18WithoutDataSourceApplication::class.java)
            .withPropertyValues(
                "service-runtime.workload-identity.audience=submission-svc",
                "gst.service=submission-svc",
                "gst.instance-id=submission-svc-0",
                "service-runtime.pool.profile=minimal",
            ).run { context ->
                context.startupFailure shouldBe null
                context.getBeanNamesForType(TransactionRunner::class.java).size shouldBe 0
                context.getBeanNamesForType(DSLContext::class.java).size shouldBe 0
            }
    }

    @Test
    fun `the replay state tables are PLATFORM_SCOPED and a tenant_id column on consumer_replay fails start-up`() {
        service.withUserConfiguration(SubmissionSchemaConfiguration::class.java).run { context ->
            val tables = context.getBean(TenancyTables::class.java)
            listOf("consumer_replay", "consumer_replay_marker", "consumer_source_seen").forEach {
                tables.classOf(DSL.name("submission", it)) shouldBe TableClass.PLATFORM_SCOPED
            }
        }
        service.withUserConfiguration(ReplayGainsTenantSchemaConfiguration::class.java).run { context ->
            failure(context) shouldContain
                "submission.consumer_replay is declared PLATFORM_SCOPED but has a tenant_id column"
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
    fun `a DSLContext not made by tenancy-jooq fails start-up`() {
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

    @Test
    fun `a second TenancyTables bean built by TableClasses-verify fails start-up (INV-Q-4821 B)`() {
        service
            .withUserConfiguration(SubmissionSchemaConfiguration::class.java, SecondVerifiedTables::class.java)
            .run { context ->
                // two candidates, the library's and the service's (not the guard's refusal of a foreign bean)
                failure(context) shouldContain "TenancyTables"
                failure(context) shouldContain "tenancyTables"
                failure(context) shouldContain "verifiedTables"
            }
    }

    @Test
    fun `a second TenancyTables bean built by TenancyTables-declared fails start-up (INV-Q-4821 B)`() {
        service
            .withUserConfiguration(SubmissionSchemaConfiguration::class.java, SecondDeclaredTables::class.java)
            .run { context ->
                failure(context) shouldContain "TenancyTables"
                failure(context) shouldContain "tenancyTables"
                failure(context) shouldContain "declaredTables"
            }
    }

    @Test
    fun `a Configuration over declared TenancyTables fails start-up (INV-Q-4821 B)`() {
        service
            .withUserConfiguration(SubmissionSchemaConfiguration::class.java, DeclaredConfiguration::class.java)
            .run { context ->
                failure(context) shouldContain "bean 'declaredConfiguration'"
                failure(context) shouldContain "TableClasses.verify"
            }
    }

    @Test
    fun `a Configuration made by TenancyJooq-configure over the context's own TenancyTables starts`() {
        service
            .withUserConfiguration(SubmissionSchemaConfiguration::class.java, OwnTablesConfiguration::class.java)
            .run { context -> context.startupFailure shouldBe null }
    }

    @SpringConfiguration(proxyBeanMethods = false)
    class SecondVerifiedTables {
        @Bean
        fun verifiedTables(): TenancyTables =
            TableClasses
                .of(
                    platformScoped =
                        (Tables.SERVICE + Tables.LIBRARY)
                            .filter {
                                !it.second
                            }.map { "${Tables.SCHEMA}.${it.first}" },
                ).verify(FixtureSchema(Tables.SCHEMA, Tables.SERVICE + Tables.LIBRARY))
    }

    @SpringConfiguration(proxyBeanMethods = false)
    class SecondDeclaredTables {
        @Bean
        fun declaredTables(): TenancyTables =
            TenancyTables.declared(mapOf(DSL.name(Tables.SCHEMA, "item") to TableClass.TENANT))
    }

    @SpringConfiguration(proxyBeanMethods = false)
    class DeclaredConfiguration {
        @Bean
        fun declaredConfiguration(
            tenants: TenantContext,
            runner: TransactionRunner,
        ): Configuration =
            TenancyJooq.configure(
                DefaultConfiguration().set(SQLDialect.POSTGRES),
                TenancyTables.declared(mapOf(DSL.name(Tables.SCHEMA, "item") to TableClass.TENANT)),
                tenants,
                runner,
            )
    }

    @SpringConfiguration(proxyBeanMethods = false)
    class OwnTablesConfiguration {
        @Bean
        fun ownTablesConfiguration(
            tables: TenancyTables,
            tenants: TenantContext,
            runner: TransactionRunner,
        ): Configuration =
            TenancyJooq.configure(DefaultConfiguration().set(SQLDialect.POSTGRES), tables, tenants, runner)
    }

    @SpringConfiguration(proxyBeanMethods = false)
    class RogueDslContext {
        @Bean
        fun rogueDsl(): DSLContext = DefaultConfiguration().set(SQLDialect.POSTGRES).dsl()
    }

    @SpringConfiguration(proxyBeanMethods = false)
    class RogueConfiguration {
        @Bean
        fun rogueConfiguration(): Configuration = DefaultConfiguration().set(SQLDialect.POSTGRES)
    }
}
