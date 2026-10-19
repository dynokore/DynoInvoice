package com.dynokore.platform.serviceruntime.http

/** One stage of a command request: carry on with a value, or stop with the answer to send. */
internal sealed interface Step<out T> {
    class Go<out T>(
        val value: T,
    ) : Step<T>

    class Stop(
        val response: StoredResponse,
    ) : Step<Nothing>
}

internal inline fun <T, R> Step<T>.andThen(next: (T) -> Step<R>): Step<R> =
    when (this) {
        is Step.Go -> next(value)
        is Step.Stop -> this
    }
