package com.dynokore.gst.aggregation.syncedge

import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.FlowContextKeys
import com.dynokore.platform.serviceruntime.identity.FlowContextTestSupport
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.concurrent.atomic.AtomicInteger

/** The double of the handler boundary: counts the requests that reach a mapped handler method (past every filter). */
class HandlerCalls {
    private val counter = AtomicInteger()

    fun count(): Int = counter.get()

    fun reached() {
        counter.incrementAndGet()
    }
}

/**
 * A verifier that accepts only the test tokens (`token-<name>` is verified as subject `sa-<name>`) and a resolver
 * for the end-user token. It sits outside the packages the application scans, so only the test of the caller check
 * puts it in a context; it is primary because the runtime's own beans are defined first by the composition root.
 */
@Configuration(proxyBeanMethods = false)
class TestWorkloadVerifier {
    @Bean
    @Primary
    fun testWorkloadTokenVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token ->
            val value = token?.value
            if (value != null && value.startsWith("token-")) {
                WorkloadVerification.Verified("sa-" + value.removePrefix("token-"))
            } else {
                WorkloadVerification.Refused("invalid")
            }
        }

    /** Any `Authorization` header is the end user of one tenant, so a request past the caller check is served. */
    @Bean
    @Primary
    fun testEndUserResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            authorization?.let { EndUserPrincipal("end-user-1", TenantId.of(TENANT), emptySet()) }
        }

    /** The keys the flow-context filter verifies with: the platform test fixture, never a copy of it. */
    @Bean
    fun testFlowContextKeys(): FlowContextKeys = FlowContextTestSupport.keys()

    @Bean
    fun handlerCalls(): HandlerCalls = HandlerCalls()

    /** Counts each request that a handler method receives, so a refused caller is shown not to reach it. */
    @Bean
    fun handlerCallCounter(calls: HandlerCalls): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry.addInterceptor(
                    object : HandlerInterceptor {
                        override fun preHandle(
                            request: HttpServletRequest,
                            response: HttpServletResponse,
                            handler: Any,
                        ): Boolean {
                            calls.reached()
                            return true
                        }
                    },
                )
            }
        }

    internal companion object {
        const val TENANT = "0b8f5f0e-52a4-4d0a-9d4e-3f1f6a1c7e11"
    }
}
