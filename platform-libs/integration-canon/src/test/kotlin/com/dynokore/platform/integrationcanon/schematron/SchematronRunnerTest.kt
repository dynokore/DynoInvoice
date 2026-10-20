package com.dynokore.platform.integrationcanon.schematron

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** INV-Q-7068: the Schematron runner compiles once, is thread-safe, and refuses every external resource. */
class SchematronRunnerTest {
    private val svrl = "http://purl.oclc.org/dsdl/svrl"

    private class Counter : CompilationListener {
        val count = AtomicInteger()

        override fun compiled() {
            count.incrementAndGet()
        }
    }

    /** A stylesheet with [rules] asserts over the lines of an invoice; rule 1 fails on a negative amount. */
    private fun stylesheet(rules: Int): ByteArray {
        val body = StringBuilder()
        for (i in 1..rules) {
            val test = "every \$l in /invoice/line satisfies number(\$l/@amount) ge -$i"
            body.append(
                "<xsl:if test=\"not($test)\"><svrl:failed-assert id=\"R$i\" location=\"/invoice\" " +
                    "test=\"$test\" role=\"error\"><svrl:text>rule $i failed</svrl:text>" +
                    "</svrl:failed-assert></xsl:if>\n",
            )
        }
        return wrap(body.toString())
    }

    private fun wrap(body: String): ByteArray =
        (
            "<xsl:stylesheet version=\"3.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\" " +
                "xmlns:svrl=\"$svrl\" xmlns:sys=\"java:java.lang.System\">" +
                "<xsl:template match=\"/\"><svrl:schematron-output>$body</svrl:schematron-output></xsl:template>" +
                "</xsl:stylesheet>"
        ).toByteArray()

    private fun invoice(
        lines: Int,
        amount: Int = 10,
    ): ByteArray =
        buildString {
            append("<invoice>")
            for (i in 1..lines) append("<line n=\"$i\" amount=\"$amount\"/>")
            append("</invoice>")
        }.toByteArray()

    @Test
    fun `INV-AC-1500 INV-AC-2325 p95 within 50 ms after one compilation`() {
        val rules = 300
        val lines = 50
        val count = 2000
        val warmup = 200
        val counter = Counter()
        val compiled = SchematronRunner(counter).compile(stylesheet(rules))
        val corpus = List(count) { invoice(lines, 10 + it) }
        repeat(warmup) { compiled.validate(corpus[it % count]) }
        val nanos = LongArray(count)
        for (i in 0 until count) {
            val t0 = System.nanoTime()
            val outcome = compiled.validate(corpus[i])
            nanos[i] = System.nanoTime() - t0
            assertTrue(outcome.failedAsserts.isEmpty())
        }
        nanos.sort()
        val p95Ms = nanos[(count * 95 / 100) - 1] / 1_000_000.0
        println(
            "INV-AC-1500/2325 parameters: count=$count lines=$lines rules=$rules " +
                "processors=${Runtime.getRuntime().availableProcessors()} p95Ms=$p95Ms",
        )
        assertTrue(p95Ms <= 50.0, "p95 was $p95Ms ms")
        assertEquals(1, counter.count.get())
    }

    @Test
    fun `eight threads share one compiled set with the single thread outcome`() {
        val counter = Counter()
        val compiled = SchematronRunner(counter).compile(stylesheet(20))
        val docs = List(10) { invoice(5, if (it % 2 == 0) 10 else -5) }
        val expected = docs.map { compiled.validate(it) }
        assertTrue(expected.any { it.failedAsserts.isNotEmpty() })
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val mismatches = AtomicInteger()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Exception>()
        val tasks =
            List(8) { t ->
                pool.submit {
                    start.await()
                    repeat(1000) { n ->
                        val idx = (t + n) % docs.size
                        try {
                            if (compiled.validate(docs[idx]) != expected[idx]) mismatches.incrementAndGet()
                        } catch (e: Exception) {
                            errors.add(e)
                        }
                    }
                }
            }
        start.countDown()
        tasks.forEach { it.get(5, TimeUnit.MINUTES) }
        pool.shutdown()
        assertEquals(emptyList<Exception>(), errors.toList())
        assertEquals(0, mismatches.get())
        assertEquals(1, counter.count.get())
    }

    @Test
    fun `two compiled sets are held at once and each returns its own outcome`() {
        val runner = SchematronRunner()
        val first = runner.compile(stylesheet(3))
        val second =
            runner.compile(
                wrap(
                    "<svrl:failed-assert id=\"OTHER\" location=\"/x\" test=\"t\"><svrl:text>other</svrl:text>" +
                        "</svrl:failed-assert>",
                ),
            )
        val doc = invoice(2, -50)
        assertEquals(listOf("R1", "R2", "R3"), first.validate(doc).failedAsserts.map { it.id })
        assertEquals(listOf("OTHER"), second.validate(doc).failedAsserts.map { it.id })
        assertEquals(listOf("R1", "R2", "R3"), first.validate(doc).failedAsserts.map { it.id })
    }

    @Test
    fun `a failing document returns the expected failed asserts`() {
        val compiled = SchematronRunner().compile(stylesheet(3))
        val test = "every \$l in /invoice/line satisfies number(\$l/@amount) ge -"
        val expected =
            listOf(
                FailedAssert("R1", "/invoice", test + "1", "error", "rule 1 failed"),
                FailedAssert("R2", "/invoice", test + "2", "error", "rule 2 failed"),
            )
        assertEquals(expected, compiled.validate(invoice(1, -3)).failedAsserts)
        assertEquals(expected.take(1), compiled.validate(invoice(1, -2)).failedAsserts)
        assertTrue(compiled.validate(invoice(1, 5)).failedAsserts.isEmpty())
        val bare =
            SchematronRunner().compile(
                wrap("<svrl:failed-assert location=\"/a\" test=\"t\"><svrl:text>m</svrl:text></svrl:failed-assert>"),
            )
        assertEquals(listOf(FailedAssert(null, "/a", "t", null, "m")), bare.validate(invoice(1)).failedAsserts)
    }

