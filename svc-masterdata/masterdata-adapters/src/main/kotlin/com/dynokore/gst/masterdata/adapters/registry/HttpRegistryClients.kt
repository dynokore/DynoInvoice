package com.dynokore.gst.masterdata.adapters.registry

import com.dynokore.gst.masterdata.application.registry.DirectoryClient
import com.dynokore.gst.masterdata.application.registry.DirectoryReply
import com.dynokore.gst.masterdata.application.registry.RegisterClient
import com.dynokore.gst.masterdata.application.registry.RegisterReply
import com.dynokore.gst.masterdata.domain.registry.RegisterFact
import com.dynokore.platform.adapterruntime.ResilientExecutor
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate

/** The registry answered with a status that is neither a found nor a not-found answer, or could not be reached. */
class RegistryUnreachable(
    message: String,
) : RuntimeException(message)

/**
 * A registry as the wire reads: `GET {base}/lookup/{value}` answers 200 with the entry or 404 with a not-found body
 * (the recorded shape of the certification-kit simulators, provisional under BLK-2). Every call goes through the
 * adapter runtime's [ResilientExecutor], so it carries the edge's deadline, bounded retry, circuit breaker and
 * bulkhead. Only GET is ever sent: the System never writes to a registry.
 */
internal class RegistryHttp(
    private val base: URI,
    private val executor: ResilientExecutor,
    private val http: HttpClient = HttpClient.newHttpClient(),
) {
    /** The status and body of the lookup; an I/O failure or a status outside 200 and 404 is [RegistryUnreachable]. */
    fun get(value: String): Pair<Int, String> =
        executor.execute {
            // the multi-argument constructor quotes what a path may not hold and keeps the `:` of a Peppol ID
            val target = URI(base.scheme, base.authority, base.path.trimEnd('/') + "/lookup/" + value, null, null)
            val request =
                HttpRequest
                    .newBuilder(target)
                    .timeout(executor.policy.timeout.timeout)
                    .GET()
                    .build()
            val response =
                try {
                    http.send(request, HttpResponse.BodyHandlers.ofString())
                } catch (expected: IOException) {
                    throw RegistryUnreachable("I/O failure: ${expected.javaClass.simpleName}")
                }
            if (response.statusCode() != OK && response.statusCode() != NOT_FOUND) {
                throw RegistryUnreachable("status ${response.statusCode()}")
            }
            response.statusCode() to response.body()
        }

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
    }
}

private val log = LoggerFactory.getLogger("com.dynokore.gst.masterdata.adapters.registry")
private val mapper = JsonMapper.builder().build()

/** The SG Peppol Directory and SMP over HTTP (INV-REQ-318): found, not found, or unreachable (INV-REQ-319). */
class HttpDirectoryClient internal constructor(
    private val wire: RegistryHttp,
) : DirectoryClient {
    constructor(base: URI, executor: ResilientExecutor) : this(RegistryHttp(base, executor))

    override fun lookup(peppolId: String): DirectoryReply =
        try {
            val (status, body) = wire.get(peppolId)
            if (status == FOUND) DirectoryReply.Found(apReference(body), body) else DirectoryReply.NotFound(body)
        } catch (expected: Exception) {
            // the executor rethrows after its bounded retry: a timeout, an open breaker, a full bulkhead or a failure
            log.warn("directory lookup failed: {}", expected.javaClass.simpleName) // CS-34: class only
            DirectoryReply.Unreachable
        }

    /** The AP's reference when the entry carries one (`apReference`); null otherwise. */
    private fun apReference(body: String): String? =
        runCatching { mapper.readTree(body) }
            .getOrNull()
            ?.text("apReference")

    private companion object {
        const val FOUND = 200
    }
}

/** The IRAS Check GST Register over HTTP (INV-REQ-477): an answer, or unavailable (INV-REQ-801). */
class HttpRegisterClient internal constructor(
    private val wire: RegistryHttp,
) : RegisterClient {
    constructor(base: URI, executor: ResilientExecutor) : this(RegistryHttp(base, executor))

    override fun lookup(uen: String): RegisterReply =
        try {
            val (status, body) = wire.get(uen)
            RegisterReply.Answered(fact(status, body), body)
        } catch (expected: Exception) {
            log.warn("register lookup failed: {}", expected.javaClass.simpleName) // CS-34: class only
            RegisterReply.Unavailable
        }

    private fun fact(
        status: Int,
        body: String,
    ): RegisterFact {
        val node = runCatching { mapper.readTree(body) }.getOrNull()
        val found = status == FOUND
        return RegisterFact(
            found,
            node?.text("status") ?: if (found) "REGISTERED" else "NOT_REGISTERED",
            node?.text("gstRegistrationNumber"),
            node?.text("registeredName"),
            node?.text("asOf")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        )
    }

    private companion object {
        const val FOUND = 200
    }
}

private fun JsonNode.text(field: String): String? =
    get(field)
        ?.takeIf {
            it.isString
        }?.asString()
        ?.takeIf { it.isNotBlank() }
