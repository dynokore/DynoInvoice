package com.dynokore.gst.rules.bootstrap.egress

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.BiConsumer

/** One connection attempt: the resolved address (or the unresolved text) and the port; port -1 when there is none. */
data class Destination(
    val host: String,
    val port: Int,
) {
    override fun toString(): String = "$host:$port"
}

/**
 * The sink the test-only Java agent ([EgressAgent]) feeds from the connect methods of the JDK socket implementations.
 * The advice inlined into the JDK classes finds the sink in the system properties (a JDK-only channel: no class of the
 * test is visible from `java.base`), and only while a guard is installed. Fails closed through [agentLoaded].
 */
object EgressRecorder : BiConsumer<Any?, Any?> {
    const val AGENT_KEY = "gst.egress.agent"
    const val SINK_KEY = "gst.egress.sink"
    private const val NO_PORT = -1

    @Volatile private var active = false
    private val seen = ConcurrentLinkedQueue<Destination>()

    /** True when the JVM was started with the agent jar, which marks the system properties in `premain`. */
    val agentLoaded: Boolean get() = System.getProperties()[AGENT_KEY] == "loaded"

    fun start() {
        seen.clear()
        active = true
        System.getProperties()[SINK_KEY] = this
    }

    fun stop() {
        active = false
        System.getProperties().remove(SINK_KEY)
    }

    fun recorded(): List<Destination> = seen.toList()

    override fun accept(
        target: Any?,
        port: Any?,
    ) {
        if (active) seen.add(describe(target, port))
    }

    private fun describe(
        target: Any?,
        port: Any?,
    ): Destination =
        when (target) {
            is InetSocketAddress -> {
                val resolved = target.address
                if (resolved != null) {
                    Destination(resolved.hostAddress, target.port)
                } else {
                    Destination("unresolved:" + target.hostString, target.port)
                }
            }

            is InetAddress -> {
                Destination(target.hostAddress, (port as? Int) ?: NO_PORT)
            }

            is String -> {
                Destination("unresolved:$target", (port as? Int) ?: NO_PORT)
            }

            is SocketAddress -> {
                Destination("other:" + target.javaClass.name + ":" + target, NO_PORT)
            }

            else -> {
                Destination("other:" + target?.javaClass?.name, NO_PORT)
            }
        }
}
