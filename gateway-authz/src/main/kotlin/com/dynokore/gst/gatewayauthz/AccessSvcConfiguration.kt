package com.dynokore.gst.gatewayauthz

import com.dynokore.gst.gatewayauthz.refresh.AccessSvcClient
import com.dynokore.gst.gatewayauthz.refresh.KeyMaterialSource
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenPropagationInterceptor
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient

/** The api-gateway -> access-svc edge (INV-API-4001), imported by [GatewayAuthzConfiguration]. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayAuthzProperties::class)
class AccessSvcConfiguration {
    /**
     * The access-svc client (INV-API-4001): the pod's projected service-account token as X-Workload-Token
     * (x-principal workload, owner ruling INV-Q-4006 A; read fresh from the mounted file on every call, STK-26) and
     * service-runtime's other [ClientHttpRequestInterceptor] beans (the deadline, API-29). The token interceptor is
     * built here from the [WorkloadTokenSource] bean because service-runtime 10.3.1 registers its own only when no
     * other interceptor bean exists, and its deadline interceptor always does. Present when the base URL is set.
     */
    @Bean
    @ConditionalOnProperty(prefix = "gateway-authz.access-svc", name = ["base-url"])
    fun accessSvcClient(
        tokenSource: WorkloadTokenSource,
        interceptors: ObjectProvider<ClientHttpRequestInterceptor>,
        properties: GatewayAuthzProperties,
    ): KeyMaterialSource {
        val timeout =
            properties.accessSvc.timeout
                .toMillis()
                .toInt()
        val factory =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(timeout)
                setReadTimeout(timeout)
            }
        val others = interceptors.orderedStream().filter { it !is WorkloadTokenPropagationInterceptor }.toList()
        return AccessSvcClient(
            RestClient
                .builder()
                .baseUrl(properties.accessSvc.baseUrl)
                .requestFactory(factory)
                .requestInterceptors { it.addAll(listOf(WorkloadTokenPropagationInterceptor(tokenSource)) + others) }
                .build(),
        )
    }
}
