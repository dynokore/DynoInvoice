package com.dynokore.gst.rules.bootstrap.gates

import org.junit.jupiter.api.Test

/** The Gradle check `acKeyCoverage` runs this class (and only this one) against the repository itself. */
class AcKeyCoverageGate {
    @Test
    fun `ac-keys-tsv covers the tests of this repository`() {
        val findings = AcKeyCoverage.check(RepositoryGates.read("ac-keys.tsv"), RepositoryGates.testSources())
        check(findings.isEmpty()) { "acKeyCoverage failed:\n" + findings.joinToString("\n") { " - $it" } }
    }
}
