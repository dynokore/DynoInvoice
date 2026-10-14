package com.dynokore.platform.serviceruntime.spel

import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.BeanExpressionContext
import org.springframework.beans.factory.config.BeanExpressionResolver
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.expression.StandardBeanExpressionResolver
import org.springframework.core.Ordered
import org.springframework.core.PriorityOrdered
import org.springframework.expression.Expression
import org.springframework.expression.ExpressionParser
import org.springframework.expression.ParserContext
import org.springframework.expression.common.LiteralExpression

/**
 * Coordinator ruling INV-Q-4694 (C): SpEL is refused where Spring evaluates it. Spring evaluates a template only through
 * the bean factory's [BeanExpressionResolver]: `@Value` after placeholder resolution, every EmbeddedValueResolver user
 * (`@Scheduled`, `@RequestMapping` and the rest), `@Value` method parameters and bean-definition strings, whatever the
 * key or property source. This resolver never parses or evaluates: a string without the template prefix is returned
 * unchanged, and any string that contains it fails. The value is never printed (CS-24); Spring's wrapping exception names
 * the bean and the injection point. No property source is scanned, so text that no evaluation point reads (a CI
 * variable, a pod annotation) never affects start-up.
 */
class SpelRefusingExpressionResolver : BeanExpressionResolver {
    override fun evaluate(
        value: String?,
        beanExpressionContext: BeanExpressionContext,
    ): Any? {
        refuseTemplate(value)
        return value
    }

    companion object {
        const val TEMPLATE_PREFIX = "#{"

        /** Fails when [value] holds the SpEL template prefix; the value is never printed (CS-24). */
        fun refuseTemplate(value: String?) {
            check(value == null || !value.contains(TEMPLATE_PREFIX)) {
                "a SpEL template is refused: Spring would evaluate it here, and INV evaluates no SpEL (coordinator " +
                    "ruling INV-Q-4694); bind the value with @ConfigurationProperties instead"
            }
        }
    }
}

/**
 * The parser given to Spring's standard resolver that `prepareBeanFactory` created (INV-Q-4694): Spring's
 * ApplicationContextAwareProcessor builds its EmbeddedValueResolver (for every EmbeddedValueResolverAware bean, such as
 * the `@Scheduled` processor) at that moment and keeps that resolver, so replacing the bean factory's resolver alone does
 * not reach it. It never parses: text without the template prefix is a literal, and a template fails.
 */
class SpelRefusingExpressionParser : ExpressionParser {
    override fun parseExpression(expressionString: String): Expression = literal(expressionString)

    override fun parseExpression(
        expressionString: String,
        context: ParserContext,
    ): Expression = literal(expressionString)

    private fun literal(text: String): Expression {
        SpelRefusingExpressionResolver.refuseTemplate(text)
        return LiteralExpression(text)
    }
}

/**
 * Installs [SpelRefusingExpressionResolver] on the bean factory (INV-Q-4694). A BeanFactoryPostProcessor, because
 * `prepareBeanFactory` sets Spring's standard resolver at the start of every refresh; with the highest PriorityOrdered
 * order, so it runs before any bean-defined BeanFactoryPostProcessor and before any application bean. It is not a
 * BeanDefinitionRegistryPostProcessor (INV-T-4593 refuses those).
 */
class SpelRefusingResolverInstaller :
    BeanFactoryPostProcessor,
    PriorityOrdered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        // the standard resolver may already be held by an EmbeddedValueResolver: it is made unable to evaluate too
        (beanFactory.beanExpressionResolver as? StandardBeanExpressionResolver)
            ?.setExpressionParser(SpelRefusingExpressionParser())
        if (beanFactory.beanExpressionResolver !is SpelRefusingExpressionResolver) {
            beanFactory.beanExpressionResolver = SpelRefusingExpressionResolver()
        }
    }
}

/**
 * Listed in service-runtime's `META-INF/spring.factories`, so every service's context gets [SpelRefusingResolverInstaller]
 * as a context post-processor, which runs before every bean-defined one (INV-Q-4694).
 */
class SpelRefusalInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        applicationContext.addBeanFactoryPostProcessor(SpelRefusingResolverInstaller())
    }
}

/**
 * The second pass of the SpEL boundary (INV-Q-4694): once every singleton exists, start-up fails, naming the cause, when
 * the bean factory's BeanExpressionResolver is not [SpelRefusingExpressionResolver] (it was replaced after installation),
 * and when Spring Boot's ConditionEvaluationReport holds any OnExpressionCondition outcome (a `@ConditionalOnExpression`
 * evaluated while configuration classes were parsed, before the resolver was installed).
 */
class SpelBoundaryRecheck :
    SmartInitializingSingleton,
    BeanFactoryAware {
    private lateinit var beanFactory: ConfigurableListableBeanFactory

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory as ConfigurableListableBeanFactory
    }

    override fun afterSingletonsInstantiated() {
        val resolver = beanFactory.beanExpressionResolver
        check(resolver is SpelRefusingExpressionResolver) {
            "the bean factory's BeanExpressionResolver is ${resolver?.javaClass?.name}, not service-runtime's " +
                "SpelRefusingExpressionResolver: SpEL could be evaluated (coordinator ruling INV-Q-4694)"
        }
        val expressions = findExpressionConditions(beanFactory)
        check(expressions.isEmpty()) {
            "@ConditionalOnExpression is refused: its SpEL was evaluated while configuration classes were parsed " +
                "(coordinator ruling INV-Q-4694); use @ConditionalOnProperty: $expressions"
        }
    }

    companion object {
        /** Spring Boot's condition class behind `@ConditionalOnExpression` (package-private, so matched by name). */
        const val ON_EXPRESSION_CONDITION = "org.springframework.boot.autoconfigure.condition.OnExpressionCondition"

        /** The sources (classes or methods) with an OnExpressionCondition outcome in the report, sorted. */
        fun findExpressionConditions(beanFactory: ConfigurableListableBeanFactory): List<String> =
            ConditionEvaluationReport
                .get(beanFactory)
                .conditionAndOutcomesBySource
                .filterValues { outcomes -> outcomes.any { it.condition.javaClass.name == ON_EXPRESSION_CONDITION } }
                .keys
                .sorted()
    }
}
