package com.dynokore.platform.serviceruntime.spel

import com.dynokore.platform.serviceruntime.datasource.SqlConfigurationKeyGuardTest.Companion.TPL_16
import com.dynokore.platform.serviceruntime.health.Tpl18WebService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.BeanExpressionContext
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.expression.StandardBeanExpressionResolver
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.support.SpringFactoriesLoader
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.nio.file.Path
import java.util.Properties

/**
 * Coordinator ruling INV-Q-4694 (C) through SpringApplication, as a service composed as TPL-18 starts: SpEL is refused
 * where Spring evaluates it, and text that no evaluation point reads never affects start-up.
 */
class SpelBoundaryIT {
    @Test
    fun `the resolver never evaluates, returns plain text unchanged and refuses a template without printing it`() {
        val resolver = SpelRefusingExpressionResolver()
        val context = BeanExpressionContext(DefaultListableBeanFactory(), null)
        resolver.evaluate("/error", context) shouldBe "/error"
        resolver.evaluate(null, context) shouldBe null
        val failure = shouldThrow<IllegalStateException> { resolver.evaluate("#{1+1}", context) }
        failure.message.orEmpty() shouldContain "INV-Q-4694"
        failure.message.orEmpty() shouldNotContain "1+1"
    }

    @Test
    fun `the initializer is registered in spring-factories`() {
        val registered =
            javaClass.classLoader
                .getResources(SpringFactoriesLoader.FACTORIES_RESOURCE_LOCATION)
                .toList()
                .flatMap { url ->
                    Properties()
                        .apply { url.openStream().use(::load) }
                        .getProperty(ApplicationContextInitializer::class.java.name)
                        .orEmpty()
                        .split(',')
                        .map(String::trim)
                }
        registered shouldContain SpelRefusalInitializer::class.java.name
    }

    @Test
    fun `the OnExpressionCondition class name still exists (fails when Spring Boot renames it)`() {
        Class.forName(SpelBoundaryRecheck.ON_EXPRESSION_CONDITION).simpleName shouldBe "OnExpressionCondition"
    }

    @Test
    fun `text that no evaluation point reads starts, from the real environment, a property and a variable`(
        @TempDir secrets: Path,
    ) {
        // the real environment (in CI: CI_MERGE_REQUEST_DESCRIPTION, CI_COMMIT_MESSAGE and the rest)
        service().run(*arguments(secrets), "--probe.unread=#{1+1}").use { context ->
            context.isActive shouldBe true
            context.beanFactory.beanExpressionResolver.shouldBeInstanceOf<SpelRefusingExpressionResolver>()
            // Spring Boot's error path placeholder (server.error.path) still resolves
            context
                .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping::class.java)
                .handlerMethods.keys
                .flatMap { it.patternValues } shouldContain "/error"
        }
        service().environment(variables("PROBE_UNREAD" to "#{1+1}")).run(*arguments(secrets)).use { context ->
            context.isActive shouldBe true
        }
    }

    @Test
    fun `a @Value whose key resolves to a template fails, as a property, a variable and by placeholders alone`(
        @TempDir secrets: Path,
    ) {
        listOf(
            refused(secrets, ValueProbe::class.java, "--probe.key=#{1+1}"),
            refused(secrets, ValueProbe::class.java, environment = variables("PROBE_KEY" to "#{1+1}")),
            refused(secrets, ValueProbe::class.java, "--probe.key=#\${probe.empty:}{1+1}"),
        ).forEach { failure ->
            failure shouldContain "valueProbe"
            failure shouldContain "INV-Q-4694"
            failure shouldNotContain "1+1"
        }
    }

    @Test
    fun `an embedded-value attribute (a @Scheduled cron from a placeholder) resolving to a template fails`(
        @TempDir secrets: Path,
    ) {
        val failure = refused(secrets, ScheduledProbe::class.java, "--probe.cron=#{'0 0 * * * *'}")
        failure shouldContain "INV-Q-4694"
        failure shouldNotContain "0 0 *"
    }

    @Test
    fun `a @ConditionalOnExpression fails at the second pass`(
        @TempDir secrets: Path,
    ) {
        val failure = refused(secrets, ExpressionConditionProbe::class.java)
        failure shouldContain "@ConditionalOnExpression"
        failure shouldContain ExpressionConditionProbe::class.java.name
    }

    @Test
    fun `a resolver replaced after installation fails at the second pass`(
        @TempDir secrets: Path,
    ) {
        val failure = refused(secrets, ResolverReplacement::class.java)
        failure shouldContain StandardBeanExpressionResolver::class.java.name
        failure shouldContain "INV-Q-4694"
    }

    @Component("valueProbe")
    class ValueProbe {
        @Value("\${probe.key}")
        lateinit var key: String
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    class ScheduledProbe {
        @Scheduled(cron = "\${probe.cron}")
        fun tick() = Unit
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression("true")
    class ExpressionConditionProbe

    @Configuration(proxyBeanMethods = false)
    class ResolverReplacement {
        companion object {
            @JvmStatic
            @Bean
            fun replaceResolver(): BeanFactoryPostProcessor =
                BeanFactoryPostProcessor { it.beanExpressionResolver = StandardBeanExpressionResolver() }
        }
    }

    private fun refused(
        secrets: Path,
        probe: Class<*>,
        vararg extra: String,
        environment: StandardEnvironment? = null,
    ): String {
        val builder = SpringApplicationBuilder(Tpl18WebService::class.java, probe).web(WebApplicationType.SERVLET)
        environment?.let(builder::environment)
        val failure = shouldThrow<Exception> { builder.run(*arguments(secrets), *extra).close() }
        return generateSequence<Throwable>(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
    }

    private fun variables(vararg entries: Pair<String, String>): StandardEnvironment =
        StandardEnvironment().also {
            it.propertySources.replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    System.getenv() + mapOf(*entries),
                ),
            )
        }

    private fun service() = SpringApplicationBuilder(Tpl18WebService::class.java).web(WebApplicationType.SERVLET)

    // As SqlConfigurationKeyGuardContextIT: command-line arguments, no database (the TPL-16 keys are present and unused).
    private fun arguments(secrets: Path): Array<String> =
        (
            listOf(
                "server.port=0",
                "management.server.port=0",
                "service-runtime.workload-identity.audience=submission-svc",
                "gst.service=submission-svc",
                "gst.bindings.secret-store.mount-root=$secrets",
                "spring.autoconfigure.exclude=" +
                    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
                    "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            ) + TPL_16.map { (key, value) -> "$key=$value" }
        ).map { "--$it" }.toTypedArray()
}
