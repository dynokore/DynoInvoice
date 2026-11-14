package com.dynokore.gst.rules.bootstrap.egress

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test-side fixture of svc-rules (INV-REQ-1081, INV-AC-2077): while installed it records every connection attempt of
 * the test JVM made through `java.net.Socket` and NIO `SocketChannel` (the test-only Java agent sees them before the
 * connect proceeds). A destination is a violation when it is not equal to an allowlisted entry; nothing is matched by
 * prefix or pattern. Fail closed: with the agent not loaded [install] fails, so no test that installs the guard can
 * pass or be skipped without the agent.
 */
class EgressGuard private constructor(
    private val allowed: List<Allowed>,
) : AutoCloseable {
    /** One allowlisted destination: the exact resolved address text and, when [port] is set, exactly that port. */
    data class Allowed(
        val host: String,
        val port: Int? = null,
    ) {
        fun admits(destination: Destination): Boolean =
            destination.host == host && (port == null || destination.port == port)
    }

    private val closed = AtomicBoolean(false)

    /** Every destination recorded since the guard was installed, in order (also the allowlisted ones). */
    fun recorded(): List<Destination> = EgressRecorder.recorded()

    /** The recorded destinations that no allowlisted entry admits. */
    fun violations(): List<Destination> = recorded().filter { destination -> allowed.none { it.admits(destination) } }

    fun assertNoViolations() {
        val found = violations()
        if (found.isNotEmpty()) throw AssertionError("outbound connection attempts outside the allowlist: $found")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            EgressRecorder.stop()
            installed.set(false)
        }
    }

    companion object {
        /** The loopback address: any port of exactly 127.0.0.1 (`127.0.0.2` is not equal and is a violation). */
        val LOOPBACK = Allowed("127.0.0.1")

        private val installed = AtomicBoolean(false)

        fun install(vararg extra: Allowed): EgressGuard {
            if (!EgressRecorder.agentLoaded) {
                throw AssertionError(
                    "the egress agent is not loaded: start the test JVM with the rules-bootstrap agent jar",
                )
            }
            check(installed.compareAndSet(false, true)) { "an egress guard is already installed" }
            EgressRecorder.start()
            return EgressGuard(listOf(LOOPBACK) + extra)
        }
    }
}