    private fun refused(
        sheet: ByteArray,
        doc: ByteArray = invoice(1),
    ) = assertThrows(SchematronRefusedException::class.java) { SchematronRunner().compile(sheet).validate(doc) }

    @Test
    fun `document() of an http URL is refused and no request reaches the server`() {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            ex.sendResponseHeaders(200, 4)
            ex.responseBody.use { it.write("<a/>".toByteArray()) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/x"
            refused(wrap("<xsl:if test=\"exists(document('$url'))\"><a/></xsl:if>"))
            assertEquals(0, hits.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `document() of a file URI does not read the file`(
        @TempDir dir: File,
    ) {
        val secret = File(dir, "secret.xml").also { it.writeText("<s>secret-content-4711</s>") }
        val sheet = wrap("<xsl:value-of select=\"document('file:${secret.absolutePath}')\"/>")
        val ex = refused(sheet)
        assertFalse(ex.toString().contains("secret-content-4711"))
        assertFalse(
            ex.cause
                ?.toString()
                .orEmpty()
                .contains("secret-content-4711"),
        )
    }

    @Test
    fun `unparsed-text of a file URI is refused`(
        @TempDir dir: File,
    ) {
        val secret = File(dir, "secret.txt").also { it.writeText("secret-content-4711") }
        val ex = refused(wrap("<xsl:value-of select=\"unparsed-text('file:${secret.absolutePath}')\"/>"))
        assertFalse(ex.toString().contains("secret-content-4711"))
    }

    @Test
    fun `an extension function is refused at compile time`() {
        assertThrows(SchematronRefusedException::class.java) {
            SchematronRunner().compile(wrap("<xsl:value-of select=\"sys:getProperty('user.dir')\"/>"))
        }
    }

    @Test
    fun `result-document to a file is refused and no file is written`(
        @TempDir dir: File,
    ) {
        val target = File(dir, "out.xml")
        refused(wrap("<xsl:result-document href=\"file:${target.absolutePath}\"><a/></xsl:result-document>"))
        assertFalse(target.exists())
    }

    private fun xxe(secret: File) =
        "<?xml version=\"1.0\"?><!DOCTYPE invoice [<!ENTITY x SYSTEM \"file:${secret.absolutePath}\">]>" +
            "<invoice><line amount=\"1\"/>&x;</invoice>"

    @Test
    fun `an XXE payload is refused`(
        @TempDir dir: File,
    ) {
        val secret = File(dir, "secret.txt").also { it.writeText("secret-content-4711") }
        val ex = refused(stylesheet(1), xxe(secret).toByteArray())
        var t: Throwable? = ex
        while (t != null) {
            assertFalse(t.message.orEmpty().contains("secret-content-4711"))
            t = t.cause
        }
    }

    @Test
    fun `the XXE payload parsed with the hardened SAX factory throws and reaches no handler`(
        @TempDir dir: File,
    ) {
        val secret = File(dir, "secret.txt").also { it.writeText("secret-content-4711") }
        val seen = StringBuilder()
        val handler =
            object : org.xml.sax.helpers.DefaultHandler() {
                override fun characters(
                    ch: CharArray,
                    start: Int,
                    length: Int,
                ) {
                    seen.append(ch, start, length)
                }
            }
        val parser =
            com.dynokore.platform.integrationcanon.xml.HardenedXml
                .saxParserFactory()
                .also { it.isNamespaceAware = true }
                .newSAXParser()
        assertThrows(org.xml.sax.SAXParseException::class.java) {
            parser.parse(xxe(secret).toByteArray().inputStream(), handler)
        }
        assertFalse(seen.toString().contains("secret-content-4711"))
    }

    @Test
    fun `external functions are switched off`() {
        assertFalse(SchematronRunner().externalFunctionsAllowed())
    }

    private val xslt = "xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\""

    @Test
    fun `a stylesheet that is not valid XSLT is refused`() {
        assertThrows(SchematronRefusedException::class.java) {
            SchematronRunner().compile(
                "<xsl:stylesheet version=\"3.0\" $xslt><xsl:bogus/></xsl:stylesheet>".toByteArray(),
            )
        }
    }

    @Test
    fun `a dynamic error while validating is refused`() {
        refused(wrap("<xsl:message terminate=\"yes\">stop</xsl:message>"))
        refused(wrap("<xsl:value-of select=\"error()\"/>"))
    }

    @Test
    fun `a document that is not well-formed is refused`() {
        refused(stylesheet(1), "<invoice>".toByteArray())
    }

    @Test
    fun `a result without the SVRL root is refused`() {
        val writesA =
            "<xsl:stylesheet version=\"3.0\" $xslt><xsl:template match=\"/\"><a/></xsl:template></xsl:stylesheet>"
        val writesNothing =
            "<xsl:stylesheet version=\"3.0\" $xslt><xsl:template match=\"/\"/></xsl:stylesheet>"
        refused(writesA.toByteArray())
        refused(writesNothing.toByteArray())
    }

    @Test
    fun `a billion-laughs payload is refused`() {
        val laughs =
            "<?xml version=\"1.0\"?><!DOCTYPE invoice [<!ENTITY a \"lol\">" +
                "<!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\"><!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">" +
                "<!ENTITY d \"&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;\"><!ENTITY e \"&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;\">]>" +
                "<invoice><line amount=\"&e;\"/></invoice>"
        refused(stylesheet(1), laughs.toByteArray())
    }
}
