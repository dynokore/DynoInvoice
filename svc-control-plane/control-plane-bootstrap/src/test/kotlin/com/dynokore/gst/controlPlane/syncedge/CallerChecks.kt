package com.dynokore.gst.controlPlane.syncedge

import org.springframework.context.ApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The requests of INV-AC-8455 against one running service: every caller of [services] and their look-alikes. */
class CallerChecks(
    private val context: ApplicationContext,
    private val services: List<String>,
    private val lookAlikes: List<String>,
) {
    /** Where an answer is not what the operation's `x-sync-edge` demands. */
    fun problems(
        operation: Operation,
        generated: String?,
    ): List<String> {
        val fields = operation.callerFields
        val excluded = fields.singleOrNull { it.startsWith(EXCEPT) }?.removePrefix(EXCEPT)
        val any = fields == listOf("any")
        val allowed =
            when {
                any -> services.toSet()
                excluded != null -> (services + lookAlikes).toSet() - excluded
                else -> fields.toSet()
            }
        val expected = if (any) "any" else excluded?.let { "$EXCEPT$it" } ?: fields.sorted().joinToString(",")
        val found = mutableListOf<String>()
        if (generated !=
            expected
        ) {
            found += "${operation.label}: the generated line is '$generated', the document says '$expected'"
        }
        found += reached(operation, allowed, any)
        if (!any) found += refused(operation, allowed)
        val none = send(operation, mapOf(AUTH to END_USER))
        if (none.statusCode() != 401) found += "${operation.label} (no workload token): ${none.statusCode()}"
        return found
    }

    private fun reached(
        operation: Operation,
        allowed: Set<String>,
        any: Boolean,
    ): List<String> {
        val callers = allowed.sorted().map { "named caller $it" to mapOf(TOKEN to "token-$it", AUTH to END_USER) }
        val unmapped =
            if (any) {
                listOf(
                    "a verified token no entry maps" to mapOf(TOKEN to UNMAPPED, AUTH to END_USER),
                )
            } else {
                emptyList()
            }
        return (callers + unmapped).mapNotNull { (what, headers) ->
            val before = Doubles.reached.size
            val answer = send(operation, headers)
            val once = Doubles.reached.size - before == 1
            "${operation.label} ($what): ${answer.statusCode()} reached $once ${answer.body().take(BODY_SHOWN)}"
                .takeUnless { answer.statusCode() == 202 && once }
        }
    }

    private fun refused(
        operation: Operation,
        allowed: Set<String>,
    ): List<String> {
        val unnamed = (services + lookAlikes).filterNot { it in allowed }
        val tokens =
            unnamed.map { "unnamed service $it" to mapOf(TOKEN to "token-$it") } +
                ("an unmapped token" to mapOf(TOKEN to UNMAPPED))
        val spoofed = allowed.firstOrNull()?.let { mapOf("X-Caller-Service" to it) }.orEmpty()
        return tokens.flatMap { (what, headers) ->
            listOfNotNull(
                refusal(operation, what, headers + (AUTH to END_USER)),
                refusal(operation, "$what, header X-Caller-Service", headers + (AUTH to END_USER) + spoofed).takeIf {
                    spoofed.isNotEmpty()
                },
            )
        }
    }

    /** The finding when the request is not 403 SRT-004 with the stored rows and events as before. */
    private fun refusal(
        operation: Operation,
        what: String,
        headers: Map<String, String>,
    ): String? {
        val before = stored()
        val calls = Doubles.reached.size
        val answer = send(operation, headers)
        val good =
            answer.statusCode() == 403 && "SRT-004" in answer.body() && before == stored() &&
                Doubles.reached.size == calls
        return "${operation.label} ($what): ${answer.statusCode()} ${answer.body().take(
            BODY_SHOWN,
        )}".takeUnless { good }
    }

    fun send(
        operation: Operation,
        headers: Map<String, String>,
    ): HttpResponse<String> {
        val port = context.environment.getProperty("local.server.port")
        val path =
            operation.template.replace(Regex("\\{([^}]+)}")) {
                operation.pathValues.getOrDefault(it.groupValues[1], "a")
            }
        val query =
            operation.query
                .takeIf { it.isNotEmpty() }
                ?.let { "?$it" }
                .orEmpty()
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path$query"))
        (operation.headers + headers).forEach { (name, value) -> request.header(name, value) }
        if (operation.method == "GET") {
            request.GET()
        } else {
            request.header("Content-Type", "application/json")
            request.method(operation.method, HttpRequest.BodyPublishers.ofString(operation.body))
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /**
     * What the service stored: the connections its pool handed out. The refused call must not open one, so no row
     * and no event (the outbox rows are written through the same pool) can have been written.
     */
    private fun stored(): Map<String, Long> = mapOf("connections" to Doubles.connections.get().toLong())

    companion object {
        const val EXCEPT = "any-except:"
        const val TOKEN = "X-Workload-Token"
        const val AUTH = "Authorization"
        const val END_USER = "Bearer ${TestIdentity.USER_TOKEN}"
        const val UNMAPPED = "token-unmapped"
        private const val BODY_SHOWN = 120
    }
}
