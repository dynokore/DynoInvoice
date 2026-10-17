package com.dynokore.platform.integrationcanon

import com.dynokore.platform.integrationcanon.Fixtures.a
import com.dynokore.platform.integrationcanon.Fixtures.b
import com.dynokore.platform.integrationcanon.Fixtures.calendar
import com.dynokore.platform.integrationcanon.Fixtures.d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Consumer-driven contract test of the module API (the ModuleApiConsumerContractTest pattern): a stub written the way
 * `payload-svc` writes its payload-building use case — it resolves the required version itself, selects, checks and
 * projects through the public surface only — run against the real implementation. `payload-svc` does not exist yet:
 * the stub plays its role.
 */
class ModuleApiConsumerContractTest {
    private data class Canonical(
        val amount: Int,
    )

    private class PayloadSvcStub {
        private val projections =
            ProjectionRegistry
                .builder<Canonical, String>()
                .register(a) { "A|${it.amount}" }
                .register(b) { "B|${it.amount}" }
                .build()

        fun build(
            required: SpecVersion,
            submissionDate: java.time.LocalDate,
            canonical: Canonical,
            removedIn: Set<SpecVersion>,
        ): String =
            when (
                val r =
                    selectRepresentable(calendar, required, submissionDate) {
                        if (it in
                            removedIn
                        ) {
                            RepresentabilityAnswer.Unrepresentable("removed")
                        } else {
                            RepresentabilityAnswer.Representable
                        }
                    }
            ) {
                is RepresentableSelection.Selected -> projections.project(r.version, canonical)
                is RepresentableSelection.Blocked -> "BLOCKED:${r.version}:${r.reason}"
                is RepresentableSelection.NoAcceptedVersion -> "NONE:${r.acceptedOnDate}"
            }
    }

    private val svc = PayloadSvcStub()

    @Test
    fun `a correction after deprecation goes in the oldest accepted version`() {
        assertEquals("B|7", svc.build(a, d(2026, 3, 1), Canonical(7), emptySet()))
        assertEquals("A|7", svc.build(a, d(2025, 7, 1), Canonical(7), emptySet()))
    }

    @Test
    fun `an unrepresentable selected version blocks the document`() {
        assertEquals("BLOCKED:spec-b:removed", svc.build(a, d(2026, 3, 1), Canonical(7), setOf(b)))
    }
}
