package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Proxy

private val STUB_TENANT = TenantId.of("0a000000-0000-0000-0000-00000000000a")

/** Drives [EndUserPrincipalBindingFilter] with a stub request that already holds the principal attribute. */
class EndUserPrincipalAttributeTest {
    private val stub = EndUserPrincipal("stub", STUB_TENANT, setOf(EntityRef("E-002")))
    private val resolved = EndUserPrincipal("user-1", STUB_TENANT, setOf(EntityRef("E-001")))

    private class Outcome(
        val status: Int?,
        val chainCalls: Int,
        val seen: Any?,
    )

    private fun run(
        method: String,
        path: String,
        token: String?,
        untouched: UntouchedOperations = UntouchedOperations.of(listOf("GET" to "/internal/v1/open")),
    ): Outcome {
        val attributes = mutableMapOf<String, Any?>(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE to stub)
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, m, args ->
                when (m.name) {
                    "getMethod" -> method
                    "getRequestURI" -> path
                    "getContextPath" -> ""
                    "getHeader" -> token
                    "getAttribute" -> attributes[args[0] as String]
                    "setAttribute" -> attributes.put(args[0] as String, args[1])
                    "removeAttribute" -> attributes.remove(args[0] as String)
                    else -> error("unexpected ${m.name}")
                }
            } as HttpServletRequest
        var status: Int? = null
        val response =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletResponse::class.java)) { _, m, args ->
                when (m.name) {
                    "setStatus" -> status = args[0] as Int
                    "getWriter" -> PrintWriter(StringWriter())
                    else -> null
                }
            } as HttpServletResponse
        var calls = 0
        var seen: Any? = null
        val chain =
            FilterChain { req, _ ->
                calls++
                seen = (req as HttpServletRequest).getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE)
            }
        val factory = DefaultListableBeanFactory()
        factory.registerSingleton(
            "resolver",
            EndUserPrincipalResolver { header -> if (header == "Bearer ok") resolved else null },
        )
        factory.registerSingleton("tenants", TenantContextImpl())
        val resolvers: ObjectProvider<EndUserPrincipalResolver> =
            factory.getBeanProvider(EndUserPrincipalResolver::class.java)
        val tenants: ObjectProvider<TenantContext> = factory.getBeanProvider(TenantContext::class.java)
        EndUserPrincipalBindingFilter(resolvers, tenants, untouched).doFilter(request, response, chain)
        return Outcome(status, calls, seen)
    }

    @Test
    fun `INV-AC-8515 a token that resolves replaces a principal already on the request`() {
        val outcome = run("POST", "/internal/v1/a/7", "Bearer ok")
        outcome.chainCalls shouldBe 1
        outcome.seen!! shouldBeSameInstanceAs resolved
    }

    @Test
    fun `INV-AC-8515 a refused token and a missing token never leave the stub principal to the chain`() {
        for (token in listOf("Bearer unknown", null)) {
            val outcome = run("POST", "/internal/v1/a/7", token)
            outcome.status shouldBe 401
            outcome.chainCalls shouldBe 0
        }
    }

    @Test
    fun `INV-AC-8515 an untouched operation reaches the chain with no principal attribute`() {
        val outcome = run("GET", "/internal/v1/open", "Bearer ok")
        outcome.chainCalls shouldBe 1
        outcome.seen shouldBe null
    }
}
