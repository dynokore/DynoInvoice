package com.dynokore.gst.tenancy.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The platform-libs BOM pin must carry the installation-scope decision of service-runtime 11.0.0, which the BOM has
 * from 0.171.0 (INV-T-4933, bullet Permission keys, BOM pin). Compared by numeric dot-separated segments, so the
 * text-order look-alike 0.9.0 is below 0.171.0.
 */
class PlatformLibsBomPinTest {
    @Test
    fun `the platformLibsBom entry of libs_versions_toml is at or above 0_171_0`() {
        val toml = Files.readString(Path.of("..", "gradle", "libs.versions.toml"))
        assertThat(atLeastMinimum(pinOf(toml))).isTrue()
    }

    @Test
    fun `the pin check refuses 0_161_0, 0_170_0, 0_9_0 and look-alikes and accepts 0_171_0 and 0_174_0`() {
        listOf("0.161.0", "0.170.0", "0.9.0", "0.171", "0.171.0-SNAPSHOT", "0.171.0 ", "", "x.171.0").forEach {
            assertThat(atLeastMinimum(it)).describedAs(it).isFalse()
        }
        listOf("0.171.0", "0.174.0", "0.200.1", "1.0.0").forEach {
            assertThat(atLeastMinimum(it)).describedAs(it).isTrue()
        }
        assertThat(pinOf("[versions]\nplatformLibsBom = \"0.174.0\"\n")).isEqualTo("0.174.0")
        assertThat(pinOf("[versions]\nother = \"0.174.0\"\n")).isNull()
    }

    private fun pinOf(toml: String): String? =
        Regex("""(?m)^platformLibsBom\s*=\s*"([^"]*)"\s*$""").find(toml)?.groupValues?.get(1)

    private fun atLeastMinimum(version: String?): Boolean {
        val found = Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(version.orEmpty()) ?: return false
        val parts = found.groupValues.drop(1).map { it.toInt() }
        return compareValuesBy(parts, FLOOR, { it[0] }, { it[1] }, { it[2] }) >= 0
    }

    private companion object {
        val FLOOR = listOf(0, 171, 0)
    }
}
