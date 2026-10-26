package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.syncedge.TestWorkloadVerifier
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.YamlMapFactoryBean
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ClassPathResource
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * INV-AC-8455 (API-25; owner ruling 2026-10-22): the service refuses, with 403 SRT-004 and nothing stored, a verified
 * caller that the `x-sync-edge` of the called operation does not name. The allowed callers are read from the contract
 * document the service ships; the enforcement is `service-runtime`'s, from the generated `sync-edge-callers.tsv`.
 * The controllers are stubs (501) until the tasks of `operations.tsv` build them, so a request that reaches the
 * handler answers 501 and no use case is called.
 */
class SyncEdgeCallerTest {
    private data class Operation(
        val method: String,
        val template: String,
        val edge: List<String>,
        val body: String = "{}",
        val headers: Map<String, String> = emptyMap(),
        val query: String = "",
        val pathValues: Map<String, String> = emptyMap(),
    ) {
        val label get() = "$method $template"

        /** The text before `->` of each element. */
        val callerFields get() = edge.map { it.substringBefore("->") }
    }

    private val unmappedToken = "token-unmapped"

    private fun contractOperations(text: String): List<Operation> {
        val factory = YamlMapFactoryBean()
        factory.setResources(ByteArrayResource(text.toByteArray()))
        val document = checkNotNull(factory.getObject()) { "the contract document is empty" }
        val operations = mutableListOf<Operation>()
        (document["paths"] as Map<*, *>).forEach { (path, item) ->
            (item as Map<*, *>).forEach { (method, operation) ->
                val edge = (operation as? Map<*, *>)?.get("x-sync-edge") ?: return@forEach
                val elements = if (edge is List<*>) edge.map { it as String } else listOf(edge as String)
                val json =
                    ((operation["requestBody"] as? Map<*, *>)?.get("content") as? Map<*, *>)?.get("application/json") as? Map<*, *>
                val example = ((json?.get("examples") as? Map<*, *>)?.values?.firstOrNull() as? Map<*, *>)?.get("value")
                val body = (example ?: json?.get("schema")?.let { sample(document, it) })?.let(Json::write) ?: "{}"
                val parameters =
                    (operation["parameters"] as? List<*>)
                        .orEmpty()
                        .filterIsInstance<Map<*, *>>()
                        .map { p -> (p["\$ref"] as? String)?.let { reference(document, it) as Map<*, *> } ?: p }
                        .filter { it["required"] == true }
                fun value(parameter: Map<*, *>) = Json.plain(sample(document, parameter["schema"]))
                val headers = parameters.filter { it["in"] == "header" }.associate { it["name"] as String to value(it) }
                val query = parameters.filter { it["in"] == "query" }.joinToString("&") { "${it["name"]}=${value(it)}" }
                val pathValues = parameters.filter { it["in"] == "path" }.associate { it["name"] as String to value(it) }
                operations += Operation(method.toString().uppercase(), path.toString(), elements, body, headers, query, pathValues)
            }
        }
        return operations
    }

    private fun reference(
        document: Map<String, Any?>,
        ref: String,
    ): Any? = ref.removePrefix("#/").split('/').fold(document as Any?) { at, key -> (at as Map<*, *>)[key] }

