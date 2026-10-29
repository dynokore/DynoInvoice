package com.dynokore.gst.controlPlane.syncedge

import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** What a handler saw when the request reached it: the operation and the tenant and actor bound at that time. */
data class Reached(
    val operation: String,
    val tenant: String?,
    val actor: String?,
)

/** Counts the calls of the resolver and records every request that reaches a handler (the use case double). */
object Doubles {
    val resolverCalls = AtomicInteger()
    val connections = AtomicInteger()
    val reached = CopyOnWriteArrayList<Reached>()

    fun reset() {
        resolverCalls.set(0)
        connections.set(0)
        reached.clear()
    }
}

/**
 * Test doubles for one service context: a verifier that accepts only the test tokens (`token-<name>` is verified as
 * subject `sa-<name>`), a counting resolver that maps [USER_TOKEN] to tenant `tenant-a` (a UUID: TenantId holds
 * nothing else) and actor `user-a` and refuses every other token, and an interceptor that records each request that
 * reaches a handler. Reaching the handler is the observable use case: the interceptor answers 202 itself and stops
 * the chain, so no controller (built or a 501 stub) runs and nothing is stored. It sits outside the packages the
 * application scans, so only a test that lists it puts it in a context.
 */
@Configuration(proxyBeanMethods = false)
class TestIdentity(
    private val tenants: ObjectProvider<TenantContext>,
) : WebMvcConfigurer {
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

    @Bean
    @Primary
    fun testEndUserResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            Doubles.resolverCalls.incrementAndGet()
            if (authorization == "Bearer $USER_TOKEN") {
                EndUserPrincipal(ACTOR, TenantId.of(TENANT_A), emptySet())
            } else {
                null
            }
        }

    /** Counts the connections the application pool hands out, which is what a refused call must leave unchanged. */
    @Bean
    fun connectionCounter(): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any {
                if (bean !is DataSource) return bean
                return Proxy.newProxyInstance(bean.javaClass.classLoader, arrayOf(DataSource::class.java)) { _, method, args ->
                    if (method.name == "getConnection") Doubles.connections.incrementAndGet()
                    try {
                        method.invoke(bean, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }
        }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(
            object : HandlerInterceptor {
                override fun preHandle(
                    request: HttpServletRequest,
                    response: HttpServletResponse,
                    handler: Any,
                ): Boolean {
                    // an error dispatch (the container's answer to sendError) is not a request that reached the handler
                    if (request.dispatcherType == DispatcherType.ERROR) return true
                    val context = tenants.getObject()
                    val bound = runCatching { context.current().toString() to context.currentActor() }.getOrNull()
                    Doubles.reached += Reached(request.method + " " + request.requestURI, bound?.first, bound?.second)
                    response.status = HttpServletResponse.SC_ACCEPTED
                    return false
                }
            },
        )
    }

    companion object {
        const val USER_TOKEN = "user-a-token"
        const val ACTOR = "user-a"
        const val TENANT_A = "0b8f5f0e-52a4-4d0a-9d4e-3f1f6a1c7e11"
    }
}
