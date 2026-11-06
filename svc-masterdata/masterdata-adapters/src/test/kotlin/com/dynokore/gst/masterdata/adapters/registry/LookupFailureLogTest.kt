package com.dynokore.gst.masterdata.adapters.registry

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.platform.adapterruntime.BulkheadPolicy
import com.dynokore.platform.adapterruntime.CircuitBreakerPolicy
import com.dynokore.platform.adapterruntime.ResiliencePolicy
import com.dynokore.platform.adapterruntime.RetryPolicy
import com.dynokore.platform.adapterruntime.TimeoutPolicy
import com.dynokore.platform.adapterruntime.deadletter.InMemoryDeadLetterSink
import com.dynokore.platform.adapterruntime.resilience4j.Resilience4jResilientExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect
import java.net.http.HttpClient.Version
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/** A transport whose connect failure carries the queried value in its message, as a JDK message may. */
private class FailingClient(
    private val message: String,
) : HttpClient() {
    override fun <T : Any?> send(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> = throw java.net.ConnectException(message)

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.empty()

    override fun followRedirects(): Redirect = Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): Version = Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(IOException(message))

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(IOException(message))
}

/** INV-Q-17121 A: a failed lookup logs its message key and exception class, never the value or the exception text. */
class LookupFailureLogTest {
    private val value = "0195:sguen201912345a"

    private fun wire(): RegistryHttp {
        val policy =
            ResiliencePolicy(
                TimeoutPolicy(Duration.ofSeconds(1)),
                RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(2), 0.0),
                CircuitBreakerPolicy(50.0F, 20, Duration.ofSeconds(1), 3),
                BulkheadPolicy(20, Duration.ofMillis(100)),
            )
        return RegistryHttp(
            URI.create("http://registry.test:8080/api"),
            Resilience4jResilientExecutor(policy, "log-test", InMemoryDeadLetterSink(), Clock.systemUTC()),
            FailingClient("Connection refused for $value"),
        )
    }

    private fun warnings(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger("com.dynokore.gst.masterdata.adapters.registry") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level.toString() == "WARN" }
    }

    @Test
    fun `a failed directory lookup logs MDM-008 and the exception class only`() {
        val events = warnings { HttpDirectoryClient(wire()).lookup(value) }
        assertEquals(listOf("directory lookup failed: MDM-008 ConnectException"), events.map { it.formattedMessage })
        assertFalse(events.single().formattedMessage.contains(value))
    }

    @Test
    fun `a failed register lookup logs REGISTER_UNAVAILABLE and the exception class only`() {
        val events = warnings { HttpRegisterClient(wire()).lookup(value) }
        assertEquals(
            listOf("register lookup failed: REGISTER_UNAVAILABLE ConnectException"),
            events.map { it.formattedMessage },
        )
        assertFalse(events.single().formattedMessage.contains(value))
    }
}
