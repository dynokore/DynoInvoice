package com.dynokore.gst.masterdata.adapters.registry

import com.dynokore.gst.masterdata.application.registry.DirectoryClient
import com.dynokore.gst.masterdata.application.registry.DirectoryReply
import com.dynokore.gst.masterdata.application.registry.RegisterClient
import com.dynokore.gst.masterdata.application.registry.MDM_008
import com.dynokore.gst.masterdata.application.registry.REGISTER_UNAVAILABLE
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
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The class name of the innermost cause: what the log names, never the message (CS-34). */
private fun rootClass(failure: Throwable): String {
    var current = failure
    while (current.cause != null && current.cause !== current) current = checkNotNull(current.cause)
    return current.javaClass.simpleName
}

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
            val target = lookupUri(base, value)
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
                    throw RegistryUnreachable("I/O failure: ${expected.javaClass.simpleName}", expected)
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

private const val SEGMENT_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_:"
private const val BYTE_MASK = 0xFF
private const val HEX = "0123456789ABCDEF"
private const val NIBBLE = 4

/** The looked-up value is one path segment: all but unreserved characters and `:` is percent-encoded (CWE-22). */
internal fun lookupUri(
    base: URI,
    value: String,
): URI {
    val segment = StringBuilder()
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and BYTE_MASK
        if (code < BYTE_MASK && code.toChar() in SEGMENT_SAFE) {
            segment.append(code.toChar())
        } else {
            segment.append('%').append(HEX[code shr NIBBLE]).append(HEX[code and (BYTE_MASK shr NIBBLE)])
        }
    }
    val prefix = URI(base.scheme, base.authority, base.path.trimEnd('/') + "/lookup/", null, null)
    return URI.create(prefix.toASCIIString() + segment)
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
            log.warn("directory lookup failed: {} {}", MDM_008, rootClass(expected)) // CS-34: key and class only
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
            log.warn("register lookup failed: {} {}", REGISTER_UNAVAILABLE, rootClass(expected)) // CS-34
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
