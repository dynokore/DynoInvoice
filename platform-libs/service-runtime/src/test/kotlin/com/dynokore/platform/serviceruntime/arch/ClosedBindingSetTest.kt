package com.dynokore.platform.serviceruntime.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withParentInterfaceNamed
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * INV-ADR-276: adding a ninth SDS §12.1 binding is a design change. This module
 * cannot mechanically check the whole closed set — three of the eight names live
 * in other modules entirely (`Binding.kt`'s own KDoc) — but it can check its own
 * half: every interface this module declares as a [com.dynokore.platform.serviceruntime.binding.Binding]
 * is one of FR-H.1's eight names, so an accidental ninth interface added here,
 * under that marker, fails this test until the merge request that adds it also
 * updates this list (the actual review gate INV-ADR-276 asks for).
 */
class ClosedBindingSetTest {
    private val frH1Names =
        setOf(
            "IdentityProvider",
            "ObjectStore",
            "SecretStore",
            "PackSource",
            "TelemetryExport",
            "LicenceCheck",
            "EventBinding",
            "MessageDelivery",
        )

    @Test
    fun `every Binding-marked interface in this module is one of FR-H1's eight names`() {
        Konsist
            .scopeFromModule("service-runtime", "main")
            .interfaces()
            .withParentInterfaceNamed("Binding")
            .assertTrue { it.name in frH1Names }
    }
}
