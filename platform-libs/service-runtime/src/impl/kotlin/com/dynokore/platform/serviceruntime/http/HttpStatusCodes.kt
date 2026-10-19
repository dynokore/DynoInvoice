package com.dynokore.platform.serviceruntime.http

/** The HTTP status codes of the problem and policy error catalogues, named once. */
internal object HttpStatusCodes {
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val METHOD_NOT_ALLOWED = 405
    const val CONFLICT = 409
    const val UNPROCESSABLE = 422
    const val TOO_MANY_REQUESTS = 429
    const val INTERNAL_ERROR = 500
    const val UNAVAILABLE = 503
}
