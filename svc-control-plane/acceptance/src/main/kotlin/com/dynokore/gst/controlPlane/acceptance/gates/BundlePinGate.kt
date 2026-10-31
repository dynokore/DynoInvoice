package com.dynokore.gst.controlPlane.acceptance.gates

/**
 * The bundle pin gate (INV-T-12889, `bundlePinGate`): the value of `platformContractsBundle` in
 * `gradle/libs.versions.toml` equals the single line of the reviewed record `bundle-pin.txt`, so a pin changed in
 * one file only (for example by a dependency-update bot) fails. Fails closed; strings are compared with `==`, with no
 * trimming and no case folding. It fails when
 * - (a) the record is missing or blank;
 * - (b) the record is not exactly one line ending in one newline;
 * - (c) the versions file is missing or blank;
 * - (d) the number of lines that assign `platformContractsBundle` is not 1;
 * - (e) the quoted value differs from the record line.
 */
object BundlePinGate {
    const val NAME = "bundle-pin.txt"
    private const val KEY = "platformContractsBundle"
    private val ASSIGNMENT = Regex("^$KEY = \"([^\"]*)\"$", RegexOption.MULTILINE)
    private val ONE_LINE = Regex("[^\n]*\n")

    fun check(
        versions: String?,
        record: String?,
    ): List<Finding> {
        if (record.isNullOrBlank()) return listOf(Finding(NAME, NAME, "the pin record is missing or empty"))
        val findings = ArrayList<Finding>()
        val oneLine = ONE_LINE.matches(record)
        if (!oneLine) findings += Finding(NAME, NAME, "the pin record holds exactly one line")
        if (versions.isNullOrBlank()) {
            return findings + Finding(NAME, "gradle/libs.versions.toml", "gradle/libs.versions.toml is missing or empty")
        }
        val values = ASSIGNMENT.findAll(versions).map { it.groupValues[1] }.toList()
        if (values.size != 1) {
            return findings + Finding(NAME, KEY, "$KEY is assigned ${values.size} times, expected 1")
        }
        val line = record.removeSuffix("\n")
        if (oneLine && values.single() != line) {
            findings +=
                Finding(
                    NAME,
                    KEY,
                    "${values.single()} in gradle/libs.versions.toml differs from $line in bundle-pin.txt",
                )
        }
        return findings
    }
}
