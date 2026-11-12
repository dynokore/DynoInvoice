package com.dynokore.gst.rules.adapters.packs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64

/** The SCHEMATRON contents decoder (INV-REQ-471). */
class SchematronPackContentsTest {
    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).let {
            java.util.HexFormat
                .of()
                .formatHex(it)
        }

    private fun file(
        name: String,
        text: String = "x",
        sha256: String = sha(text.toByteArray()),
        content: String = Base64.getEncoder().encodeToString(text.toByteArray()),
    ) = """{"name":"$name","sha256":"$sha256","content":"$content"}"""

    private fun pack(vararg files: String) = """{"version":"1.0","files":[${files.joinToString(",")}]}""".toByteArray()

    private fun failure(contents: ByteArray) =
        shouldThrow<PackContentsFailure> { SchematronPackContents.decode(contents) }

    @Test
    fun `a valid pack decodes byte for byte`() {
        val out = SchematronPackContents.decode(pack(file("a.xsl", "hello"), file("b.sch", "world")))
        out.version shouldBe "1.0"
        out.files.map { it.name } shouldBe listOf("a.xsl", "b.sch")
        String(out.files[0].content) shouldBe "hello"
    }

    @Test
    fun `a missing member is refused naming it`() {
        failure("""{"files":[]}""".toByteArray()).message shouldContain "$.version"
        failure("""{"version":"1"}""".toByteArray()).message shouldContain "$.files"
        failure("""{"version":"1","files":[{"name":"a.xsl","content":"eA=="}]}""".toByteArray()).message shouldContain
            "$.files[0].sha256"
    }

    @Test
    fun `a sha256 that is not 64 lowercase hex characters is refused`() {
        failure(pack(file("a.xsl", sha256 = sha("x".toByteArray()).uppercase()))).message shouldContain
            "$.files[0].sha256"
        failure(pack(file("a.xsl", sha256 = "abc"))).message shouldContain "$.files[0].sha256"
    }

    @Test
    fun `a content that is not base64 is refused`() {
        failure(pack(file("a.xsl", content = "!!!not base64"))).message shouldContain "$.files[0].content"
    }

    @Test
    fun `a repeated name is refused`() {
        failure(pack(file("a.xsl"), file("a.xsl"))).message shouldContain "$.files[1].name"
    }

    @Test
    fun `a sha256 that differs from the content is refused`() {
        failure(pack(file("a.xsl", sha256 = sha("other".toByteArray())))).message shouldContain "$.files[0].sha256"
    }
}
