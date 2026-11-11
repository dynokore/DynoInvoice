package com.dynokore.gst.consoleBff.adapters.session

import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Duration

/**
 * Removes the sessions past either timeout. A request never depends on it: the resolver checks both itself.
 *
 * Exception to the rule that an adapter never receives `PlatformTenantBinding`: one `bindPlatform` call and one
 * transaction per sweep, with the actor of [JooqSessionStore.ACTOR]; the table is platform-scoped. This is the owner's
 * widening of INV-Q-2927 D to the session adapters only (owner answer of 2026-11-11 to the answered design question of
 * INV-T-14458, option A).
 */
class SessionSweeper(
    private val store: JooqSessionStore,
    private val binding: PlatformTenantBinding,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val idle: Duration,
    private val absolute: Duration,
) {
    /** Deletes the expired rows and returns how many. */
    fun sweep(): Int =
        binding.bindPlatform(JooqSessionStore.ACTOR) {
            transactions.inTransaction { store.deleteExpired(clock.instant(), idle, absolute) }
        }
}

/** The sweeper over the service's `TenantContext` bean (the composition root names no binding type). */
fun sessionSweeperOf(
    store: JooqSessionStore,
    tenants: com.dynokore.platform.tenancykernel.TenantContext,
    transactions: TransactionRunner,
    clock: Clock,
    idle: Duration,
    absolute: Duration,
): SessionSweeper = SessionSweeper(store, JooqSessionStore.bindingOf(tenants), transactions, clock, idle, absolute)
