package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.platform.outboxandqueue.EmissionGuard
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.productruntime.ProductEventTypes
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test

/** The emission guard accepts what tenancy-svc produces and rejects an uncatalogued type (TPL-05, API-43). */
class TenancyEventCatalogueTest {
    private val catalogue = TenancyEventCatalogue.load()
    private val guard = EmissionGuard(catalogue, TenancyEventCatalogue.SERVICE)

    @Test
    fun `the guard accepts the structure event and the types the contract catalogues for tenancy`() {
        guard.require(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED)
        guard.require("com.dynokore.gst.tenancy.EntityProfileChanged.v1")
    }

    @Test
    fun `the guard rejects an uncatalogued type, the placeholder and a look-alike`() {
        listOf(
            "com.dynokore.gst.tenancy.NotInTheCatalogue.v1",
            "com.dynokore.gst.tenancy.ExampleHappened.v1",
            "com.dynokore.gst.tenancy.organisationstructurechanged.v1",
            "",
        ).forEach { type -> shouldThrow<EmissionNotPermitted> { guard.require(type) } }
    }

    @Test
    fun `another service's guard over the same catalogue does not let it emit the structure event`() {
        shouldThrow<EmissionNotPermitted> {
            EmissionGuard(catalogue, "access-svc").require(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED)
        }
    }
}
