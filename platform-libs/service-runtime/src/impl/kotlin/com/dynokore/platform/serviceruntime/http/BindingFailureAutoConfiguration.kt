package com.dynokore.platform.serviceruntime.http

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/** Registers [BindingFailureAdvice] in every servlet service on service-runtime (INV-AC-19580). */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.web.servlet.DispatcherServlet"])
class BindingFailureAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BindingFailureAdvice::class)
    fun bindingFailureAdvice(): BindingFailureAdvice = BindingFailureAdvice()
}
