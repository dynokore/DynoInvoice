package com.dynokore.gst.transport.adapters.http

import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** The `Authorization` header of the request being served: the propagated end-user token (API-25), null if none. */
fun interface CallerToken {
    fun authorization(): String?
}

@Component
class RequestCallerToken : CallerToken {
    override fun authorization(): String? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.getHeader("Authorization")
}
