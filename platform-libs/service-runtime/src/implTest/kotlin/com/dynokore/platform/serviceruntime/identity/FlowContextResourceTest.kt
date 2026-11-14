package com.dynokore.platform.serviceruntime.identity

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

class FlowContextResourceTest {
    @Test
    fun `INV-AC-8251 the resources of the class path are merged and an absent resource lists nothing`(
        @TempDir dir: Path,
    ) {
        val a = Files.createDirectories(dir.resolve("a/META-INF/gst"))
        val b = Files.createDirectories(dir.resolve("b/META-INF/gst"))
        Files.writeString(a.resolve("flow-context-operations.tsv"), "operationId\tmethod\tpath\none\tPOST\t/v1/one\n")
        Files.writeString(b.resolve("flow-context-operations.tsv"), "operationId\tmethod\tpath\ntwo\tPOST\t/v1/two\n")
        val loader = URLClassLoader(arrayOf(dir.resolve("a").toUri().toURL(), dir.resolve("b").toUri().toURL()), null)
        val operations = FlowContextOperations.from(loader)
        operations.serving("POST", "/v1/one") shouldBe "POST /v1/one"
        operations.serving("POST", "/v1/two") shouldBe "POST /v1/two"
        FlowContextOperations.from(URLClassLoader(emptyArray(), null)).serving("POST", "/v1/one") shouldBe null
    }
}
