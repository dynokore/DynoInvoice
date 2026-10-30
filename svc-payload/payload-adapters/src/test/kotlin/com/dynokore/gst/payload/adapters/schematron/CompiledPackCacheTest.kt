package com.dynokore.gst.payload.adapters.schematron

import com.dynokore.gst.payload.domain.StagedPackMissingException
import com.dynokore.gst.payload.domain.StagedPackSource
import com.dynokore.platform.integrationcanon.schematron.CompilationListener
import com.dynokore.platform.integrationcanon.schematron.SchematronRunner
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class CompiledPackCacheTest {
    private val svrl = "http://purl.oclc.org/dsdl/svrl"

    // Already-XSLT stylesheet: one failed-assert when the document root has no attribute `ok`.
    private fun stylesheet(marker: String) =
        """<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
             <xsl:template match="/*"><schematron-output xmlns="$svrl">
               <xsl:if test="not(@ok)"><failed-assert id="$marker" location="/*" test="@ok"><text>missing ok</text></failed-assert></xsl:if>
             </schematron-output></xsl:template></xsl:stylesheet>""".toByteArray()

    private class FakeSource(
        private val staged: Map<String, ByteArray>,
    ) : StagedPackSource {
        val calls = mutableListOf<String>()

        override fun fetch(packVersion: String): ByteArray? {
            calls += packVersion
            return staged[packVersion]
        }
    }

    private val compilations = AtomicInteger()
    private val runner = SchematronRunner(CompilationListener { compilations.incrementAndGet() })
    private val bad = "<doc/>".toByteArray()
    private val good = "<doc ok='1'/>".toByteArray()

    @Test
    fun `INV-AC-1500 INV-AC-2325 one activation and 1000 validations cause exactly one compilation`() {
        val cache = CompiledPackCache(runner, FakeSource(emptyMap()))
        cache.activate("v1", stylesheet("R1"))
        repeat(1000) { cache.validate("v1", bad) }
        compilations.get() shouldBe 1
        cache.validate("v1", bad).failedAsserts.map { it.id } shouldBe listOf("R1")
        cache.validate("v1", good).passed shouldBe true
    }

    @Test
    fun `a miss asks the source once and a held version never`() {
        val source = FakeSource(mapOf("v1" to stylesheet("R1"), "v2" to stylesheet("R2")))
        val cache = CompiledPackCache(runner, source)
        cache.activate("v1", stylesheet("R1"))
        cache.validate("v1", bad)
        source.calls shouldHaveSize 0
        repeat(3) { cache.validate("v2", bad) }
        source.calls shouldBe listOf("v2")
        compilations.get() shouldBe 2
    }

    @Test
    fun `two versions are held at once`() {
        val source = FakeSource(mapOf("v2" to stylesheet("R2")))
        val cache = CompiledPackCache(runner, source)
        cache.activate("v1", stylesheet("R1"))
        cache.validate("v2", bad).failedAsserts.map { it.id } shouldBe listOf("R2")
        cache.validate("v1", bad).failedAsserts.map { it.id } shouldBe listOf("R1")
        cache.validate("v2", bad).failedAsserts.map { it.id } shouldBe listOf("R2")
        source.calls shouldBe listOf("v2")
    }

    @Test
    fun `a null from the source throws and the next validate asks the source again`() {
        val source = FakeSource(emptyMap())
        val cache = CompiledPackCache(runner, source)
        repeat(2) {
            val e = assertThrows(StagedPackMissingException::class.java) { cache.validate("v9", bad) }
            e.packVersion shouldBe "v9"
        }
        source.calls shouldBe listOf("v9", "v9")
        compilations.get() shouldBe 0
    }
}
