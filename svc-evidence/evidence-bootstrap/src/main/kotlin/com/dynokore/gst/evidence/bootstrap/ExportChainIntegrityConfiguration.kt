package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.persistence.JooqExportChainIntegrity
import com.dynokore.gst.evidence.application.export.ExportChainIntegrity
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Binds the export chain-integrity gate (DEP-007). The one bean is the fail-closed database adapter: no conditional,
 * no fallback and no property that switches the gate off.
 */
@Configuration(proxyBeanMethods = false)
class ExportChainIntegrityConfiguration {
    @Bean
    fun exportChainIntegrity(dsl: DSLContext): ExportChainIntegrity = JooqExportChainIntegrity(dsl)
}