    /** A minimal value that satisfies [schema]: the required properties, the first enum value, the lower bounds. */
    private fun sample(
        document: Map<String, Any?>,
        schema: Any?,
        depth: Int = 0,
    ): Any? {
        val node = schema as Map<*, *>
        node["\$ref"]?.let { ref ->
            return sample(document, reference(document, ref as String), depth + 1)
        }
        (node["allOf"] as? List<*>)?.let { parts ->
            return parts.map { sample(document, it, depth + 1) }.filterIsInstance<Map<*, *>>().fold(mapOf<Any?, Any?>()) { a, b -> a + b }
        }
        ((node["oneOf"] ?: node["anyOf"]) as? List<*>)?.let { return sample(document, it.first(), depth + 1) }
        node["enum"]?.let { return (it as List<*>).first() }
        node["const"]?.let { return it }
        return when (node["type"]) {
            "object" -> {
                val required = (node["required"] as? List<*>).orEmpty()
                (node["properties"] as? Map<*, *>).orEmpty().filterKeys { it in required }.mapValues { sample(document, it.value, depth + 1) }
            }
            "array" -> if (((node["minItems"] as? Number)?.toInt() ?: 0) > 0) listOf(sample(document, node["items"], depth + 1)) else emptyList<Any?>()
            "integer" -> (node["minimum"] as? Number)?.toLong() ?: 1L
            "number" -> (node["minimum"] as? Number)?.toDouble() ?: 1.0
            "boolean" -> true
            else ->
                when (node["format"]) {
                    "uuid" -> SAMPLE_ID
                    "date-time" -> "2026-10-01T00:00:00Z"
                    "date" -> "2026-10-01"
                    else -> "a".repeat(maxOf(1, (node["minLength"] as? Number)?.toInt() ?: 1))
                }
        }
    }

