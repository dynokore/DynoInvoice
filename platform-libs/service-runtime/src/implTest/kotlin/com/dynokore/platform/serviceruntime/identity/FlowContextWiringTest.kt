package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.http.client.ClientHttpRequestInterceptor
import java.time.Clock

/** P109: the context is built the way a service's bootstrap builds it, with the keys and tenant context as beans. */
class FlowContextWiringTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdentityAutoConfiguration::class.java))
            .withPropertyValues(
                "service-runtime.workload-identity.audience=canonical-svc",
                "service-runtime.workload-identity.jwk-set-uri=http://localhost:1/jwks",
            ).withBean(FlowContextKeys::class.java, { FlowContextTestSupport.keys() })
            .withBean(TenantContext::class.java, { TenantContextImpl() })
            .withBean(Clock::class.java, { FlowContextTestSupport.CLOCK })

    @Test
    fun `INV-AC-8251 the flow-context filter follows the calling-service filter and the interceptor is a bean`() {
        runner.run { context ->
            context.startupFailure shouldBe null
            val filters = context.getBeansOfType(FilterRegistrationBean::class.java).values
            val order = { type: Class<*> -> filters.single { type.isInstance(it.filter) }.order }
            order(SyncEdgeCallerFilter::class.java) shouldBe -85
            order(FlowContextFilter::class.java) shouldBe -84
            IdentityAutoConfiguration.ORDER_FLOW_CONTEXT shouldBe -84
            context.getBeansOfType(ClientHttpRequestInterceptor::class.java).values.any {
                it is FlowContextPropagationInterceptor
            } shouldBe true
            context.getBean(FlowContextOperations::class.java).serving("POST", "/v1/none") shouldBe null
        }
    }
}
