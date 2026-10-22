package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.UntouchedOperations
import com.dynokore.platform.tenancykernel.TenantContext
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/** The end-user binding filter (INV-T-8198) in front of a real command servlet, for the command tests. */
internal object BindingFront {
    /** No operation is untouched: every command path is an end-user operation. */
    fun filter(
        resolver: EndUserPrincipalResolver,
        tenants: TenantContext,
    ): Filter {
        val beans =
            DefaultListableBeanFactory().apply {
                registerSingleton("resolver", resolver)
                registerSingleton("tenants", tenants)
            }
        return EndUserPrincipalBindingFilter(
            beans.getBeanProvider(EndUserPrincipalResolver::class.java),
            beans.getBeanProvider(TenantContext::class.java),
            UntouchedOperations.of(emptyList()),
        )
    }

    fun serve(
        front: Filter?,
        servlet: HttpServlet,
        request: MockHttpServletRequest,
        response: MockHttpServletResponse,
    ) {
        if (front == null) {
            servlet.service(request, response)
        } else {
            front.doFilter(
                request,
                response,
                FilterChain { req, res -> servlet.service(req as HttpServletRequest, res as HttpServletResponse) },
            )
        }
    }
}
