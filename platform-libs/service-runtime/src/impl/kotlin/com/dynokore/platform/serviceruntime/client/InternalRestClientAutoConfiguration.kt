package com.dynokore.platform.serviceruntime.client

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Scope
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.web.client.RestClient

/**
 * The `RestClient.Builder` a service builds every generated ARC-08 client from
 * (TPL-07: `TransportInternalApi(builder.baseUrl(url).build())` in that
 * service's own `@Configuration`) — pre-wired with every
 * [ClientHttpRequestInterceptor] bean this module (or the service itself)
 * contributes: deadline propagation (API-29), the workload token
 * (INV-REQ-1202 behaviour #1) and the propagated end-user token (INV-REQ-1202
 * behaviour #2). A generated client never needs its own interceptor wiring —
 * this is what "generated RestClient wrapper with deadline propagation"
 * (this task's done-when) means in practice: every client a service builds
 * from this bean already carries all three, with no code of its own.
 *
 * Prototype-scoped, the same as Boot's own auto-configured `RestClient.Builder`
 * (`RestClientAutoConfiguration`): each generated client calls `.baseUrl(...)`
 * on its own copy, never sharing base URL/state with another edge's client.
 */
@AutoConfiguration
class InternalRestClientAutoConfiguration {
    @Bean
    @Scope(BeanDefinition.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean(name = ["internalRestClientBuilder"])
    fun internalRestClientBuilder(interceptors: ObjectProvider<List<ClientHttpRequestInterceptor>>): RestClient.Builder = RestClient.builder().requestInterceptors { it.addAll(interceptors.getIfAvailable(::emptyList)) }
}
