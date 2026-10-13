package com.dynokore.platform.serviceruntime.client

import com.dynokore.platform.serviceruntime.resilience.http.DeadlinePropagationInterceptor
import com.dynokore.platform.serviceruntime.resilience.http.EndUserTokenPropagationInterceptor
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.client.ClientHttpRequestInterceptor
import java.time.Clock

/**
 * This task's done-when, "generated RestClient wrapper with deadline
 * propagation": every `RestClient` a service builds from this bean's
 * `RestClient.Builder` already carries every contributed interceptor, with no
 * wiring of its own (TPL-07).
 */
class InternalRestClientAutoConfigurationTest {
    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        object : ObjectProvider<T> {
            override fun getIfAvailable(): T = value
        }

    @Test
    fun `the built RestClient's own request interceptors are exactly the ones contributed`() {
        val deadlineInterceptor = DeadlinePropagationInterceptor(Clock.systemUTC())
        val endUserInterceptor = EndUserTokenPropagationInterceptor()
        val builder =
            InternalRestClientAutoConfiguration()
                .internalRestClientBuilder(providerOf(listOf(deadlineInterceptor, endUserInterceptor)))

        val captured = mutableListOf<ClientHttpRequestInterceptor>()
        builder.requestInterceptors { captured.addAll(it) }.build()

        captured shouldContainExactly listOf(deadlineInterceptor, endUserInterceptor)
    }

    @Test
    fun `prototype-scoped - two calls never share one builder's mutable state`() {
        val configuration = InternalRestClientAutoConfiguration()
        val first = configuration.internalRestClientBuilder(providerOf(emptyList()))
        val second = configuration.internalRestClientBuilder(providerOf(emptyList()))

        first shouldNotBeSameInstanceAs second
    }
}
