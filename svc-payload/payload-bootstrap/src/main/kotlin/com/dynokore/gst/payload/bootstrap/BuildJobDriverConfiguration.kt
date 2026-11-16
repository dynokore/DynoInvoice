package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.build.BuildJobFailed
import com.dynokore.gst.payload.adapters.build.BuildPayloadJobHandler
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import java.time.Duration

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
 * item, so any number of processes may run it (12-factor VIII).
 */
@Configuration(proxyBeanMethods = false)
@Conditional(BuildJobDriverEnabled::class)
@EnableScheduling
class BuildJobDriverConfiguration {
    @Bean
    @ConditionalOnMissingBean(BuildJobStep::class)
    fun buildJobStep(handler: BuildPayloadJobHandler): BuildJobStep = BuildJobStep(handler::runOnce)

    @Bean
    fun buildJobDriver(
        step: BuildJobStep,
        environment: Environment,
    ): BuildJobDriver = BuildJobDriver(step, environment)
}

/**
 * Runs [BuildJobStep] until nothing is claimable; the failure of one job ends that tick only. The delay is read from
 * `payload.build.driver.delay` with no default: while enabled and the property is absent the context does not start.
 * The fixed-delay task is registered in code because the raw-SQL placement gate refuses string attributes of
 * `@Scheduled`.
 */
class BuildJobDriver(
    private val step: BuildJobStep,
    private val environment: Environment,
) : SchedulingConfigurer {
    override fun configureTasks(registrar: ScheduledTaskRegistrar) {
        val delay =
            Binder
                .get(environment)
                .bind(DELAY_PROPERTY, Duration::class.java)
                .orElseThrow {
                    IllegalStateException("$DELAY_PROPERTY is required while the build job driver is enabled")
                }
        registrar.addFixedDelayTask(::tick, delay)
    }

    fun tick() {
        try {
            var more = true
            while (more) more = step.runOnce()
        } catch (failure: BuildJobFailed) {
            // the failed job was released by the handler; the next tick claims it again
            logger()
                .addKeyValue(
                    "docUuid",
                    failure.docUuid,
                ).setCause(failure)
                .log("build job failed: {}", failure.message)
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            logger().setCause(failure).log("build job failed: {}", failure.message)
        }
    }

    private fun logger() = LoggerFactory.getLogger(BuildJobDriver::class.java).atError()

    companion object {
        const val DELAY_PROPERTY = "payload.build.driver.delay"
    }
}
