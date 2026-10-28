package com.dynokore.gst.access.bootstrap.served

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

// a service name has no digit (WorkloadIdentityProperties.SERVICE_NAME), so the suffix `-2` cannot be mapped
private const val LOOK_ALIKE_SUFFIX = "-alt"

private data class Attempt(
    val workload: String?,
    val authorization: String? = USER_A_TOKEN,
    val extra: Map<String, String> = emptyMap(),
)

/**
 * INV-AC-8455: a verified caller that the `x-sync-edge` of the called operation does not name is refused with 403
 * SRT-004 and nothing is stored. The allowed callers are read from the document; the file
 * `META-INF/gst/sync-edge-callers.tsv` of the contract module must hold exactly them.
 */
class SyncEdgeCallerOperationsTest {
    private val document = ContractDocument.read()
    private val named = namedCallers(document)
    private val mapped = named + named.map { it + LOOK_ALIKE_SUFFIX }

    @BeforeEach
    fun reset() = Doubles.reset()

    private fun expectedCallers(op: ContractOperation): String = op.edgeCallers.sorted().joinToString(",")

    private fun tsvLines(): Map<String, List<String>> =
        ContractDocument::class.java.classLoader
            .getResources("META-INF/gst/sync-edge-callers.tsv")
            .toList()
            .flatMap { url ->
                url
                    .openStream()
                    .use { String(it.readAllBytes(), Charsets.UTF_8) }
                    .lines()
                    .filter(String::isNotEmpty)
            }.groupBy({ it.substringBeforeLast('\t') }, { it.substringAfterLast('\t') })

    private fun refused(
        served: ServedContext,
        op: ContractOperation,
        request: Attempt,
        what: String,
    ) {
        Doubles.reset()
        val before = served.stored()
        val answer = served.call(op, request.workload, request.authorization, request.extra)
        assertThat(answer.status).describedAs("${op.key}: $what").isEqualTo(403)
        assertThat(answer.contentAsString).describedAs("${op.key}: $what").contains("SRT-004")
        assertThat(Doubles.reached).describedAs("${op.key}: $what, use case double").isEmpty()
        assertThat(served.stored()).describedAs("${op.key}: $what, stored rows and events").isEqualTo(before)
    }

    private fun reachesAllowed(
        served: ServedContext,
        op: ContractOperation,
        allowed: List<String>,
    ) = allowed.forEach { caller ->
        Doubles.reset()
        val answer = served.call(op, workloadToken(caller), USER_A_TOKEN)
        assertThat(Doubles.reached).describedAs("${op.key}: the token of $caller").hasSize(1)
        assertThat(answer.status).describedAs("${op.key}: the token of $caller").isNotIn(401, 403)
    }

    private fun refusesOthers(
        served: ServedContext,
        op: ContractOperation,
        allowed: List<String>,
    ) {
        val unnamed = (mapped - allowed.toSet()).sorted()
        unnamed.forEach { refused(served, op, Attempt(workloadToken(it)), "the unnamed service $it") }
        plainCallers(op).forEach {
            refused(served, op, Attempt(workloadToken(it + LOOK_ALIKE_SUFFIX)), "the look-alike of $it")
        }
        refused(served, op, Attempt(UNMAPPED_WORKLOAD_TOKEN), "a verified token that no entry maps")
        exceptedCallers(op).forEach { refused(served, op, Attempt(workloadToken(it)), "the excepted $it") }
        val stranger = workloadToken(unnamed.first())
        refused(served, op, Attempt(stranger, authorization = null), "an unnamed service, no token")
        val header = mapOf("X-Caller-Service" to allowed.first())
        refused(served, op, Attempt(stranger, extra = header), "X-Caller-Service naming a caller, unnamed token")
        Doubles.reset()
        val none = served.call(op, null, USER_A_TOKEN)
        assertThat(none.status).describedAs("${op.key}: no workload token").isEqualTo(401)
        assertThat(Doubles.reached).describedAs("${op.key}: no workload token, double").isEmpty()
    }

