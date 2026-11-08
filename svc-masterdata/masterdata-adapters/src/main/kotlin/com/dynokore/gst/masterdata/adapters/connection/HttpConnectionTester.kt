package com.dynokore.gst.masterdata.adapters.connection

import com.dynokore.gst.masterdata.application.connection.ConnectionTester
import com.dynokore.gst.masterdata.domain.connection.ConnectionRecord
import com.dynokore.gst.masterdata.domain.connection.ConnectionTestResult
import com.dynokore.gst.masterdata.domain.connection.TestClass
import com.dynokore.platform.adapterruntime.ResilientExecutor
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * INV-REQ-835: runs the test of one record against its endpoint with the referenced credential. Only GET is sent, with
 * the credential as a bearer token read through the secret store at every call (STK-29); the call goes through the
 * adapter runtime's [ResilientExecutor] (deadline, bounded retry, circuit breaker). The answer is always a classified
 * [ConnectionTestResult], never a raw error: a credential reference that names no secret, or a 401 or 403 answer, is
 * AUTHENTICATION_REFUSED; a 2xx answer SUCCESS; any other status UNEXPECTED_RESPONSE; a network, DNS, timeout or open
 * breaker failure UNREACHABLE. The detail is a code; neither the secret nor any error text is ever returned or logged.
 */
class HttpConnectionTester(
    private val executor: ResilientExecutor,
    private val secrets: SecretStore,
    private val http: HttpClient = HttpClient.newHttpClient(),
) : ConnectionTester {
    override fun test(record: ConnectionRecord): ConnectionTestResult {
        val secret =
            try {
                secrets.read(SecretRef(record.credentialRef)).reveal()
            } catch (_: SecretNotFound) {
                return ConnectionTestResult(TestClass.AUTHENTICATION_REFUSED, CREDENTIAL_REFUSED)
            }
        return try {
            classify(executor.execute { send(record.endpoint, secret) })
        } catch (expected: Exception) {
            log.warn("connection test failed: {}", rootClass(expected)) // CS-34: the class only, never the message
            ConnectionTestResult(TestClass.UNREACHABLE, UNREACHABLE)
        }
    }

    private fun send(
        endpoint: String,
        secret: String,
    ): Int {
        val request =
            HttpRequest
                .newBuilder(URI.create(endpoint))
                .timeout(executor.policy.timeout.timeout)
                .header("Authorization", "Bearer $secret")
                .GET()
                .build()
        return try {
            http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        } catch (expected: IOException) {
            throw IOException("I/O failure: ${expected.javaClass.simpleName}")
        }
    }

    private fun classify(status: Int): ConnectionTestResult =
        when (status) {
            in OK_RANGE -> ConnectionTestResult(TestClass.SUCCESS)
            UNAUTHORIZED, FORBIDDEN -> ConnectionTestResult(TestClass.AUTHENTICATION_REFUSED, CREDENTIAL_REFUSED)
            else -> ConnectionTestResult(TestClass.UNEXPECTED_RESPONSE, "HTTP-$status")
        }

    private fun rootClass(failure: Throwable): String {
        var current = failure
        while (current.cause != null && current.cause !== current) current = checkNotNull(current.cause)
        return current.javaClass.simpleName
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(HttpConnectionTester::class.java)
        val OK_RANGE = 200..299
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val CREDENTIAL_REFUSED = "CREDENTIAL-REFUSED"
        const val UNREACHABLE = "UNREACHABLE"
    }
}
