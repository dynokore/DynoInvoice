package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.application.RouteUnitOfWork
import com.dynokore.platform.tenancykernel.TransactionRunner

/** The unit of work of a route change: the route store and the outbox row share one transaction of the bound tenant. */
class JooqRouteUnitOfWork(
    private val runner: TransactionRunner,
) : RouteUnitOfWork {
    override fun <T> run(block: () -> T): T = runner.inTransaction(block)
}
