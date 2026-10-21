package com.dynokore.gst.gatewayauthz.check

/** Reads a path parameter's value from a request path by the route's contract path template (API-26). */
object PathTemplate {
    /** The value of [parameter] in [path] under [template], or null when the path does not match the template. */
    fun parameter(
        template: String,
        path: String,
        parameter: String,
    ): String? {
        val templateSegments = template.split('/')
        val pathSegments = path.substringBefore('?').split('/')
        val matches =
            templateSegments.size == pathSegments.size &&
                templateSegments.zip(pathSegments).all { (expected, actual) ->
                    if (isParameter(expected)) actual.isNotEmpty() else expected == actual
                }
        val index = templateSegments.indexOf("{$parameter}")
        return pathSegments.getOrNull(index).takeIf { matches }
    }

    private fun isParameter(segment: String): Boolean = segment.startsWith('{') && segment.endsWith('}')
}
