package com.dynokore.gst.rules.bootstrap.egress

import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.none
import net.bytebuddy.matcher.ElementMatchers.takesArgument
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import java.lang.instrument.Instrumentation
import java.net.InetAddress
import java.net.SocketAddress
import java.util.function.BiConsumer

private const val SINK = EgressRecorder.SINK_KEY

/**
 * Test-only Java agent (`-javaagent`, built into a small jar by `rules-bootstrap/build.gradle.kts`; INV-REQ-1081). It
 * advises, before the connect proceeds, the connect methods of the JDK classes behind `java.net.Socket` and the NIO
 * `SocketChannel` (blocking and non-blocking; `java.net.http.HttpClient` connects through the latter):
 *  - `sun.nio.ch.NioSocketImpl.connect(SocketAddress, int)`, `connect(String, int)`, `connect(InetAddress, int)`
 *  - `sun.nio.ch.SocketChannelImpl.connect(SocketAddress)` (blocking and non-blocking) and
 *    `blockingConnect(SocketAddress, long)` (the `Socket` adaptor of a channel)
 * of the JDK 25 of the build. Only JDK types appear in the advice bodies, which are inlined into `java.base`.
 */
object EgressAgent {
    @JvmStatic
    fun premain(
        args: String?,
        inst: Instrumentation,
    ) {
        AgentBuilder
            .Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
            .ignore(none<TypeDescription>())
            .type(named<TypeDescription>("sun.nio.ch.NioSocketImpl"))
            .transform { builder, _, _, _, _ ->
                builder
                    .visit(
                        Advice
                            .to(AddressAdvice::class.java)
                            .on(named<MethodDescription>("connect").and(takesArgument(0, SocketAddress::class.java))),
                    ).visit(
                        Advice
                            .to(TargetAdvice::class.java)
                            .on(
                                named<MethodDescription>("connect")
                                    .and(takesArguments(String::class.java, Int::class.javaPrimitiveType))
                                    .or(
                                        named<MethodDescription>("connect")
                                            .and(takesArguments(InetAddress::class.java, Int::class.javaPrimitiveType)),
                                    ),
                            ),
                    )
            }.type(named<TypeDescription>("sun.nio.ch.SocketChannelImpl"))
            .transform { builder, _, _, _, _ ->
                builder.visit(
                    Advice
                        .to(AddressAdvice::class.java)
                        .on(
                            named<MethodDescription>("connect")
                                .and(takesArguments(SocketAddress::class.java))
                                .or(
                                    named<MethodDescription>("blockingConnect")
                                        .and(takesArgument(0, SocketAddress::class.java)),
                                ),
                        ),
                )
            }.installOn(inst)
        System.getProperties()[EgressRecorder.AGENT_KEY] = "loaded"
    }
}

/** Reports the address argument of a connect, before the connect proceeds. */
object AddressAdvice {
    @JvmStatic
    @Advice.OnMethodEnter(suppress = Throwable::class)
    @Suppress("UNCHECKED_CAST")
    fun enter(
        @Advice.Argument(0) address: SocketAddress?,
    ) {
        val sink = System.getProperties()[SINK]
        if (sink is BiConsumer<*, *>) (sink as BiConsumer<Any?, Any?>).accept(address, null)
    }
}

/** Reports the host (text or address) and port arguments of a connect, before the connect proceeds. */
object TargetAdvice {
    @JvmStatic
    @Advice.OnMethodEnter(suppress = Throwable::class)
    @Suppress("UNCHECKED_CAST")
    fun enter(
        @Advice.Argument(0) target: Any?,
        @Advice.Argument(1) port: Int,
    ) {
        val sink = System.getProperties()[SINK]
        if (sink is BiConsumer<*, *>) (sink as BiConsumer<Any?, Any?>).accept(target, port)
    }
}
