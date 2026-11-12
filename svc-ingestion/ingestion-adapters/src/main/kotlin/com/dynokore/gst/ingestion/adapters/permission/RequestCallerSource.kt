package com.dynokore.gst.ingestion.adapters.permission

import com.dynokore.gst.ingestion.adapters.http.ApiRefusal
import com.dynokore.gst.ingestion.application.permission.CallerSource
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** The caller of the request in hand: the `sub` of the verified end-user token (API-24); no token is 401 SRT-003. */
class RequestCallerSource(
    private val principals: EndUserPrincipalResolver,
) : CallerSource {
    override fun actor(): String {
        val request =
            (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
                ?: throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)
        val principal =
            principals.resolve(request.getHeader("Authorization"))
                ?: throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)
        return principal.actor
    }
}
