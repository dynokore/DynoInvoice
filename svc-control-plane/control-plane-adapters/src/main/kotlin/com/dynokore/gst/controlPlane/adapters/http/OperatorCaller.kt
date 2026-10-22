package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

/** The platform operator of a request: a verified token with no tenant (INV-REQ-1202 b2), and its correlation id. */
internal class OperatorCaller(
    val principal: EndUserPrincipal,
    val correlationId: String,
) {
    companion object {
        fun of(principals: EndUserPrincipalResolver): OperatorCaller {
            val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
            val correlationId =
                request.getHeader("X-Correlation-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val principal =
                principals.resolve(request.getHeader("Authorization"))
                    ?: problem(ClosedErrorCode.UNAUTHENTICATED, correlationId)
            if (principal.tenant != null) {
                problem(ClosedErrorCode.ACCESS_DENIED, correlationId, "A platform operator token is required")
            }
            return OperatorCaller(principal, correlationId)
        }
    }
}

/** A request value the domain refuses (a malformed reference, a too long field) is a contract violation. */
internal fun <T> OperatorCaller.commandOf(build: () -> T): T =
    try {
        build()
    } catch (invalid: IllegalArgumentException) {
        problem(ClosedErrorCode.CONTRACT_VIOLATION, correlationId, invalid.message)
    }
