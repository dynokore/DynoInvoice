package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The source of a platform operator's roles (regulatory.author, regulatory.approver) is an open question (the report of
 * INV-T-4984, gap G6). Until it is ruled this binding fails closed: nobody holds a role, so every operation
 * answers 403.
 */
@Configuration(proxyBeanMethods = false)
class OperatorRolesConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun operatorRoles(): OperatorRoles = OperatorRoles { _, _ -> false }
}