    @Test
    fun `INV-AC-8455 every mapped operation answers by the callers its x-sync-edge names`() =
        withServedService(mapped) { served ->
            val operations = servedOperations(served, document)
            assertThat(operations).isNotEmpty()
            val lines = tsvLines()
            println("INV-AC-8455 mapped operations and their allowed callers:")
            operations.forEach { (mappedHandler, op) ->
                println("  ${mappedHandler.key} <- ${expectedCallers(op)}")
                assertThat(lines[op.method + "\t" + op.path])
                    .describedAs("${op.key}: the line of sync-edge-callers.tsv")
                    .isEqualTo(listOf(expectedCallers(op)))
                val allowed = allowedCallers(op, mapped)
                reachesAllowed(served, op, allowed)
                refusesOthers(served, op, allowed)
            }
        }

    @Test
    fun `INV-AC-8455 the main source sets hold no string literal equal to a caller name`() {
        val sources =
            Files
                .walk(Path.of(".."))
                .use { paths ->
                    paths
                        .filter {
                            it.toString().replace('\\', '/').contains("/src/main/") && it.toString().endsWith(".kt")
                        }.toList()
                }.associate { it.toString() to Files.readString(it) }
        assertThat(sources).describedAs("main Kotlin files scanned").isNotEmpty()
        assertThat(
            callerLiteralOffenders(sources, mapped + named),
        ).describedAs("main files with a caller name").isEmpty()
    }

    @Test
    fun `INV-AC-8455 the source scan finds a caller literal in a bad source and ignores comments`() {
        val names = setOf("console-bff")
        val bad =
            mapOf(
                "Direct.kt" to "class A { fun f() = check(helper.same(\"console-bff\")) }",
                "Other.kt" to "val x = 1; val y = \"console-bff\"; val z = 2",
                "Template.kt" to "val t = \"\"\"console-bff\"\"\"",
            )
        assertThat(callerLiteralOffenders(bad, names)).containsExactlyInAnyOrder("Direct.kt", "Other.kt", "Template.kt")
        val clean =
            mapOf(
                "Line.kt" to "// \"console-bff\"\nval a = 1",
                "Block.kt" to "/* outer /* \"console-bff\" */ \"console-bff\" */ val a = 1",
                "Longer.kt" to "val a = \"console-bff-x\"",
                "Char.kt" to "val c = '\"'; val d = \"x\"",
            )
        assertThat(callerLiteralOffenders(clean, names)).isEmpty()
    }
}

/** The files whose code (comments removed) holds a string literal equal to one of [names]. */
fun callerLiteralOffenders(
    sources: Map<String, String>,
    names: Set<String>,
): List<String> = sources.filter { (_, text) -> stringLiterals(text).any { it in names } }.keys.sorted()

private val TOKEN =
    listOf(
        "[/][*]",
        "[/][/][^\\n]*",
        "\"\"\"[\\s\\S]*?\"\"\"",
        "\"(?:\\\\.|[^\"\\\\\\n])*\"",
        "'(?:\\\\.|[^'\\\\\\n])+'",
    ).joinToString("|").toRegex()

/** The index after the block comment that opens just before [from]; block comments nest in Kotlin. */
private fun afterBlockComment(
    text: String,
    from: Int,
): Int {
    var depth = 1
    var i = from
    while (depth > 0 && i < text.length) {
        when {
            text.startsWith("/*", i) -> {
                depth++
                i += 2
            }

            text.startsWith("*/", i) -> {
                depth--
                i += 2
            }

            else -> {
                i++
            }
        }
    }
    return i
}

/** The contents of the string literals of Kotlin [text], skipping line comments and nested block comments. */
fun stringLiterals(text: String): List<String> {
    val found = mutableListOf<String>()
    var i = 0
    while (true) {
        val token = TOKEN.find(text, i) ?: return found
        val value = token.value
        i = token.range.last + 1
        when {
            value == "/*" -> i = afterBlockComment(text, i)
            value.startsWith("//") || value.startsWith("'") -> Unit
            value.startsWith("\"\"\"") -> found.add(value.removeSurrounding("\"\"\""))
            else -> found.add(value.removeSurrounding("\""))
        }
    }
}
