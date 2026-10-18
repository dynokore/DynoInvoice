package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.auditlog.AuditSchema
import com.dynokore.platform.configresolver.ConfigSchema
import com.dynokore.platform.outboxandqueue.EventLogSchema
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.serviceruntime.jobs.JobSchema
import com.dynokore.platform.serviceruntime.persistence.PersistenceConfiguration
import com.dynokore.platform.serviceruntime.persistence.PersistenceGuards
import com.dynokore.platform.serviceruntime.persistence.PersistenceLayer
import com.dynokore.platform.tenancyjooq.TableClasses
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.Schema
import org.jooq.impl.DefaultConfiguration
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Lazy
import java.time.Clock

/**
 * `inv.tenancy.*`: a service's own table declarations (INV-ADR-3968 item 4a, INV-T-4134), by qualified name
 * (`schema.table`). The libraries' tables are declared by their own schema classes ([ConfigSchema.platformScopedTables],
 * [OutboxSchema.platformScopedTables], [EventLogSchema.platformScopedTables], [JobSchema.platformScopedTables]); a
 * table that is declared by neither and has no `tenant_id` column fails start-up.
 *
 * @property platformScopedTables tables without a tenant column and without tenant data (their migration carries the
 *   table comment `platform-scoped: no tenant data`).
 * @property referenceTables tables with a `tenant_id` column that also hold platform rows readable by every tenant.
 */
@ConfigurationProperties(prefix = "inv.tenancy")
data class TenancyProperties(
    val platformScopedTables: List<String> = emptyList(),
    val referenceTables: List<String> = emptyList(),
)

/**
 * `inv.persistence.*`: what a service declares to the jOOQ convenience layer (INV-T-4224).
 *
 * @property appendOnlyTables `schema.table` names of the service's append-only tables; `AggregateStore` refuses them
 *   at construction, `LedgerAppender` is their access. Start-up fails, naming the entry, for a blank entry, one that is
 *   not two lower-case identifiers joined by one dot, or a repeated one (see [PersistenceGuards.requireAppendOnlyNames]).
 */
@ConfigurationProperties(prefix = "inv.persistence")
data class PersistenceProperties(
    val appendOnlyTables: List<String> = emptyList(),
) {
    init {
        PersistenceGuards.requireAppendOnlyNames(appendOnlyTables)
    }
}

/**
 * The framework tenant predicate on jOOQ, wired into every service (INV-T-4134; INV-ADR-3968 item 4a, INV-REQ-854 b6;
 * owner rulings INV-Q-4113, INV-Q-4196).
 *
 * - [TenancyTables]: the service's generated jOOQ schemas (its `org.jooq.Schema` beans) classified by
 *   [TableClasses.verify]. Start-up fails for a table without a tenant column that is not declared `PLATFORM_SCOPED`, a
 *   declared table that is missing, or a class that disagrees with the table's columns.
 * - The `org.jooq.Configuration` and `DSLContext` beans over tenancy-kernel's [TransactionRunner], with tenancy-jooq's
 *   listeners and connection provider ([TenancyJooq.configure]).
 * - [TenancyJooqGuard]: start-up fails for any `DSLContext` or `org.jooq.Configuration` bean that does not carry them.
 *
 * Every `TenancyTables` must have been built by `TableClasses.verify` (owner answer INV-Q-4821 B): the `tenancyTables`
 * bean is not replaceable, so a service that defines its own `TenancyTables` bean fails start-up.
 *
 * Its own auto-configuration, after the L1 composition (which creates the [TransactionRunner]) and before Spring Boot's
 * jOOQ auto-configuration, which then backs off: it would build a configuration on a plain `DataSource`.
 * service-runtime creates no JdbcTemplate, NamedParameterJdbcTemplate, TransactionTemplate or transaction manager.
 */
