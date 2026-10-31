package com.dynokore.gst.transport.syncedge

import com.dynokore.gst.transport.adapters.TransportDb
import org.springframework.context.ApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection

/** The requests of INV-AC-8455 against one running service: every caller of [services] and their look-alikes. */
class CallerChecks(
    private val context: ApplicationContext,
    private val connection: Connection,
    private val services: List<String>,
    private val lookAlikes: List<String>,
    private val calls: HandlerCalls,
    private val reached: Map<String, Reach> = emptyMap(),
) {
    /** What an operation that is built answers when the request reaches it; the others are stubs (501). */
    class Reach(
        val status: Int,
        val body: String,
    )

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
                excluded != null -> services.toSet() - excluded
                else -> fields.toSet()
            }
        val expected = if (any) "any" else excluded?.let { "$EXCEPT$it" } ?: fields.sorted().joinToString(",")
        val found = mutableListOf<String>()
        if (generated !=
            expected
        ) {
            found += "${operation.label}: the generated line is '$generated', the document says '$expected'"
        }
        found += reaching(operation, allowed, any)
        if (!any) found += refused(operation, allowed)
        val reachedBefore = calls.count(operation.label)
        val none = send(operation, mapOf(AUTH to END_USER))
        if (none.statusCode() != 401 || calls.count(operation.label) != reachedBefore) {
            found += "${operation.label} (no workload token): ${none.statusCode()}"
        }
        return found
    }

    private fun reaching(
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
            val before = stored()
            val reachedBefore = calls.count(operation.label)
            val answer = send(operation, headers)
            val expected = reached[operation.label]?.status ?: STUB
            val changed = stored().entries.count { before[it.key] != it.value }
            val once = calls.count(operation.label) - reachedBefore
            "${operation.label} ($what): ${answer.statusCode()} ${answer.body().take(
                BODY_SHOWN,
            )}, double called $once times".takeIf {
                answer.statusCode() != expected || once != 1 || (expected == STUB && changed != 0)
            }
        }
    }

    private fun refused(
        operation: Operation,
        allowed: Set<String>,
    ): List<String> {
        val unnamed = services.filterNot { it in allowed } + lookAlikes
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
        val reachedBefore = calls.count(operation.label)
        val answer = send(operation, headers)
        val reachedBy = calls.count(operation.label) - reachedBefore
        val good = answer.statusCode() == 403 && "SRT-004" in answer.body() && before == stored() && reachedBy == 0
        return "${operation.label} ($what): ${answer.statusCode()} ${answer.body().take(
            BODY_SHOWN,
        )}, double called $reachedBy times".takeUnless { good }
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
            request.method(
                operation.method,
                HttpRequest.BodyPublishers.ofString(reached[operation.label]?.body ?: operation.body),
            )
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** Row counts of every table of the schema (the events are rows of its outbox tables). */
    private fun stored(): Map<String, Long> =
        connection.createStatement().use { statement ->
            statement.executeQuery(COUNTS).use { rows ->
                buildMap { while (rows.next()) put(rows.getString(1), rows.getLong(2)) }
            }
        }

    companion object {
        private const val STUB = 501
        const val EXCEPT = "any-except:"
        const val TOKEN = "X-Workload-Token"
        const val AUTH = "Authorization"
        const val END_USER = "Bearer end-user"
        const val UNMAPPED = "token-unmapped"
        private const val BODY_SHOWN = 120
        private val COUNTS =
            "select table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', " +
                "table_schema, table_name), false, true, '')))[1]::text::bigint " +
                "from information_schema.tables where table_schema = '${TransportDb.SCHEMA}' and table_type = 'BASE TABLE'"
    }
}
