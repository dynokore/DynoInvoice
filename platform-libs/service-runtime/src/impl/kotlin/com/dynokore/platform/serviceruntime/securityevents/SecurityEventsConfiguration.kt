package com.dynokore.platform.serviceruntime.securityevents

import com.dynokore.platform.auditlog.AuditStager
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.serviceruntime.binding.TelemetryExport
import com.dynokore.platform.serviceruntime.composition.OperatorSessionAuditWriter
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource

/**
 * The shared security-event path (INV-REQ-1384, INV-Q-10945 option A): the emitter, the mapping, and the decorator
 * that makes an audit entry recorded in a transaction raise its record after that transaction commits. The emitter
 * needs a [TelemetryExport] bean (`gst.bindings.telemetry-export`); without one the context does not start (INV-Q-11058).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityEventProperties::class)
@PropertySource("classpath:service-runtime-security-events-defaults.properties")
class SecurityEventsConfiguration {
    @Bean
    @ConditionalOnMissingBean(SecurityEventEmitter::class)
    fun securityEventEmitter(
        properties: SecurityEventProperties,
        export: ObjectProvider<TelemetryExport>,
        meters: ObjectProvider<MeterRegistry>,
    ): OtlpSecurityEventEmitter {
        val bound =
            checkNotNull(export.getIfAvailable()) {
                "No TelemetryExport bean: security events are exported through it. Set gst.bindings.telemetry-export " +
                    "or provide a TelemetryExport bean (INV-REQ-1384)."
            }
        return OtlpSecurityEventEmitter(properties, { bound }, meters.getIfAvailable())
    }

    companion object {
        /** Static, so it is registered before any [AuditWriter] bean is created. */
        @Bean
        @JvmStatic
        fun securityEventPostProcessor(): SecurityEventPostProcessor = SecurityEventPostProcessor()
    }
}

/**
 * Decorates every [AuditWriter] with [SecurityEventAuditWriter]. The [TransactionRunner] bean is not touched: it keeps
 * its class and no proxy is made (INV-Q-11056).
 */
class SecurityEventPostProcessor :
    BeanPostProcessor,
    BeanFactoryAware {
    private lateinit var beans: BeanFactory

    override fun setBeanFactory(beanFactory: BeanFactory) {
        beans = beanFactory
    }

    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any {
        val properties = { beans.getBean(SecurityEventProperties::class.java) }
        val emitter = { beans.getBean(SecurityEventEmitter::class.java) }
        val transactions = { beans.getBean(TransactionRunner::class.java) }
        return when {
            decoratedAlready(bean) -> {
                bean
            }

            bean is AuditStager && bean is AuditWriter -> {
                SecurityEventAuditStager(
                    bean,
                    properties,
                    emitter,
                    transactions,
                )
            }

            bean is AuditWriter -> {
                SecurityEventAuditWriter(bean, properties, emitter, transactions)
            }

            else -> {
                bean
            }
        }
    }

    /** True when [bean] is, or wraps through an operator-session decorator, a [SecurityEventAuditWriter]. */
    private fun decoratedAlready(bean: Any): Boolean =
        when (bean) {
            is SecurityEventAuditWriter -> true
            is OperatorSessionAuditWriter -> decoratedAlready(bean.delegate)
            else -> false
        }
}