@AutoConfiguration(
    afterName = ["com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration"],
    beforeName = ["org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration"],
)
@EnableConfigurationProperties(TenancyProperties::class, PersistenceProperties::class)
@Import(TenancyJooqWiring.BootCustomizer::class)
class TenancyJooqWiring {
    @Bean
    fun tenancyTables(
        schemas: ObjectProvider<Schema>,
        declared: TenancyProperties,
        modules: ModuleProperties,
        transactionRunner: ObjectProvider<TransactionRunner>,
    ): TenancyTables {
        val generated = schemas.orderedStream().toList()
        // INV-Q-4815: a service with a database (a TransactionRunner) supplies its generated schema; none is a failed check
        check(transactionRunner.getIfAvailable() == null || generated.any { it.tables.isNotEmpty() }) {
            "the context has a TransactionRunner but no org.jooq.Schema bean that holds a table: declare one " +
                "org.jooq.Schema bean per generated schema in the service's bootstrap module (INV-Q-4815, " +
                "INV-ADR-3968 item 4a: table classes are checked at start-up against the generated schema)"
        }
        val present = generated.flatMap { it.tables }.map { it.qualifiedName.name.joinToString(".") }.toSet()
        val libraries =
            (
                modules.schema
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        ConfigSchema.platformScopedTables(it) +
                            OutboxSchema.platformScopedTables(it) +
                            JobSchema.platformScopedTables(it)
                    }.orEmpty() + EventLogSchema.platformScopedTables()
            ).filter { it in present } // a library table absent from the generated schemas is not the service's
        return TableClasses
            .of(
                platformScoped = (declared.platformScopedTables + libraries).distinct(),
                reference = declared.referenceTables,
            ).verify(*generated.toTypedArray())
    }

    @Bean
    @ConditionalOnBean(TransactionRunner::class)
    @ConditionalOnMissingBean(Configuration::class)
    fun tenancyJooqConfiguration(
        tables: TenancyTables,
        tenants: TenantContext,
        transactionRunner: TransactionRunner,
        clock: ObjectProvider<Clock>,
    ): Configuration {
        val base = DefaultConfiguration().set(SQLDialect.POSTGRES)
        PersistenceConfiguration.settings(base)
        val audit = PersistenceConfiguration.recordListener(clock.getIfAvailable(Clock::systemUTC), tenants)
        return TenancyJooq.configure(base, tables, tenants, transactionRunner, listOf(audit))
    }

    @Bean
    @ConditionalOnBean(TransactionRunner::class)
    @ConditionalOnMissingBean(DSLContext::class)
    fun tenancyJooqDslContext(configuration: Configuration): DSLContext = configuration.dsl()

    /** The tables `AggregateStore` refuses (INV-ADR-3968 item 11) and the service's append-only tables. */
    @Bean
    fun persistenceGuards(
        declared: PersistenceProperties,
        modules: ModuleProperties,
    ): PersistenceGuards =
        PersistenceGuards(
            protectedTables =
                listOfNotNull(AuditSchema.AUDIT_ENTRY, AuditSchema.OVERRIDE_GRANT, modules.evidence.ledgerTable),
            appendOnlyTables = declared.appendOnlyTables,
        )

    /** The jOOQ convenience layer: `AggregateStore` and `LedgerAppender` factories over the service's DSLContext. */
    @Bean
    @Lazy
    @ConditionalOnBean(TransactionRunner::class)
    @ConditionalOnMissingBean(PersistenceLayer::class)
    fun persistenceLayer(
        dsl: DSLContext,
        guards: PersistenceGuards,
    ): PersistenceLayer = PersistenceLayer(dsl, guards)

    /** Spring Boot's jOOQ auto-configuration, when a service has it: its configuration gets the same listeners. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer"])
    class BootCustomizer {
        @Bean
        @ConditionalOnBean(TransactionRunner::class)
        fun tenancyJooqCustomizer(
            tables: TenancyTables,
            tenants: TenantContext,
            transactionRunner: TransactionRunner,
            clock: ObjectProvider<Clock>,
        ): org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer =
            org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer { configuration ->
                configuration.set(SQLDialect.POSTGRES)
                PersistenceConfiguration.settings(configuration)
                val audit = PersistenceConfiguration.recordListener(clock.getIfAvailable(Clock::systemUTC), tenants)
                TenancyJooq.install(configuration, tables, tenants, transactionRunner, listOf(audit))
            }
    }

    companion object {
        /** Refuses any `DSLContext` or `org.jooq.Configuration` bean that tenancy-jooq did not make. */
        @Bean
        @JvmStatic
        fun tenancyJooqGuard(): TenancyJooqGuard = TenancyJooqGuard()
    }
}

/**
 * Every `TenancyTables` must have been built by `TableClasses.verify` (owner answer INV-Q-4821 B); a `DSLContext` or
 * `org.jooq.Configuration` over any other fails the check.
 * Start-up fails when a `DSLContext` or `org.jooq.Configuration` bean lacks tenancy-jooq's connection provider, its
 * VisitListener or its ExecuteListener (INV-T-4134; owner ruling INV-Q-4196). Deny by default: it checks every such
 * bean the moment it is created, and again once all singletons exist (lazy beans are created for the check), and a
 * listener list holding anything but tenancy-jooq's VisitListener, or not ending in its ExecuteListener, is a finding.
 * tenancy-jooq's listener classes are internal to it; they are identified by their class identity in the class loader
 * of [TenancyJooq], and a class that cannot be resolved there fails the check rather than passing it.
 */
class TenancyJooqGuard :
    BeanPostProcessor,
    BeanFactoryAware,
    SmartInitializingSingleton {
    private var beans: ListableBeanFactory? = null

    override fun setBeanFactory(beanFactory: BeanFactory) {
        beans = beanFactory as? ListableBeanFactory
    }

    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any {
        inspect(bean, beanName)
        return bean
    }

    override fun afterSingletonsInstantiated() {
        val factory = checkNotNull(beans) { "the tenancy-jooq guard needs a ListableBeanFactory" }
        listOf(DSLContext::class.java, Configuration::class.java).forEach { type ->
            factory.getBeansOfType(type, true, true).forEach { (name, bean) -> inspect(bean, name) }
        }
    }

    private fun inspect(
        bean: Any,
        beanName: String,
    ) {
        val configuration =
            when (bean) {
                is DSLContext -> bean.configuration()
                is Configuration -> bean
                else -> return
            }
        val problem = problem(configuration)
        check(problem == null) {
            "bean '$beanName' (${bean.javaClass.name}) is not made by tenancy-jooq: $problem " +
                "(INV-ADR-3968 item 4a, owner ruling INV-Q-4196); create it with TenancyJooq.configure, " +
                "or use the DSLContext bean service-runtime provides"
        }
    }

    internal companion object {
        /** Why [configuration] is not tenancy-jooq's, or null (tenancy-jooq's own check, INV-Q-5105). */
        fun problem(configuration: Configuration): String? = TenancyJooq.problem(configuration)
    }
}