    private fun contractText(): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("gst/contracts/evidence/openapi.yaml")) {
            "the evidence-contract module has no gst/contracts/evidence/openapi.yaml"
        }.use { String(it.readAllBytes(), Charsets.UTF_8) }

    private fun generatedLines(): List<List<String>> =
        javaClass.classLoader
            .getResources("META-INF/gst/sync-edge-callers.tsv")
            .toList()
            .flatMap { url -> url.readText().lines().filter { it.isNotEmpty() }.map { it.split('\t') } }

    private fun mappedOperations(context: ApplicationContext): List<Pair<String, String>> =
        context
            .getBean(RequestMappingHandlerMapping::class.java)
            .handlerMethods.keys
            .flatMap { info ->
                val templates = info.pathPatternsCondition?.patternValues.orEmpty()
                templates.filter { it.startsWith(INTERNAL) }.flatMap { path ->
                    info.methodsCondition.methods.map { it.name to path }
                }
            }.sortedBy { it.second + it.first }

    private fun send(
        context: ApplicationContext,
        operation: Operation,
        headers: Map<String, String>,
    ): HttpResponse<String> {
        val port = context.environment.getProperty("local.server.port")
        val path = operation.template.replace(Regex("\\{([^}]+)}")) { operation.pathValues.getOrDefault(it.groupValues[1], "a") }
        val query = operation.query.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
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

    /** Row counts of every table of the schema (the events are rows of its outbox tables). */
    private fun stored(connection: Connection): Map<String, Long> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "select table_name, (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', " +
                        "table_schema, table_name), false, true, '')))[1]::text::bigint " +
                        "from information_schema.tables where table_schema = '${EvidenceDb.SCHEMA}' " +
                        "and table_type = 'BASE TABLE'",
                ).use { rows ->
                    buildMap { while (rows.next()) put(rows.getString(1), rows.getLong(2)) }
                }
        }

    private fun properties(services: List<String>): Array<String> =
        services
            .flatMapIndexed { index, service ->
                listOf(
                    "service-runtime.workload-identity.callers[$index].service=$service",
                    "service-runtime.workload-identity.callers[$index].subject=sa-$service",
                )
            }.toTypedArray()

    @Test
    fun `INV-AC-8455 the service refuses a verified caller its x-sync-edge does not name`(
        @TempDir dir: Path,
    ) {
        val operations = contractOperations(contractText())
        val named = operations.flatMap { it.callerFields }.filterNot { it == "any" || it.startsWith(EXCEPT) }
        val excluded = operations.flatMap { it.callerFields }.filter { it.startsWith(EXCEPT) }.map { it.removePrefix(EXCEPT) }
        val services = (named + excluded).distinct().sorted()
        val lookAlikes = services.map { "$it-two" }
        val problems = mutableListOf<String>()
        EvidenceDb.superuser().use { connection ->
            serve(dir, properties(services + lookAlikes), javaClass.classLoader) { context ->
                    val mapped = mappedOperations(context)
                    val byLabel = operations.associateBy { it.label }
                    val lines = generatedLines().associate { (it[0] + " " + it[1]) to it[2] }
                    mapped.forEach { (method, path) ->
                        val operation = byLabel["$method $path"]
                        if (operation == null) {
                            problems += "$method $path: mapped, but the contract document has no such operation"
                            return@forEach
                        }
                        problems += check(context, connection, operation, lines[operation.label], services, lookAlikes)
                    }
                    (mapped.size > 0) shouldBe true
                }
        }
        problems.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-8455 with the generated resource off the classpath every internal operation answers 403 SRT-004`(
        @TempDir dir: Path,
    ) {
        val operations = contractOperations(contractText())
        val services = operations.flatMap { it.callerFields }.filterNot { it == "any" || it.startsWith(EXCEPT) }.distinct()
        val problems = mutableListOf<String>()
        val without = FilteredClassLoader(ClassPathResource("META-INF/gst/sync-edge-callers.tsv"))
        serve(dir, properties(services), without) { context ->
                val mapped = mappedOperations(context)
                (mapped.size > 0) shouldBe true
                mapped.forEach { (method, path) ->
                    val operation = Operation(method, path, listOf(services.first() + "->evidence-svc"))
                    val answer = send(context, operation, mapOf(TOKEN to "token-${services.first()}", AUTH to END_USER))
                    if (answer.statusCode() != 403 || "SRT-004" !in answer.body()) {
                        problems += "$method $path: ${answer.statusCode()} with the generated resource missing"
                    }
                }
            }
        problems.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-8455 the main source sets hold no literal caller name passed to a caller check`() {
        val root = Path.of(System.getProperty("user.dir")).parent
        val names =
            contractOperations(contractText())
                .flatMap { it.callerFields }
                .map { it.removePrefix(EXCEPT) }
                .filter { it != "any" }
                .toSet()
        val problems = mutableListOf<String>()
        Files.list(root).use { modules -> modules.toList() }.map { it.resolve("src/main") }.filter(Files::isDirectory).forEach {
            Files.walk(it).use { files -> files.toList() }.filter { f -> f.toString().endsWith(".kt") }.forEach { file ->
                val code = withoutComments(Files.readString(file))
                if (CALLER_CHECK.containsMatchIn(code)) problems += "$file: a literal passed to a caller check"
                names.filter { name -> "\"$name\"" in code }.forEach { name -> problems += "$file: the literal \"$name\"" }
            }
        }
        problems.shouldBeEmpty()
    }

    private fun serve(
        dir: Path,
        properties: Array<String>,
        loader: ClassLoader,
        block: (ConfigurableApplicationContext) -> Unit,
    ) {
        EvidenceContext.startServlet(dir, listOf(TestWorkloadVerifier::class.java), properties, loader).use(block)
    }

    private fun check(
        context: ApplicationContext,
        connection: Connection,
        operation: Operation,
        generated: String?,
        services: List<String>,
        lookAlikes: List<String>,
    ): List<String> {
        val problems = mutableListOf<String>()
        val fields = operation.callerFields
        val label = operation.label
        val any = fields == listOf("any")
        val except = fields.singleOrNull { it.startsWith(EXCEPT) }?.removePrefix(EXCEPT)
        val allowed: Set<String> =
            when {
                any -> services.toSet()
                except != null -> services.toSet() - except
                else -> fields.toSet()
            }
        val expectedLine =
            when {
                any -> "any"
                except != null -> "$EXCEPT$except"
                else -> fields.sorted().joinToString(",")
            }
        if (generated != expectedLine) problems += "$label: the generated line is '$generated', the document says '$expectedLine'"

        fun call(
            headers: Map<String, String>,
            expectAllowed: Boolean,
            what: String,
        ) {
            val before = stored(connection)
            val answer = send(context, operation, headers)
            val after = stored(connection)
            val ok =
                if (expectAllowed) {
                    answer.statusCode() == 501
                } else {
                    answer.statusCode() == 403 && "SRT-004" in answer.body() && before == after
                }
            if (!ok) problems += "$label ($what): ${answer.statusCode()} ${answer.body().take(120)}"
        }
        allowed.sorted().forEach { call(mapOf(TOKEN to "token-$it", AUTH to END_USER), true, "named caller $it") }
        if (any) call(mapOf(TOKEN to unmappedToken, AUTH to END_USER), true, "any: a verified token no entry maps")
        if (!any) {
            fields.filterNot { it.startsWith(EXCEPT) }.forEach {
                call(mapOf(TOKEN to "token-$it-two", AUTH to END_USER), false, "look-alike $it-two")
            }
            services.filterNot { it in allowed }.forEach { call(mapOf(TOKEN to "token-$it", AUTH to END_USER), false, "unnamed service $it") }
            lookAlikes.forEach { call(mapOf(TOKEN to "token-$it", AUTH to END_USER), false, "mapped look-alike $it") }
            call(mapOf(TOKEN to unmappedToken, AUTH to END_USER), false, "a verified token no entry maps")
            val unnamed = (services.filterNot { it in allowed } + lookAlikes).first()
            call(mapOf(TOKEN to "token-$unnamed", AUTH to END_USER), false, "end-user token, unnamed")
            allowed.firstOrNull()?.let {
                call(mapOf(TOKEN to "token-$unnamed", "X-Caller-Service" to it, AUTH to END_USER), false, "X-Caller-Service $it, unnamed")
            }
        }
        val none = send(context, operation, mapOf(AUTH to END_USER))
        if (none.statusCode() != 401) problems += "$label (no workload token): ${none.statusCode()}"
        return problems
    }

    /** Comments blanked (nested block comments included); string literals stay, a name in one is what is looked for. */
    private fun withoutComments(source: String): String {
        val out = StringBuilder()
        var i = 0
        var depth = 0
        var inString = false
        while (i < source.length) {
            val two = source.substring(i, minOf(i + 2, source.length))
            when {
                depth > 0 && two == "/*" -> depth++.also { i += 2 }
                depth > 0 && two == "*/" -> depth--.also { i += 2 }
                depth > 0 -> i++
                inString -> {
                    out.append(source[i])
                    if (source[i] == '\\' && i + 1 < source.length) out.append(source[++i])
                    else if (source[i] == '"') inString = false
                    i++
                }
                two == "/*" -> depth++.also { i += 2 }
                two == "//" -> while (i < source.length && source[i] != '\n') i++
                source[i] == '"' -> out.append(source[i++]).also { inString = true }
                else -> out.append(source[i++])
            }
        }
        return out.toString()
    }

    private object Json {
        fun plain(value: Any?): String = if (value is String) value else write(value)

        fun write(value: Any?): String =
            when (value) {
                null -> "null"
                is Map<*, *> -> value.entries.joinToString(",", "{", "}") { "\"${it.key}\":${write(it.value)}" }
                is List<*> -> value.joinToString(",", "[", "]") { write(it) }
                is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                else -> value.toString()
            }
    }

    private companion object {
        const val INTERNAL = "/internal/v1/"
        const val EXCEPT = "any-except:"
        const val SAMPLE_ID = "5b1f0c1e-7a37-4c45-9a52-2f0a4f0b9b11"
        const val TOKEN = "X-Workload-Token"
        const val AUTH = "Authorization"
        const val END_USER = "Bearer end-user"

        /** A string literal as the argument of a caller check: `require("x")`, `expect("x")`, `ServiceCaller...("x")`. */
        val CALLER_CHECK = Regex("""(ServiceCaller|callerCheck|requireCaller)\b[^;\n]*\(\s*"[^"]*"""")
    }
}
