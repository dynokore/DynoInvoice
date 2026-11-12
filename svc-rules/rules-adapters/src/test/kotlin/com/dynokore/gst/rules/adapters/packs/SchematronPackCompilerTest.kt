package com.dynokore.gst.rules.adapters.packs

import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.platform.integrationcanon.schematron.CompilationListener
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.fence.CompileOutcome
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** The PackCompiler hook of a SCHEMATRON pack (INV-AC-20472). */
class SchematronPackCompilerTest {
    private val svrl = "http://purl.oclc.org/dsdl/svrl"

    private fun stylesheet(assertTest: String = "y") =
        """<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:svrl="$svrl">
          <xsl:template match="/">
            <svrl:schematron-output>
              <xsl:if test="not(/doc/$assertTest)">
                <svrl:failed-assert id="A1" test="$assertTest" location="/doc">
                  <svrl:text>y missing</svrl:text>
                </svrl:failed-assert>
              </xsl:if>
            </svrl:schematron-output>
          </xsl:template>
        </xsl:stylesheet>"""

    private val manifest =
        PackManifest(
            PackId("sch"),
            PackTypes.SCHEMATRON,
            PackVersionLabel("1"),
            LocalDate.of(2026, 1, 1),
            null,
            "1.0.0",
        )

    private fun file(
        name: String,
        text: String,
        sha256: String? = null,
    ): String {
        val bytes = text.toByteArray()
        val digest =
            sha256 ?: MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return """{"name":"$name","sha256":"$digest","content":"${Base64.getEncoder().encodeToString(bytes)}"}"""
    }

    private fun pack(vararg files: String) = """{"version":"1.0","files":[${files.joinToString(",")}]}""".toByteArray()

    private val count = AtomicInteger()
    private val compiler = SchematronPackCompiler(CompilationListener { count.incrementAndGet() })

    private fun compiled(contents: ByteArray) =
        compiler
            .compile(manifest, contents)
            .shouldBeInstanceOf<CompileOutcome.Compiled<CompiledPackSchematron>>()
            .compiled

    private fun failed(contents: ByteArray) =
        compiler.compile(manifest, contents).shouldBeInstanceOf<CompileOutcome.Failed>().reason

    @Test
    fun `INV-AC-20472 the listener count equals the stylesheets after compile and after 100 validations`() {
        val result =
            compiled(pack(file("a.xsl", stylesheet()), file("b.xslt", stylesheet()), file("c.sch", "<sch/>")))
        count.get() shouldBe 2
        result.stylesheets.keys shouldBe setOf("a.xsl", "b.xslt")
        repeat(100) { result.stylesheets.getValue("a.xsl").validate("<doc><y/></doc>".toByteArray()) }
        count.get() shouldBe 2
    }

    @Test
    fun `INV-AC-20472 a stylesheet that does not compile gives Failed naming the file and no result`() {
        val reason = failed(pack(file("good.xsl", stylesheet()), file("bad.xsl", "<not-a-stylesheet")))
        reason shouldContain "bad.xsl"
    }

    @Test
    fun `INV-AC-20472 a pack without a stylesheet gives Failed`() {
        failed(pack(file("rules.sch", "<sch/>"))) shouldContain "no stylesheet"
        count.get() shouldBe 0
    }

    @Test
    fun `INV-AC-20472 a sha256 mismatch gives Failed naming the file`() {
        failed(pack(file("a.xsl", stylesheet(), sha256 = "0".repeat(64)))) shouldContain "a.xsl"
        count.get() shouldBe 0
    }

    @Test
    fun `INV-AC-20472 a sch file and the look-alikes rules XSL and rules xsl txt are not compiled`() {
        val reason =
            failed(
                pack(
                    file("rules.sch", stylesheet()),
                    file("rules.XSL", stylesheet()),
                    file("rules.xsl.txt", stylesheet()),
                ),
            )
        reason shouldContain "no stylesheet"
        count.get() shouldBe 0
        val result =
            compiled(
                pack(file("a.xsl", stylesheet()), file("rules.sch", stylesheet()), file("rules.XSL", stylesheet())),
            )
        result.stylesheets.keys shouldBe setOf("a.xsl")
        count.get() shouldBe 1
    }

    @Test
    fun `INV-AC-20472 a document that violates an assertion yields a failed assert with its location`() {
        val sheet = compiled(pack(file("a.xsl", stylesheet()))).stylesheets.getValue("a.xsl")
        sheet.validate("<doc><y/></doc>".toByteArray()).failedAsserts.shouldBeEmpty()
        val asserts = sheet.validate("<doc/>".toByteArray()).failedAsserts
        asserts.size shouldBe 1
        asserts[0].location shouldBe "/doc"
        asserts[0].id shouldBe "A1"
    }
}
