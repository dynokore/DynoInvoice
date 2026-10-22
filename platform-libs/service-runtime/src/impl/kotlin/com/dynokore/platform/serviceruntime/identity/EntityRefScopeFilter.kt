package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
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
import org.springframework.http.server.PathContainer
import tools.jackson.core.JsonToken
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Refuses a request whose body entity reference is outside the end-user principal's entity set (API-24; coordinator
 * ruling INV-Q-8513 A; owner ruling INV-Q-3716), for the operations the contract marks with `x-entity-ref` and for no
 * other (API-25: a system-flow callee has no entity-set check). The principal is the one
 * [EndUserPrincipalBindingFilter] left in [EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE]; without it the answer
 * is 401 SRT-003. A body that is not one JSON object, is over [MAX_BODY_BYTES], repeats the property or holds it as
 * a non-string is 400 SRT-001; an absent property continues (the service refuses a missing required entity itself);
 * a string outside the set, or any string while the set is not current, is 403 SRT-004 (the same answer whether or
 * not the entity exists). The handler then reads the same body bytes. Messages and logs never carry the value.
 */
class EntityRefScopeFilter private constructor(
    parsed: List<OperationLine<String>>,
) : Filter {
    private val operations = OperationLines.of(parsed, RESOURCE, "properties") { a, b -> a == b }

    override fun doFilter(
        request: ServletRequest,
        response: ServletResponse,
        chain: FilterChain,
    ) {
        val http = request as HttpServletRequest
        val path = PathContainer.parsePath(http.requestURI.removePrefix(http.contextPath.orEmpty()))
        val serving = operations.serving(http.method, path)
        if (serving == null) {
            chain.doFilter(request, response)
            return
        }
        val out = response as HttpServletResponse
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        val principal = http.getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
        if (principal == null) {
            refuse(out, Problems.unauthenticated(correlation))
            return
        }
        val bytes = readLimited(http)
        if (bytes == null) {
            refuse(out, Problems.contractViolation("the request body is over $MAX_BODY_BYTES bytes", correlation))
            return
        }
        val value =
            try {
                stringProperty(bytes, serving.payload)
            } catch (_: MalformedBody) {
                refuse(
                    out,
                    Problems.contractViolation(
                        "the body of ${serving.method} ${serving.template} is not one JSON object with " +
                            "${serving.payload} absent or a single string",
                        correlation,
                    ),
                )
                return
            }
        if (value != null && (!principal.entitySetCurrent || !inSet(principal, value))) {
            refuse(out, Problems.accessDenied(correlation))
            return
        }
        chain.doFilter(RereadableRequest(http, bytes), response)
    }

    private fun inSet(
        principal: EndUserPrincipal,
        value: String,
    ): Boolean =
        try {
            principal.covers(EntityRef(value))
        } catch (_: IllegalArgumentException) {
            false
        }

    /** The body, or null when it is over the limit. */
    private fun readLimited(request: HttpServletRequest): ByteArray? {
        val bytes = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        return bytes.takeIf { it.size <= MAX_BODY_BYTES }
    }

    private class MalformedBody : RuntimeException()

    /** The string value of [property] at the top level, null when absent; [MalformedBody] on anything else. */
    private fun stringProperty(
        bytes: ByteArray,
        property: String,
    ): String? {
        val text =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw MalformedBody()
            }
        var found: String? = null
        try {
            MAPPER.createParser(text).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) throw MalformedBody()
                while (true) {
                    val token = parser.nextToken()
                    if (token == JsonToken.END_OBJECT) break
                    if (token != JsonToken.PROPERTY_NAME) throw MalformedBody()
                    val name = parser.currentName()
                    val valueToken = parser.nextToken()
                    if (name == property) {
                        if (found != null || valueToken != JsonToken.VALUE_STRING) throw MalformedBody()
                        found = parser.string
                    } else {
                        parser.skipChildren()
                    }
                }
                if (parser.nextToken() != null) throw MalformedBody()
            }
        } catch (malformed: MalformedBody) {
            throw malformed
        } catch (_: RuntimeException) {
            throw MalformedBody()
        }
        return found
    }

    private fun refuse(
        response: HttpServletResponse,
        answer: StoredResponse,
    ) {
        response.status = answer.status
        response.contentType = Problems.CONTENT_TYPE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(answer.body)
    }

    private class RereadableRequest(
        request: HttpServletRequest,
        private val body: ByteArray,
    ) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream {
            val source = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read(): Int = source.read()

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = source.read(b, off, len)

                override fun isFinished(): Boolean = source.available() == 0

                override fun isReady(): Boolean = true

                override fun setReadListener(listener: ReadListener?): Unit =
                    throw UnsupportedOperationException("asynchronous reads are not supported")
            }
        }

        override fun getReader(): BufferedReader =
            BufferedReader(InputStreamReader(inputStream, characterEncoding ?: StandardCharsets.UTF_8.name()))
    }

    companion object {
        const val RESOURCE = "META-INF/gst/entity-ref-operations.tsv"
        const val MAX_BODY_BYTES = 1048576
        private val PROPERTY = Regex("^[A-Za-z][A-Za-z0-9]{0,39}$")
        private val MAPPER = ObjectMapper()

        /** Every resource of [RESOURCE] on the classpath merged; a malformed or repeated line stops the start-up. */
        fun from(loader: ClassLoader): EntityRefScopeFilter {
            val operations =
                loader.getResources(RESOURCE).toList().flatMap { url ->
                    val text = url.openStream().use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
                    text.removeSuffix("\n").takeIf { it.isNotEmpty() }?.split('\n').orEmpty().map { line ->
                        val origin = "$RESOURCE $url"
                        val parts = line.split('\t')
                        check(parts.size == 3) { "$origin: a line needs exactly 3 TAB-separated fields" }
                        check(parts[0].isNotEmpty()) { "$origin: the method is empty" }
                        check(parts[1].startsWith("/")) { "$origin: the path must start with /" }
                        check(PROPERTY.matches(parts[2])) { "$origin: the property must match ${PROPERTY.pattern}" }
                        OperationLine(parts[0], parts[1], parts[2])
                    }
                }
            return EntityRefScopeFilter(operations)
        }
    }
}
