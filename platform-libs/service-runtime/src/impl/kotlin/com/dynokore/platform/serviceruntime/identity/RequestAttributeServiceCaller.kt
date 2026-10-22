package com.dynokore.platform.serviceruntime.identity

import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder

/** [ServiceCaller] over the request attribute set by [WorkloadTokenAuthenticationFilter]; keeps no state of its own. */
class RequestAttributeServiceCaller : ServiceCaller {
    override fun current(): String? =
        RequestContextHolder
            .getRequestAttributes()
            ?.getAttribute(ServiceCaller.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) as? String
}
