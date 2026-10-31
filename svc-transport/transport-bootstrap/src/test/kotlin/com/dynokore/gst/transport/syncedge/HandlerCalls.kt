package com.dynokore.gst.transport.syncedge

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The counting double of INV-AC-8455. The stubs have no use case port, so the double sits at the last point before the
 * handler method: the last interceptor of the chain counts each request that is about to enter a handler, under the
 * label `<METHOD> <path template>` of its operation. A refused caller must leave the count unchanged; a request
 * that a caller check let through is counted once.
 */
class HandlerCalls :
    HandlerInterceptor,
    WebMvcConfigurer {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun count(label: String): Int = counts[label]?.get() ?: 0

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(this).order(Ordered.LOWEST_PRECEDENCE)
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String
        if (handler is HandlerMethod && pattern != null) {
            counts.computeIfAbsent("${request.method} $pattern") { AtomicInteger() }.incrementAndGet()
        }
        return true
    }
}
