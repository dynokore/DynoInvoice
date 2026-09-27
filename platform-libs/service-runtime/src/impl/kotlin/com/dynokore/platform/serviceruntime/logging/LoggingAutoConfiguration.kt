package com.dynokore.platform.serviceruntime.logging

import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Installs [MdcAllowListTurboFilter] into Logback's [LoggerContext] once per JVM
 * (STK-35, CS-34) — ECS structured logging itself
 * (`logging.structured.format.console: ecs`) is a plain Boot property, set in
 * this module's own defaults resource, not code.
 */
@AutoConfiguration
@ConditionalOnClass(LoggerContext::class)
class LoggingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun mdcAllowListInstaller(): MdcAllowListInstaller = MdcAllowListInstaller()
}

/** Idempotent: installing twice (a test's own context, then the real one) never double-registers the filter. */
class MdcAllowListInstaller {
    init {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext
        val alreadyInstalled = context?.turboFilterList?.any { it is MdcAllowListTurboFilter } ?: true
        if (context != null && !alreadyInstalled) {
            context.addTurboFilter(MdcAllowListTurboFilter())
        }
    }
}
