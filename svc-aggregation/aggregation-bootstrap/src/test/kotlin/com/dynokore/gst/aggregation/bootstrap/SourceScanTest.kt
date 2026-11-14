package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.syncedge.SourceScan
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The negative fixtures of the main-source scan of INV-AC-8455: it must report a bad tree, or "0 findings" proves nothing. */
class SourceScanTest {
    private val names = setOf("ingestion-svc")

    private fun tree(
        root: Path,
        source: String,
    ): Path {
        val main = Files.createDirectories(root.resolve("some-module/src/main/kotlin"))
        Files.writeString(main.resolve("Probe.kt"), source)
        return root
    }

    @Test
    fun `INV-AC-8455 the scan reports a literal passed to a caller check`(
        @TempDir root: Path,
    ) {
        val found = SourceScan.findings(tree(root, "val x = ServiceCaller(\"other-svc\")\n"), names)
        found shouldHaveSize 1
    }

    @Test
    fun `INV-AC-8455 the scan reports a bare literal caller name`(
        @TempDir root: Path,
    ) {
        val found = SourceScan.findings(tree(root, "val x = \"ingestion-svc\"\n"), names)
        found shouldHaveSize 1
    }

    @Test
    fun `INV-AC-8455 the scan does not report a caller name held only in a comment`(
        @TempDir root: Path,
    ) {
        SourceScan
            .findings(
                tree(
                    root,
                    "// ingestion-svc calls us\n/* \"ingestion-svc\" /* nested */ \"ingestion-svc\" */\nval y = 1\n",
                ),
                names,
            ).shouldBeEmpty()
    }
}
