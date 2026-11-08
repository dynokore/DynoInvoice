package com.dynokore.platform.serviceruntime.tracing

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

/**
 * One CLIENT span per outbound call. Attributes are exactly `peer.service`, `http.request.method`,
 * `http.response.status_code` and `server.address`; no URL, query, header or body value is recorded. `peer.service`
 * is the `service` of the peer whose `host` equals the request host by equality, else the host itself.
 */
class ClientSpanInterceptor(
    private val tracer: Tracer,
    peers: List<TracingProperties.Peer>,
) : ClientHttpRequestInterceptor {
    private val services: Map<String, String>

    init {
        require(peers.map { it.host }.toSet().size == peers.size) { "gst.tracing.peers: duplicate host" }
        require(
            peers.all {
                it.host.isNotEmpty() && it.service.isNotEmpty()
            },
        ) { "gst.tracing.peers: host and service are required" }
        services = peers.associate { it.host to it.service }
    }

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val host = request.uri.host.orEmpty()
        val span =
            tracer
                .spanBuilder(request.method.name())
                .setSpanKind(SpanKind.CLIENT)
                .setParent(TraceParents.parentContext())
                .setAttribute(PEER_SERVICE, services[host] ?: host)
                .setAttribute(METHOD, request.method.name())
                .setAttribute(SERVER_ADDRESS, host)
                .startSpan()
        try {
            val ctx = span.spanContext
            request.headers.set(
                TraceContextCaptureFilter.HEADER,
                "00-${ctx.traceId}-${ctx.spanId}-${ctx.traceFlags.asHex()}",
            )
            val response = execution.execute(request, body)
            span.setAttribute(STATUS, response.statusCode.value().toLong())
            return response
        } finally {
            span.end()
        }
    }

    companion object {
        private val PEER_SERVICE = AttributeKey.stringKey("peer.service")
        private val METHOD = AttributeKey.stringKey("http.request.method")
        private val SERVER_ADDRESS = AttributeKey.stringKey("server.address")
        private val STATUS = AttributeKey.longKey("http.response.status_code")
    }
}
