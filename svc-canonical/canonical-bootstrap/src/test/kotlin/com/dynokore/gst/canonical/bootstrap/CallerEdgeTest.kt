package com.dynokore.gst.canonical.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * INV-AC-8455: a verified caller that the `x-sync-edge` of the called operation does not name is refused with 403
 * SRT-004 and nothing is stored (INV-REQ-1202 behavior 1; INV-T-8253, INV-T-8456, INV-T-8457). The allowed callers are
 * read from the contract document, never written here.
 */
class CallerEdgeTest {
    private val document = ContractDocument.text()
    private val syncEdgeResource = "META-INF/gst/sync-edge-callers.tsv"

    private fun expectedLine(
        operation: DocumentOperation,
        handler: MappedHandler,
    ): String {
        val elements = operation.edges.map { it.substringBefore("->").trim() }
        val callers =
            if (elements.singleOrNull()?.let { it == "any" || it.startsWith("any-except:") } ==
                true
            ) {
                elements.single()
            } else {
                elements.sorted().joinToString(",")
            }
        return "${handler.method}\t${handler.path}\t$callers"
    }

    private fun tsvLines(service: ServedService): List<String> =
        service.classLoader
            .getResources(syncEdgeResource)
            .toList()
            .flatMap { it.readText().lines() }
            .filter { it.isNotEmpty() }

    private fun assertRefused(
        service: ServedService,
        label: String,
        block: () -> Answer,
    ) {
        val rows = service.state()
        val reached = service.counter.reaches.size
        val answer = block()
        assertThat(answer.status).describedAs("$label: ${answer.body}").isEqualTo(403)
        assertThat(answer.body).describedAs(label).contains("SRT-004")
        assertThat(service.counter.reaches.size).describedAs("$label use case calls").isEqualTo(reached)
        assertThat(service.state()).describedAs("$label stored rows and events").isEqualTo(rows)
    }

    private fun assertEdges(
        text: String,
        removed: List<String> = emptyList(),
    ) {
        val operations = ContractDocument.operations(text)
        val doubles = WorkloadDoubles(callersOf(ContractDocument.operations(document)))
        ServedService.run(doubles, removed) { service ->
            val tsv = tsvLines(service)
            service.handlers.forEach { handler ->
                val operation =
                    checkNotNull(ContractDocument.find(operations, handler)) {
                        "no operation of the document for ${handler.method} ${handler.path}"
                    }
                assertOperation(service, doubles, handler, operation, tsv)
            }
        }
    }

    private fun assertOperation(
        service: ServedService,
        doubles: WorkloadDoubles,
        handler: MappedHandler,
        operation: DocumentOperation,
        tsv: List<String>,
    ) {
        val id = operation.id
        val allowed = allowedCallers(operation, doubles.services)
        assertThat(tsv).describedAs("$id: $syncEdgeResource holds the line").contains(expectedLine(operation, handler))
        allowed.sorted().forEach { caller ->
            val before = service.counter.reaches.size
            val answer = service.call(handler, doubles.tokenOf(caller), EndUserDoubles.USER_A)
            assertThat(
                service.counter.reaches.size,
            ).describedAs(
                "$id caller $caller reaches the use case once: ${answer.status} ${answer.body}",
            ).isEqualTo(before + 1)
        }
        val any = operation.edges.any { it.substringBefore("->").trim() == "any" }
        val unnamed =
            if (any) {
                emptyList()
            } else {
                (doubles.services - allowed).map { it to doubles.tokenOf(it) } +
                    (doubles.lookalike to doubles.tokenOf(doubles.lookalike)) +
                    ("unmapped" to doubles.unmappedToken)
            }
        unnamed.forEach { (name, token) ->
            assertRefused(service, "$id caller $name") { service.call(handler, token, EndUserDoubles.USER_A) }
        }
        unnamed.firstOrNull()?.let { (name, token) ->
            assertRefused(service, "$id caller $name with X-Caller-Service of a named caller") {
                service.call(handler, token, EndUserDoubles.USER_A, mapOf("X-Caller-Service" to allowed.first()))
            }
            assertRefused(service, "$id caller $name without an end-user token") { service.call(handler, token) }
        }
        val before = service.counter.reaches.size
        val none = service.call(handler, null, EndUserDoubles.USER_A)
        assertThat(none.status).describedAs("$id without a workload token: ${none.body}").isEqualTo(401)
        assertThat(service.counter.reaches.size).describedAs("$id without a workload token").isEqualTo(before)
    }

    @Test
    fun `INV-AC-8455 every mapped operation answers the callers its x-sync-edge names and refuses the rest`() =
        assertEdges(document)

    @Test
    fun `INV-AC-8455 the main source sets hold no literal caller name passed to a caller check`() {
        val names = callersOf(ContractDocument.operations(document))
        val literal = Regex("\"(" + names.joinToString("|") { Regex.escape(it) } + ")\"")
        val check = Regex("""ServiceCaller|SyncEdgeCallerFilter|callerIs|X-Caller-Service""")
        val offenders =
            Files
                .list(Path.of(".."))
                .use { modules -> modules.filter { it.fileName.toString().startsWith("canonical-") }.toList() }
                .flatMap { module ->
                    val main = module.resolve("src/main")
                    if (!Files.isDirectory(main)) {
                        emptyList()
                    } else {
                        Files.walk(main).use { files ->
                            files.filter { it.toString().endsWith(".kt") }.toList()
                        }
                    }
                }.filter {
                    literal.containsMatchIn(
                        Files.readString(it),
                    ) || check.containsMatchIn(Files.readString(it))
                }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `INV-AC-8455 probe one caller of an x-sync-edge changed fails the test for that operation`() {
        val copy = document.replaceFirst("ingestion-svc->canonical-svc", "payload-svc->canonical-svc")
        assertThatThrownBy { assertEdges(copy) }.isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `INV-AC-8455 probe the sync-edge resource removed fails and names the operation`() {
        assertThatThrownBy { assertEdges(document, listOf(syncEdgeResource)) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("caller")
    }

    @Test
    fun `INV-AC-8455 the look-alike caller with a suffix is refused`() {
        val doubles = WorkloadDoubles(callersOf(ContractDocument.operations(document)))
        ServedService.run(doubles) { service ->
            val handler = service.handlers.first()
            assertRefused(service, "look-alike ${doubles.lookalike}") {
                service.call(handler, doubles.tokenOf(doubles.lookalike), EndUserDoubles.USER_A)
            }
        }
    }
}
