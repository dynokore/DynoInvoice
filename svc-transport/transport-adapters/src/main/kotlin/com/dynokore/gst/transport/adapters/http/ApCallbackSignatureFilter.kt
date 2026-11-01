package com.dynokore.gst.transport.adapters.http

import com.dynokore.gst.transport.application.AccessPointAdapters
import com.dynokore.gst.transport.domain.SenderVerdict
import com.dynokore.gst.transport.domain.SenderVerifier
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.UUID

/**
 * Sender verification of `POST /v1/ap/{adapterId}/notifications` (INV-REQ-585 behaviour 2, API-27, INV-Q-9668): the
 * `ap-callback` principal is scoped to one adapter, and every adapter requires the signed form (mutual TLS is not
 * implemented here and not claimed). It runs before the body is parsed, over the raw bytes the signature covers, so an
 * unsigned callback is refused before any handler, ledger or outbox is reached. Any failure is 401 SRT-003 with the
 * API-20 envelope and no detail. A verified request carries [VERIFIED_ADAPTER] for the controller, which refuses a
 * request that does not.
 */
class ApCallbackSignatureFilter(
    private val adapters: AccessPointAdapters,
    private val secrets: SecretStore,
    private val verifier: SenderVerifier,
) : Filter {
    private val log = LoggerFactory.getLogger(ApCallbackSignatureFilter::class.java)

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val http = request as HttpServletRequest
        http.removeAttribute(VERIFIED_ADAPTER)
        val adapter = if (http.method == "POST") adapterOf(http.requestURI) else null
        if (adapter == null) {
            chain.doFilter(request, response)
            return
        }
        val body = verifiedBody(http, adapter)
        if (body == null) {
            refuse(response as HttpServletResponse)
            return
        }
        val replay = ReplayableRequest(http, body)
        replay.setAttribute(VERIFIED_ADAPTER, adapter)
        chain.doFilter(replay, response)
    }

    /** The raw body when the sender is verified for [adapter], null otherwise. */
    private fun verifiedBody(
        http: HttpServletRequest,
        adapter: String,
    ): ByteArray? {
        // equality with a configured id (never a prefix): the secret is not read for any other value
        if (adapters.ids().none { it.value == adapter }) return refusedBecause("unknown adapter")
        val timestamp = http.getHeader(TIMESTAMP_HEADER)
        val signature = http.getHeader(SIGNATURE_HEADER)
        if (timestamp == null || signature == null) return refusedBecause("unsigned")
        val secret =
            try {
                secrets.read(SecretRef("$SECRET_PREFIX$adapter")).reveal().toByteArray(Charsets.UTF_8)
            } catch (_: Exception) {
                return refusedBecause("secret unreadable")
            }
        val body = readBounded(http) ?: return refusedBecause("body over the limit")
        return when (val verdict = verifier.verify(body, timestamp, signature, secret)) {
            SenderVerdict.Accepted -> body
            is SenderVerdict.Refused -> refusedBecause(verdict.reason.name)
        }
    }

    private fun refusedBecause(reason: String): ByteArray? {
        log.debug("ap callback refused: {}", reason)
        return null
    }

    private fun readBounded(http: HttpServletRequest): ByteArray? {
        val bytes = http.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        return bytes.takeIf { it.size <= MAX_BODY_BYTES }
    }

    private fun refuse(response: HttpServletResponse) {
        val problem =
            Problems.render(
                ClosedErrorCode.UNAUTHENTICATED,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
                null,
            )
        response.status = problem.status
        problem.headers.forEach { (name, value) -> response.addHeader(name, value) }
        response.writer.write(problem.body)
    }

    /** The adapter segment of exactly `/v1/ap/<adapterId>/notifications`, or null for any other path. */
    private fun adapterOf(uri: String): String? {
        val parts = uri.split('/')
        val matches =
            parts.size == PATH_PARTS && parts[0].isEmpty() && parts[1] == "v1" && parts[2] == "ap" &&
                parts[PATH_ADAPTER].isNotEmpty() && parts[PATH_LAST] == "notifications"
        return if (matches) parts[PATH_ADAPTER] else null
    }

    private class ReplayableRequest(
        request: HttpServletRequest,
        private val body: ByteArray,
    ) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream {
            val source = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read(): Int = source.read()

                override fun isFinished(): Boolean = source.available() == 0

                override fun isReady(): Boolean = true

                override fun setReadListener(listener: ReadListener?) = throw UnsupportedOperationException()
            }
        }

        override fun getReader(): BufferedReader =
            BufferedReader(InputStreamReader(inputStream, characterEncoding ?: Charsets.UTF_8.name()))
    }

    companion object {
        const val TIMESTAMP_HEADER = "X-AP-Timestamp"
        const val SIGNATURE_HEADER = "X-AP-Signature"
        const val VERIFIED_ADAPTER = "transport.ap-callback.verified-adapter"
        const val SECRET_PREFIX = "transport/ap-callback/"
        private const val MAX_BODY_BYTES = 16 * 1024 * 1024
        private const val PATH_PARTS = 5
        private const val PATH_ADAPTER = 3
        private const val PATH_LAST = 4
    }
}
