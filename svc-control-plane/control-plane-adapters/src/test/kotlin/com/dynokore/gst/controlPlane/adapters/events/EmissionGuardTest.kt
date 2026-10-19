package com.dynokore.gst.controlPlane.adapters.events

import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EmissionGuardTest {
    private val guard = ControlPlaneEventCatalogue.guard()

    @Test
    fun `a platform event type catalogued for every service is permitted`() {
        guard.require(PlatformEventCatalogue.AUDIT_ENTRY_RECORDED.eventType).eventType shouldBe
            PlatformEventCatalogue.AUDIT_ENTRY_RECORDED.eventType
    }

    @Test
    fun `an event type that is not catalogued for control-plane is refused`() {
        shouldThrow<EmissionNotPermitted> { guard.require("com.dynokore.gst.control-plane.ExampleHappened.v1") }
        shouldThrow<EmissionNotPermitted> { guard.require("com.dynokore.gst.control-plane.Uncatalogued.v1") }
    }

    @Test
    fun `a look-alike of a platform event type is refused`() {
        shouldThrow<EmissionNotPermitted> { guard.require("com.dynokore.gst.platform.AuditEntryRecorded.v2") }
        shouldThrow<EmissionNotPermitted> { guard.require("com.dynokore.gst.platform.auditentryrecorded.v1") }
    }
}
