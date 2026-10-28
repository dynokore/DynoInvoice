package com.dynokore.gst.access.bootstrap.served

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

private const val LOOK_ALIKE_SUFFIX = "-alt" // a service name has no digit (WorkloadIdentityProperties.SERVICE_NAME), so `-2` cannot be mapped

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
            .flatMap { url -> url.openStream().use { String(it.readAllBytes(), Charsets.UTF_8) }.lines().filter(String::isNotEmpty) }
            .groupBy({ it.substringBeforeLast('\t') }, { it.substringAfterLast('\t') })

    private fun refused(
        served: ServedContext,
        op: ContractOperation,
        workload: String?,
        authorization: String? = USER_A_TOKEN,
        extra: Map<String, String> = emptyMap(),
        what: String,
    ) {
        Doubles.reset()
        val before = served.stored()
        val answer = served.call(op, workload, authorization, extra)
        assertThat(answer.status).describedAs("${op.key}: $what").isEqualTo(403)
        assertThat(answer.contentAsString).describedAs("${op.key}: $what").contains("SRT-004")
        assertThat(Doubles.reached).describedAs("${op.key}: $what, use case double").isEmpty()
        assertThat(served.stored()).describedAs("${op.key}: $what, stored rows and events").isEqualTo(before)
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
                allowed.forEach { caller ->
                    Doubles.reset()
                    val answer = served.call(op, workloadToken(caller), USER_A_TOKEN)
                    assertThat(Doubles.reached).describedAs("${op.key}: the token of $caller").hasSize(1)
                    assertThat(answer.status).describedAs("${op.key}: the token of $caller").isNotIn(401, 403)
                }
                val unnamed = (mapped - allowed.toSet()).sorted()
                unnamed.forEach { refused(served, op, workloadToken(it), what = "the token of the unnamed service $it") }
                if (plainCallers(op).isNotEmpty()) {
                    plainCallers(op).forEach {
                        refused(served, op, workloadToken(it + LOOK_ALIKE_SUFFIX), what = "the look-alike of the named caller $it")
                    }
                }
                refused(served, op, UNMAPPED_WORKLOAD_TOKEN, what = "a verified token that no entry maps")
                exceptedCallers(op).forEach { refused(served, op, workloadToken(it), what = "the excepted service $it") }
                val stranger = unnamed.first()
                refused(served, op, workloadToken(stranger), authorization = null, what = "an unnamed service, no end-user token")
                refused(
                    served,
                    op,
                    workloadToken(stranger),
                    extra = mapOf("X-Caller-Service" to allowed.first()),
                    what = "X-Caller-Service naming a caller, token of an unnamed service",
                )
                Doubles.reset()
                assertThat(served.call(op, null, USER_A_TOKEN).status).describedAs("${op.key}: no workload token").isEqualTo(401)
                assertThat(Doubles.reached).describedAs("${op.key}: no workload token, double").isEmpty()
            }
        }

    @Test
    fun `INV-AC-8455 the main source sets hold no literal caller name passed to a caller check`() {
        val names = mapped
        val serviceName = Regex("\"([a-z][a-z-]{0,62})\"")
        val offenders =
            Files
                .walk(Path.of(".."))
                .use { paths ->
                    paths.filter { it.toString().replace('\\', '/').contains("/src/main/") && it.toString().endsWith(".kt") }.toList()
                }.filter { file ->
                    val text = Files.readString(file)
                    (text.contains("ServiceCaller") || text.contains("serviceCaller")) &&
                        serviceName.findAll(text).any { it.groupValues[1] in names || it.groupValues[1].endsWith("-svc") }
                }
        assertThat(offenders).describedAs("main files with a literal caller name").isEmpty()
    }
}
