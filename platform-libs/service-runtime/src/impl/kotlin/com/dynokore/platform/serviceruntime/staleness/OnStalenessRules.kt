package com.dynokore.platform.serviceruntime.staleness

import com.dynokore.platform.outboxandqueue.StalenessRule
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.ConfigurationCondition
import org.springframework.core.type.AnnotatedTypeMetadata

/** Matches when the service has staleness rules: configured under `service-runtime.staleness.rules`, or [StalenessRule] beans. */
class OnStalenessRules :
    SpringBootCondition(),
    ConfigurationCondition {
    override fun getConfigurationPhase(): ConfigurationCondition.ConfigurationPhase = ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN

    override fun getMatchOutcome(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): ConditionOutcome {
        val configured =
            Binder
                .get(context.environment)
                .bind("${StalenessProperties.PREFIX}.rules", Bindable.listOf(StalenessProperties.Rule::class.java))
                .map { it.isNotEmpty() }
                .orElse(false) == true
        val declared = context.beanFactory?.getBeanNamesForType(StalenessRule::class.java, true, false)?.isNotEmpty() == true
        return if (configured || declared) {
            ConditionOutcome.match("staleness rules configured or declared")
        } else {
            ConditionOutcome.noMatch("no staleness rules")
        }
    }
}
