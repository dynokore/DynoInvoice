package com.dynokore.platform.serviceruntime.replica

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/** INV-Q-9065 A: the resource loader and the counter. */
class DegradedAllowResourceTest {
    @TempDir
    lateinit var dir: Path

    private fun loader(vararg contents: String?): ClassLoader {
        val roots =
            contents.mapIndexed { i, text ->
                val root = Files.createDirectories(dir.resolve("r$i"))
                if (text != null) {
                    Files.createDirectories(root.resolve("META-INF/gst"))
                    Files.writeString(root.resolve(DegradedAllowResource.RESOURCE), text)
                }
                root.toUri().toURL()
            }
        return URLClassLoader(roots.toTypedArray(), null)
    }

    private val h = DegradedAllowResource.HEADER

    private fun marks(
        table: DegradedAllowTable,
        method: String,
        template: String,
    ) = table.marks(OperationKey(method, template, "ingest"))

    @Test
    fun `a valid resource marks exactly its rows`() {
        val table =
            DegradedAllowResource.load(
                loader("$h\nAddBatch\tGET\t/v1/batches/{batchId}\nAddTx\tPOST\t/v1/transactions\n"),
            )
        marks(table, "POST", "/v1/transactions") shouldBe true
        marks(table, "GET", "/v1/batches/{batchId}") shouldBe true
        marks(table, "POST", "/v10/transactions") shouldBe false
        marks(table, "post", "/v1/transactions") shouldBe false
    }

    @Test
    fun `no resource gives the empty table and does not fail`() {
        marks(DegradedAllowResource.load(loader(null)), "POST", "/v1/transactions") shouldBe false
    }

    @Test
    fun `a malformed line stops the start-up naming the line and never the content`() {
        for (bad in listOf(
            "B\tPOST\n",
            "B\tPOST\t/a\textra\n",
            "B\tpost\t/a\n",
            "B\tPOST\ta\n",
            "B\tPOST\t/a b\n",
            "1B\tPOST\t/a\n",
            "\n",
            "B\tPOST\t/a\r\n",
        )) {
            val failure =
                shouldThrow<IllegalStateException> { DegradedAllowResource.load(loader("$h\nA\tPOST\t/ok\n$bad")) }
            failure.message!! shouldContain "line 3"
            failure.message!! shouldNotContain "/ok"
        }
    }

    @Test
    fun `rows out of operationId order stop the start-up`() {
        shouldThrow<IllegalStateException> { DegradedAllowResource.load(loader("$h\nB\tPOST\t/b\nA\tPOST\t/a\n")) }
            .message!! shouldContain "sorted"
    }

    @Test
    fun `a missing or altered header stops the start-up at line 1`() {
        for (bad in listOf("A\tPOST\t/a\n", "operationId\tmethod\n", "\n", "")) {
            shouldThrow<IllegalStateException> { DegradedAllowResource.load(loader(bad)) }.message!! shouldContain
                "line 1"
        }
    }

    @Test
    fun `a repeated line stops the start-up`() {
        shouldThrow<IllegalStateException> { DegradedAllowResource.load(loader("$h\nA\tPOST\t/a\nB\tPOST\t/a\n")) }
            .message!! shouldContain "line 3"
        shouldThrow<IllegalStateException> { DegradedAllowResource.load(loader("$h\nA\tPOST\t/a\nA\tPOST\t/b\n")) }
            .message!! shouldContain "line 3"
        shouldThrow<IllegalStateException> {
            DegradedAllowResource.load(loader("$h\nA\tPOST\t/a\n", "$h\nA\tPOST\t/a\n"))
        }.message!! shouldContain "repeats"
    }

    @Test
    fun `the counter is labelled by operation and counts each allow`() {
        val meters = SimpleMeterRegistry()
        val observer = MicrometerDegradedAllowObserver(meters)
        val allow =
            DegradedAllow(
                OperationKey("POST", "/v1/transactions", "ingest"),
                com.dynokore.platform.tenancykernel.TenantId.PLATFORM,
                "c",
                "E1",
                null,
            )
        repeat(3) { observer.onAllow(allow) }
        meters
            .get(
                MicrometerDegradedAllowObserver.METER_NAME,
            ).tag("operation", "POST /v1/transactions")
            .counter()
            .count() shouldBe
            3.0
    }
}
