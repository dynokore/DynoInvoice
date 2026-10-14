package com.dynokore.platform.serviceruntime.datasource

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import java.beans.Introspector
import javax.sql.DataSource

/**
 * Coordinator ruling INV-Q-4542, checked on what each pool actually receives: [SqlConfigurationKeyGuard] reads the
 * configuration keys, and this post-processor reads every HikariDataSource bean after Spring has bound it (the
 * application pool, the event-log pool, any pool a service declares), before its pool starts. It catches every path to
 * a pool that no key names: a servlet-context parameter, a URL from any property source, a value set in code.
 *
 * It is deny-by-default (lessons P140): every HikariConfig property is classified in [ALLOWED] (with its value check)
 * or [AT_DEFAULT] (it must keep HikariCP's default); a property in neither, or not at its default, fails start-up.
 * Values are never printed (CS-24). A pool's password comes only from SecretStore through the credential path: the
 * password must stay null and the credentials provider, when set, is exactly [SecretStoreCredentialsProvider]
 * (coordinator ruling INV-Q-4570); MBeans stay off, so no login is changed over JMX (coordinator ruling INV-Q-4574).
 */
@Configuration(proxyBeanMethods = false)
class SqlConfigurationGuardWiring {
    companion object {
        /** The second run of the key check (INV-Q-4559). */
        @JvmStatic
        @Bean
        fun serviceRuntimeSqlConfigurationKeyRecheck(): SqlConfigurationKeyRecheck = SqlConfigurationKeyRecheck()
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HikariDataSource::class)
    class Pool {
        companion object {
            @JvmStatic
            @Bean
            fun serviceRuntimePoolConfigurationGuard(environment: Environment): PoolConfigurationGuard =
                PoolConfigurationGuard(environment)

            /** The second pass, after every singleton exists (coordinator ruling INV-Q-4603). */
            @JvmStatic
            @Bean
            fun serviceRuntimePoolConfigurationRecheck(environment: Environment): PoolConfigurationRecheck =
                PoolConfigurationRecheck(environment)
        }
    }
}

/**
 * Ordered, so it runs before service-runtime's own (unordered) pool post-processors, which only set allowed properties
 * (the lanes' maximum, the SecretStore credentials provider, ApplicationName).
 */
