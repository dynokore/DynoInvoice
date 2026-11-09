package com.dynokore.platform.serviceruntime.tracing

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File

/**
 * INV-T-19009 (INV-AC-19004): the redundant standalone `aopalliance:aopalliance` jar is excluded from the impl runtime
 * classpath and the exclusion is published in the impl POM; the interfaces come from spring-aop.
 */
class AopallianceExcludedTest {
    private val bridge = "io.micrometer:micrometer-tracing-bridge-otel"
    private val wanted = "aopalliance:aopalliance"

    @Test
    fun `INV-AC-19004 the impl test runtime classpath holds no aopalliance jar`() {
        val entries = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotEmpty() }
        assertThat(entries).isNotEmpty()
        val jar = Regex("""aopalliance-[0-9][^/\\]*\.jar""")
        assertThat(entries.filter { jar.matches(it.substringAfterLast('/').substringAfterLast('\\')) }).isEmpty()
    }

    @Test
    fun `INV-AC-19004 the aopalliance interfaces load from spring-aop`() {
        val type = Class.forName("org.aopalliance.intercept.MethodInterceptor", false, javaClass.classLoader)
        val file = File(type.protectionDomain.codeSource.location.path).name
        assertThat(file).startsWith("spring-aop-").endsWith(".jar")
    }

    @Test
    fun `INV-AC-19004 the generated impl POM excludes aopalliance under the tracing bridge`() {
        val pom = pomFile(System.getProperty("gst.test.service-runtime-impl-pom"))
        assertThat(excludes(pom.readText(), bridge, wanted)).isTrue()
    }

    @Test
    fun `INV-AC-19004 the POM reader reports look-alike exclusions as not excluded`() {
        fun pom(
            dependency: String,
            exclusion: String?,
        ) = "<project><dependencies><dependency>" + dependency + (exclusion ?: "") +
            "</dependency></dependencies></project>"

        fun dep(
            group: String,
            artifact: String,
        ) = "<groupId>$group</groupId><artifactId>$artifact</artifactId>"

        fun ex(
            group: String,
            artifact: String,
        ) = "<exclusions><exclusion>${dep(group, artifact)}</exclusion></exclusions>"
        val bridgeDep = dep("io.micrometer", "micrometer-tracing-bridge-otel")
        // sanity: the reader does say yes for the exact exclusion
        assertThat(excludes(pom(bridgeDep, ex("aopalliance", "aopalliance")), bridge, wanted)).isTrue()
        val fixtures =
            listOf(
                pom(bridgeDep, ex("org.aopalliance", "aopalliance")),
                pom(bridgeDep, ex("aopalliance", "aopalliance-repackaged")),
                pom(bridgeDep, ex("aopalliance-x", "aopalliance")),
                pom(
                    dep("io.micrometer", "micrometer-tracing") + ex("aopalliance", "aopalliance") +
                        "</dependency><dependency>" + bridgeDep,
                    null,
                ),
                pom(bridgeDep, null),
                pom(dep("io.micrometer", "micrometer-tracing"), null),
            )
        fixtures.forEach { assertThat(excludes(it, bridge, wanted)).describedAs(it).isFalse() }
    }

    @Test
    fun `INV-AC-19004 an unset or missing POM path is an error, never a pass`() {
        assertThatThrownBy { pomFile(null) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { pomFile("") }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { pomFile("   ") }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            pomFile("/nonexistent/does-not-exist/pom-default.xml")
        }.isInstanceOf(IllegalStateException::class.java)
    }

    private fun pomFile(path: String?): File {
        check(!path.isNullOrBlank()) { "gst.test.service-runtime-impl-pom is unset or blank" }
        val file = File(path)
        check(file.isFile) { "the impl POM $path does not exist" }
        return file
    }

    /** True when the `<dependency>` of [dependency] (`group:artifact`) holds an `<exclusion>` equal to [exclusion]. */
    private fun excludes(
        pomText: String,
        dependency: String,
        exclusion: String,
    ): Boolean {
        val document =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(pomText.byteInputStream())

        fun Element.text(tag: String) =
            getElementsByTagNameNS("*", tag)
                .item(0)
                ?.textContent
                ?.trim()
                .orEmpty()
        val dependencies = document.getElementsByTagNameNS("*", "dependency")
        return (0 until dependencies.length)
            .map { dependencies.item(it) as Element }
            .filter { "${it.text("groupId")}:${it.text("artifactId")}" == dependency }
            .any { d ->
                val exclusions = d.getElementsByTagNameNS("*", "exclusion")
                (0 until exclusions.length)
                    .map { exclusions.item(it) as Element }
                    .any { "${it.text("groupId")}:${it.text("artifactId")}" == exclusion }
            }
    }
}
