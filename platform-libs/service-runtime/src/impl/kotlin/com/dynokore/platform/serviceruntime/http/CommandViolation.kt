package com.dynokore.platform.serviceruntime.http

/** A request refused with its closed error [code]; [raise] throws one. */
internal class CommandViolation(
    message: String,
    val code: ClosedErrorCode = ClosedErrorCode.CONTRACT_VIOLATION,
) : RuntimeException(message) {
    companion object {
        fun raise(
            message: String,
            code: ClosedErrorCode = ClosedErrorCode.CONTRACT_VIOLATION,
        ): Nothing = throw CommandViolation(message, code)
    }
}
