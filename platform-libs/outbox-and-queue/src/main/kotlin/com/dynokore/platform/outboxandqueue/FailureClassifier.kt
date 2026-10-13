package com.dynokore.platform.outboxandqueue

import java.io.IOException
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientException
import java.util.concurrent.TimeoutException

/** Whose fault a failed delivery is (INV-Q-3110). */
enum class FailureKind {
    /** The record is the likely cause: each attempt counts toward [ConsumerRetryPolicy.maxAttempts]. */
    RECORD,

    /** A dependency or the database is unavailable: back off up to the maximum delay, the bound is not used. */
    INFRASTRUCTURE,
}

/** Marks an exception as "a dependency is unavailable now" for the default [FailureClassifier]. */
interface DependencyUnavailable

/** Decides the [FailureKind] of a handler failure. Set per consumer through [ConsumerSubscription.classifier]. */
fun interface FailureClassifier {
    fun classify(failure: Throwable): FailureKind

    companion object {
        /**
         * Infrastructure when the failure or any cause is a lost connection or timeout (SQLState class 08, 57P,
         * 40001, 40P01; [SQLTransientException], [SQLRecoverableException], [SQLTimeoutException], [IOException],
         * [TimeoutException]), an [EventBindingUnavailable] or a [DependencyUnavailable]; a record failure otherwise.
         */
        val DEFAULT =
            FailureClassifier { failure ->
                val chain = generateSequence(failure) { it.cause }.take(DEPTH)
                if (chain.any(::isInfrastructure)) FailureKind.INFRASTRUCTURE else FailureKind.RECORD
            }

        private const val DEPTH = 10
        private val STATES = listOf("08", "57P", "40001", "40P01")

        private fun hasTransientState(e: SQLException): Boolean {
            val state = e.sqlState ?: return false
            return STATES.any(state::startsWith)
        }

        private fun isInfrastructure(t: Throwable): Boolean =
            when (t) {
                is DependencyUnavailable, is EventBindingUnavailable, is SQLTransientException,
                is SQLRecoverableException, is SQLTimeoutException, is IOException, is TimeoutException,
                -> true

                is SQLException -> hasTransientState(t)

                else -> false
            }
    }
}
