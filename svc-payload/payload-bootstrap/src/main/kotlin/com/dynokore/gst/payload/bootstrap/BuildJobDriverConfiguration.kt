package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.build.BuildPayloadJobHandler
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/** One step of the build job: claims, runs and ends one job; false when nothing was claimable. */
fun interface BuildJobStep {
    fun runOnce(): Boolean
}

/** True only when `payload.build.driver.enabled` equals the text `true` (exact equality; absent or other is false). */
class BuildJobDriverEnabled : Condition {
    override fun matches(
        context: ConditionContext,
        metadata: AnnotatedTypeMetadata,
    ): Boolean = context.environment.getProperty(ENABLED_PROPERTY) == "true"

    companion object {
        const val ENABLED_PROPERTY = "payload.build.driver.enabled"
    }
}

/**
 * The scheduled driver of the claimed build job, off by default (fail closed). The platform claim is exclusive per
 * item, so any number of processes may run it (12-factor VIII). The delay is read from `payload.build.driver.delay`
 * with no default: while enabled and the property is absent the placeholder fails and the context does not start.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(BuildJobDriverEnabled::class)
@EnableScheduling
class BuildJobDriverConfiguration {
    @Bean
    @ConditionalOnMissingBean(BuildJobStep::class)
    fun buildJobStep(handler: BuildPayloadJobHandler): BuildJobStep = BuildJobStep(handler::runOnce)

    @Bean
    fun buildJobDriver(step: BuildJobStep): BuildJobDriver = BuildJobDriver(step)
}

/** Runs [BuildJobStep] until nothing is claimable; the failure of one job ends that tick only. */
class BuildJobDriver(
    private val step: BuildJobStep,
) {
    @Scheduled(fixedDelayString = "\${payload.build.driver.delay}")
    fun tick() {
        try {
            @Suppress("ControlFlowWithEmptyBody")
            while (step.runOnce()) {
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            // the failed job was released by the handler; the next tick claims it again
            LoggerFactory.getLogger(BuildJobDriver::class.java).error("build job failed: {}", failure.message, failure)
        }
    }
}
