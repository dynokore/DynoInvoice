package com.dynokore.platform.productruntime

import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.ConfigurationCondition
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Matches when the context holds a [ConsumerSubscription] bean whose topic is the authority topic (INV-T-9059): the
 * service reads AuthorityChanged, so its replica needs the head feed, the replay intake and the completion listener.
 * access-svc, which publishes the topic and subscribes to none of it, matches nothing. The subscription is a plain
 * value bean; it is read by name and topic only.
 */
class OnAuthoritySubscription :
    SpringBootCondition(),
    ConfigurationCondition {
    override fun getConfigurationPhase(): ConfigurationCondition.ConfigurationPhase =
        ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN

    override fun getMatchOutcome(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): ConditionOutcome {
        val factory = context.beanFactory ?: return ConditionOutcome.noMatch("no bean factory")
        val subscribed =
            factory
                .getBeanNamesForType(ConsumerSubscription::class.java, true, false)
                .any { factory.getBean(it, ConsumerSubscription::class.java).topic == AUTHORITY_TOPIC }
        return if (subscribed) {
            ConditionOutcome.match("a subscription to $AUTHORITY_TOPIC")
        } else {
            ConditionOutcome.noMatch("no subscription to $AUTHORITY_TOPIC")
        }
    }

    companion object {
        val AUTHORITY_TOPIC: String = requireNotNull(PlatformEventCatalogue.AUTHORITY_CHANGED.topic)
    }
}
