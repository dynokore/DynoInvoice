package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.http.UriSizeValidator
import jakarta.validation.constraints.Size
import org.hibernate.validator.HibernateValidatorConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean

/**
 * TPL-18: the bean validator of the request bodies. It adds [UriSizeValidator] to the built-in `@Size`, so that the
 * `maxLength` the contract puts on a `format: uri` property (the connection `endpoint`) is enforced instead of failing
 * every request with an unexpected-type error.
 */
@Configuration(proxyBeanMethods = false)
class ValidationConfiguration {
    @Bean
    fun defaultValidator(): LocalValidatorFactoryBean =
        LocalValidatorFactoryBean().apply {
            setConfigurationInitializer { configuration ->
                val hibernate = configuration as HibernateValidatorConfiguration
                val mapping = hibernate.createConstraintMapping()
                mapping
                    .constraintDefinition(Size::class.java)
                    .includeExistingValidators(true)
                    .validatedBy(UriSizeValidator::class.java)
                hibernate.addMapping(mapping)
            }
        }
}
