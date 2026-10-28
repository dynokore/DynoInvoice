package com.dynokore.gst.rules.bootstrap.served

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

private const val LOOK_ALIKE_SUFFIX = "-alt"
private const val ANY_EXCEPT = "any-except:"

/** The services the document names as callers, `any-except:<svc>` contributing `<svc>`. */
fun namedCallers(document: List<ContractOperation>): Set<String> =
    document.flatMap { it.edgeCallers }.flatMap { element ->
        when {
            element == "any" -> error("an `any` edge needs the service list of the model; this service has none")
            element.startsWith(ANY_EXCEPT) -> listOf(element.removePrefix(ANY_EXCEPT))
            else -> listOf(element)
        }
    }.toSet()

/** A caller the operation allows: a named one, or for `any-except:<svc>` a mapped service other than `<svc>`. */
fun allowedCaller(
    op: ContractOperation,
    mapped: Set<String>,
): String {
    val excepted = op.edgeCallers.filter { it.startsWith(ANY_EXCEPT) }.map { it.removePrefix(ANY_EXCEPT) }.toSet()
    val named = op.edgeCallers.filterNot { it == "any" || it.startsWith(ANY_EXCEPT) }
    return named.firstOrNull() ?: checkNotNull((mapped - excepted).minOrNull()) { "${op.key}: no allowed caller" }
}

/**
 * A service refuses a verified caller the `x-sync-edge` of the called operation does not name, with 403 SRT-004
 * (INV-REQ-1202 behavior 1; owner ruling 2026-10-22; INV-AC-8455).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncEdgeCallerOperationsTest {
    private val document = ContractDocument.read()
    private val named = namedCallers(document)
    private val service = ServedService(named + named.map { it + LOOK_ALIKE_SUFFIX })

    @AfterAll
    fun stop() = service.close()

    @BeforeEach
    fun reset() = Doubles.reset()

    private fun expectedCallers(op: ContractOperation): String = op.edgeCallers.sorted().joinToString(",")

    private fun tsvLines(): Map<String, List<String>> =
        ContractDocument::class.java.classLoader.getResources("META-INF/gst/sync-edge-callers.tsv").toList()
            .flatMap { url -> url.openStream().use { String(it.readAllBytes(), Charsets.UTF_8) }.lines().filter(String::isNotEmpty) }
            .groupBy({ it.substringBeforeLast('\t') }, { it.substringAfterLast('\t') })

    private fun refused(
        op: ContractOperation,
        workload: String?,
        authorization: String? = USER_A_TOKEN,
        extra: Map<String, String> = emptyMap(),
        what: String,
    ) {
        Doubles.reset()
        val answer = service.call(op, workload, authorization, extra)
        withClue("${op.key}: $what") {
            answer.statusCode() shouldBe 403
            answer.body() shouldContain "SRT-004"
            Doubles.reached.shouldBeEmpty()
        }
    }

    private inline fun <T> withClue(
        clue: String,
        block: () -> T,
    ): T = io.kotest.assertions.withClue(clue, block)

    @Test
    fun `INV-AC-8455 every mapped operation answers by the callers its x-sync-edge names`() {
        val served = servedOperations(service, document)
        served.isNotEmpty() shouldBe true
        val lines = tsvLines()
        val mappedServices = named + named.map { it + LOOK_ALIKE_SUFFIX }
        println("INV-AC-8455 mapped operations and their allowed callers:")
        served.forEach { (mapped, op) ->
            println("  ${mapped.key} <- ${expectedCallers(op)}")
            withClue("${op.key}: the line of sync-edge-callers.tsv") {
                lines[op.method + "\t" + op.path] shouldBe listOf(expectedCallers(op))
            }
            val plain = op.edgeCallers.filterNot { it == "any" || it.startsWith(ANY_EXCEPT) }
            val excepted = op.edgeCallers.filter { it.startsWith(ANY_EXCEPT) }.map { it.removePrefix(ANY_EXCEPT) }
            val allowed = if (plain.isNotEmpty()) plain else (named - excepted.toSet()).sorted()
            allowed.forEach { caller ->
                Doubles.reset()
                val answer = service.call(op, workloadToken(caller), USER_A_TOKEN)
                withClue("${op.key}: the token of $caller") {
                    Doubles.reached.shouldHaveSize(1)
                    (answer.statusCode() != 403 && answer.statusCode() != 401) shouldBe true
                }
            }
            val unnamed = (mappedServices - allowed.toSet()).sorted()
            unnamed.forEach { refused(op, workloadToken(it), what = "the token of the unnamed service $it") }
            refused(op, workloadToken(allowed.first() + LOOK_ALIKE_SUFFIX), what = "the look-alike of a named caller")
            refused(op, UNMAPPED_WORKLOAD_TOKEN, what = "a verified token that no entry maps")
            refused(op, workloadToken(unnamed.first()), authorization = null, what = "an unnamed service, no end-user token")
            refused(
                op,
                workloadToken(unnamed.first()),
                extra = mapOf("X-Caller-Service" to allowed.first()),
                what = "X-Caller-Service naming a caller, token of an unnamed service",
            )
            excepted.forEach { refused(op, workloadToken(it), what = "the excepted service $it") }
            Doubles.reset()
            service.call(op, null, USER_A_TOKEN).statusCode() shouldBe 401
            Doubles.reached.shouldBeEmpty()
        }
    }

    @Test
    fun `INV-AC-8455 the main source sets hold no literal caller name passed to a caller check`() {
        val names = named + namedCallers(document).map { it + LOOK_ALIKE_SUFFIX }
        val offenders =
            Files.walk(Path.of("..")).use { paths ->
                paths.filter { it.toString().replace('\\', '/').contains("/src/main/") && it.toString().endsWith(".kt") }
                    .toList()
            }.filter { file ->
                val text = Files.readString(file)
                text.contains("ServiceCaller") && names.any { text.contains("\"$it\"") }
            }
        offenders.shouldBeEmpty()
    }
}
