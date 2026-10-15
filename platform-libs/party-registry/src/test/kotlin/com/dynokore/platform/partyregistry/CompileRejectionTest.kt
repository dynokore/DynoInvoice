package com.dynokore.platform.partyregistry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * INV-REQ-803, INV-REQ-314: the compiler itself rejects the confusions. Each sample is compiled with the Kotlin
 * compiler the build resolves, against the module's api classes; the positive control must compile, so a rejection
 * cannot come from a broken harness.
 */
class CompileRejectionTest {
    private val preamble = "import com.dynokore.platform.partyregistry.*\nimport java.time.Instant\n"

    private fun compile(
        dir: File,
        body: String,
    ): Pair<Int, String> {
        val src = File(dir, "Sample.kt").also { it.writeText(preamble + body) }
        val api = System.getProperty("partyregistry.api.classes")
        val cp = listOf(api, System.getProperty("partyregistry.stdlib")).joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/java").path
        val proc =
            ProcessBuilder(
                java,
                "-cp",
                System.getProperty("partyregistry.compiler.classpath"),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib",
                "-no-reflect",
                "-cp",
                cp,
                "-d",
                File(dir, "out").path,
                src.path,
            ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(240, TimeUnit.SECONDS))
        return proc.exitValue() to out
    }

    @Test
    fun `positive control compiles`(
        @TempDir dir: File,
    ) {
        val (code, out) =
            compile(
                dir,
                "fun need(s: SupplierIdentity) = s\n" +
                    "fun ok() = need(SupplierIdentity(SenderId(\"a\"), SupplierId(\"b\"), SupplierGstn(\"c\")))\n" +
                    "fun p() = Provenance.declared(ProvidedBy.INTERNAL, EvidenceRef(\"e\"), Instant.EPOCH)\n",
            )
        assertEquals(0, code, out)
    }

    @Test
    fun `a SubmittingIdentity where a SupplierIdentity is required does not compile`(
        @TempDir dir: File,
    ) {
        val (code, out) =
            compile(
                dir,
                "fun need(s: SupplierIdentity) = s\n" +
                    "fun bad(s: SubmittingIdentity) = need(s)\n",
            )
        assertNotEquals(0, code, out)
        assertTrue(out.contains("error:") && out.contains("SubmittingIdentity"), out)
    }

    @Test
    fun `a SenderId where a SupplierId is required does not compile`(
        @TempDir dir: File,
    ) {
        val (code, out) = compile(dir, "fun need(s: SupplierId) = s\nfun bad(s: SenderId) = need(s)\n")
        assertNotEquals(0, code, out)
    }

    @Test
    fun `an identifier without provenance does not compile`(
        @TempDir dir: File,
    ) {
        val (code, out) = compile(dir, "fun bad() = Identifier(IdentifierScheme.UEN, \"x\")\n")
        assertNotEquals(0, code, out)
        val (nullCode, nullOut) = compile(dir, "fun bad() = Identifier(IdentifierScheme.UEN, \"x\", null)\n")
        assertNotEquals(0, nullCode, nullOut)
    }

    @Test
    fun `a Provenance cannot be built directly, so MIGRATED has one route`(
        @TempDir dir: File,
    ) {
        val (code, out) =
            compile(
                dir,
                "fun bad() = Provenance(ProvidedBy.MIGRATED, EvidenceRef(\"e\"), Instant.EPOCH)\n",
            )
        assertNotEquals(0, code, out)
    }
}
