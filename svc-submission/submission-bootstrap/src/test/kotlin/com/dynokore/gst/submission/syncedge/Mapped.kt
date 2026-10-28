package com.dynokore.gst.submission.syncedge

import org.springframework.context.ApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/** One handler method the application context maps: HTTP method, path pattern and the class that holds it. */
data class MappedHandler(
    val method: String,
    val pattern: String,
    val type: String,
) {
    val label get() = "$method $pattern"
}

object Mapped {
    /** Spring Boot's own error endpoint is not an operation of the contract; every other mapped handler is. */
    private const val ERROR_CONTROLLER = "org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController"

    fun handlers(context: ApplicationContext): List<MappedHandler> =
        context
            .getBean(RequestMappingHandlerMapping::class.java)
            .handlerMethods
            .filterValues { it.beanType.name != ERROR_CONTROLLER }
            .flatMap { (info, handler) ->
                val patterns = info.pathPatternsCondition?.patternValues.orEmpty()
                patterns.flatMap { pattern ->
                    info.methodsCondition.methods.map { MappedHandler(it.name, pattern, handler.beanType.simpleName) }
                }
            }.sortedBy { it.pattern + it.method }
}
