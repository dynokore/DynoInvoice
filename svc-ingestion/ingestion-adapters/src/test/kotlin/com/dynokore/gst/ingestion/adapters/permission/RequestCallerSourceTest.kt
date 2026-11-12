package com.dynokore.gst.ingestion.adapters.permission

import com.dynokore.gst.ingestion.adapters.http.ApiRefusal
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

/** INV-AC-20953: the caller is the actor of the principal the resolver maps the Authorization header to. */
class RequestCallerSourceTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val resolver =
        EndUserPrincipalResolver { header ->
            if (header == "Bearer good") EndUserPrincipal("identity:P-1", tenant, emptySet()) else null
        }
    private val source = RequestCallerSource(resolver)

    @AfterEach
    fun clear() = RequestContextHolder.resetRequestAttributes()

    private fun request(authorization: String?) {
        val request = MockHttpServletRequest()
        if (authorization != null) request.addHeader("Authorization", authorization)
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    @Test
    fun `INV-AC-20953 a header the resolver maps to a principal gives its actor`() {
        request("Bearer good")
        source.actor() shouldBe "identity:P-1"
    }

    @Test
    fun `INV-AC-20953 a header the resolver maps to null is UNAUTHENTICATED`() {
        request("Bearer bad")
        shouldThrow<ApiRefusal> { source.actor() }.code shouldBe ClosedErrorCode.UNAUTHENTICATED
        request(null)
        shouldThrow<ApiRefusal> { source.actor() }.code shouldBe ClosedErrorCode.UNAUTHENTICATED
    }

    @Test
    fun `INV-AC-20953 no request in the thread is UNAUTHENTICATED`() {
        RequestContextHolder.resetRequestAttributes()
        shouldThrow<ApiRefusal> { source.actor() }.code shouldBe ClosedErrorCode.UNAUTHENTICATED
    }
}