class PoolConfigurationGuard(
    private val environment: Environment,
) : BeanPostProcessor,
    Ordered {
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any {
        verify(bean, beanName, environment.getProperty(SERVICE), FIRST_PASS)
        return bean
    }

    companion object {
        internal const val SERVICE = "gst.service"
        private const val FIRST_PASS = "before its pool starts"

        /** The pool of [bean] when it is a HikariConfig or a DataSource that wraps a HikariDataSource, else null. */
        fun poolOf(bean: Any): HikariConfig? =
            (bean as? HikariConfig)
                ?: (bean as? DataSource)
                    ?.takeIf { runCatching { it.isWrapperFor(HikariDataSource::class.java) }.getOrDefault(false) }
                    ?.unwrap(HikariDataSource::class.java)

        /** Fails naming [beanName] and every refused property of its pool (never a value, CS-24); [pass] says when. */
        fun verify(
            bean: Any,
            beanName: String,
            service: String?,
            pass: String,
        ) {
            val pool = poolOf(bean) ?: return
            val offending = findOffendingProperties(pool, service)
            check(offending.isEmpty()) {
                "the pool of bean '$beanName' ($pass) is configured to run SQL, set session state or take a login " +
                    "outside SecretStore, which is refused (coordinator rulings INV-Q-4542, INV-Q-4570, INV-Q-4574, " +
                    "INV-Q-4603; INV-ADR-3968 item 4c, INV-REQ-1230, STK-29): $offending"
            }
        }

        private const val APPLICATION_NAME = "ApplicationName"
        private const val SECRET_STORE_PROVIDER = "SecretStoreCredentialsProvider"

        /** Every HikariConfig property with a getter, by its bean property name. */
        val PROPERTIES: Map<String, java.lang.reflect.Method> =
            Introspector
                .getBeanInfo(HikariConfig::class.java, Any::class.java)
                .propertyDescriptors
                .filter { it.readMethod != null }
                .associate { it.name to it.readMethod }

        /** Every refused property of [pool], sorted, with the reason; [service] is `gst.service`. */
        fun findOffendingProperties(
            pool: HikariConfig,
            service: String?,
        ): List<String> {
            val defaults = HikariConfig()
            return PROPERTIES
                .mapNotNull { (property, getter) ->
                    val value = getter.invoke(pool)
                    when {
                        property in ALLOWED -> ALLOWED.getValue(property)(value, service)
                        value == getter.invoke(defaults) -> null
                        property in AT_DEFAULT -> "set"
                        else -> "set, and not classified (P140)"
                    }?.let { "$property ($it)" }
                }.sorted()
        }

        // ---- Classified by coordinator ruling INV-Q-4542; a property moves to ALLOWED only through a Keel ruling ----

        private val ANY: (Any?, String?) -> String? = { _, _ -> null }

        /** The properties a pool may set, as `spring.datasource` allows them (SqlConfigurationKeyGuard), with their check. */
        val ALLOWED: Map<String, (Any?, String?) -> String?> =
            mapOf(
                // the URL parameter allowlist of every configured URL (INV-Q-4559; password, STK-29, INV-Q-4548)
                "jdbcUrl" to { value, _ -> SqlConfigurationKeyGuard.urlRefusal(value as String?) },
                // only the pool's own name <gst.service>, set in code (coordinator ruling INV-Q-4487)
                "dataSourceProperties" to { value, service ->
                    val properties = (value as java.util.Properties?).orEmpty()
                    val others = properties.keys.map(Any::toString).filter { it != APPLICATION_NAME }
                    when {
                        others.isNotEmpty() -> "refused data-source properties ${others.sorted()}"

                        properties.containsKey(
                            APPLICATION_NAME,
                        ) && properties[APPLICATION_NAME] != service -> "$APPLICATION_NAME other than gst.service"

                        else -> null
                    }
                },
                "username" to ANY,
                // derived from username and password, a new object on every read
                "credentials" to ANY,
                // the login comes only through SecretStore (STK-29, TPL-16, INV-Q-3909; coordinator ruling INV-Q-4570): no
                // provider, or exactly the one DatabaseCredentialsConfiguration sets (class equality, no subclass)
                "credentialsProvider" to { value, _ ->
                    if (value == null || value.javaClass == SecretStoreCredentialsProvider::class.java) {
                        null
                    } else {
                        "only $SECRET_STORE_PROVIDER (INV-Q-4570)"
                    }
                },
                "driverClassName" to ANY,
                "maximumPoolSize" to ANY,
                "minimumIdle" to ANY,
                "connectionTimeout" to ANY,
                "idleTimeout" to ANY,
                "maxLifetime" to ANY,
                "keepaliveTime" to ANY,
                "validationTimeout" to ANY,
                "leakDetectionThreshold" to ANY,
                "initializationFailTimeout" to ANY,
                "poolName" to ANY,
                // Spring Boot's pool metrics and health binders; no SQL or session state
                "metricRegistry" to ANY,
                "metricsTrackerFactory" to ANY,
                "healthCheckRegistry" to ANY,
            )

        /** The properties that must keep HikariCP's default: they run SQL, set session state or replace the driver. */
        val AT_DEFAULT: Set<String> =
            setOf(
                // a password set on the pool, from any property source or code, bypasses SecretStore (STK-29, INV-Q-4570)
                "password",
                // MBeans expose HikariConfigMXBean.setPassword over JMX (coordinator ruling INV-Q-4574)
                "registerMbeans",
                "connectionInitSql",
                "connectionTestQuery",
                "schema",
                "catalog",
                "transactionIsolation",
                "autoCommit",
                "readOnly",
                "isolateInternalQueries",
                "allowPoolSuspension",
                "dataSource",
                "dataSourceClassName",
                "dataSourceJNDI",
                "credentialsProviderClassName",
                "exceptionOverride",
                "exceptionOverrideClassName",
                "healthCheckProperties",
                "scheduledExecutor",
                "threadFactory",
            )
    }
}

/**
 * The second pass of [PoolConfigurationGuard] (coordinator ruling INV-Q-4603): once every singleton exists, every
 * HikariDataSource bean (and every HikariConfig or DataSource wrapping one) is checked again with the same
 * deny-by-default rules. A pool created before the guard was registered (a bean a BeanFactoryPostProcessor fetched
 * early, which no BeanPostProcessor sees) or changed after its first check fails start-up, naming the bean and the
 * properties, never a value (CS-24).
 */
class PoolConfigurationRecheck(
    private val environment: Environment,
) : SmartInitializingSingleton,
    BeanFactoryAware {
    private lateinit var beanFactory: ListableBeanFactory

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory as ListableBeanFactory
    }

    override fun afterSingletonsInstantiated() {
        val service = environment.getProperty(PoolConfigurationGuard.SERVICE)
        listOf(HikariConfig::class.java, DataSource::class.java)
            .flatMap { type -> beanFactory.getBeansOfType(type, false, false).entries }
            .distinctBy { it.key }
            .sortedBy { it.key }
            .forEach { (name, bean) -> PoolConfigurationGuard.verify(bean, name, service, SECOND_PASS) }
    }

    private companion object {
        const val SECOND_PASS = "second pass, after every singleton exists"
    }
}
