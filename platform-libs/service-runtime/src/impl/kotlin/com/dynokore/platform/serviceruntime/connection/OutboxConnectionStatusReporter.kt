package com.dynokore.platform.serviceruntime.connection

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.jobs.JobPayloads
import java.util.UUID

/**
 * [ConnectionStatusReporter] over the service's outbox (INV-REQ-836 b3-b4, INV-Q-2983). The state is this
 * instance's own: the outbox stamps the instance id in the envelope, and consumers fold the instances of a
 * connection to the worst state. A connection's state starts as the circuit breaker's initial state, closed
 * (up), so an instance publishes only when its breaker leaves or returns to that state. The event is written
 * under the connection's own tenant and entity, or the platform tenant for an installation connection
 * (INV-REQ-1226 b7). If the write fails the state is not taken, so the next report tries again.
 */
class OutboxConnectionStatusReporter(
    private val scope: TenantScope,
    private val outbox: OutboxWriter,
) : ConnectionStatusReporter {
    private val states = HashMap<Pair<String, String>, ConnectionState>()

    override fun report(
        connection: ConnectionRecord,
        breaker: CircuitBreakerState,
        lastFailure: ConnectionFailure?,
    ): Boolean {
        val key = connection.ownerService to connection.name
        val state = ConnectionState.of(breaker)
        synchronized(states) {
            if ((states[key] ?: ConnectionState.UP) == state) return false
            val correlation = CorrelationId("connection-status:${UUID.randomUUID()}")
            scope.bind(connection.tenant) {
                scope.transactions.inTransaction {
                    outbox.append(
                        listOf(
                            OutboundEvent(
                                JobPayloads.CONNECTION_STATUS_CHANGED,
                                JobPayloads.connectionStatusChanged(connection.name, state, lastFailure),
                            ),
                        ),
                        EventContext(
                            tenant = connection.tenant,
                            entity = connection.entity,
                            docUuid = null,
                            transactionDate = null,
                            aggregateKey = "${connection.ownerService}/connection/${connection.name}",
                            aggregateVersion = null,
                            correlation = correlation,
                            causation = CausationId(correlation.value),
                        ),
                    )
                }
            }
            states[key] = state
            return true
        }
    }
}
