package com.dynokore.gst.tenancy.adapters.certification

import com.dynokore.platform.outboxandqueue.Envelope
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-REQ-1135 / INV-REQ-1226: the pinned platform-libs BOM carries the envelope class that knows CertificationMarkRequested. */
class PlatformEventClassPinTest {
    @Test
    fun `INV-AC-18188 the envelope class accepts CertificationMarkRequested as a platform type`() {
        Envelope.isPlatformType("com.dynokore.gst.platform.CertificationMarkRequested.v1") shouldBe true
        Envelope.isPlatformType("com.dynokore.gst.tenancy.CertificationMarkRequested.v1") shouldBe false
        Envelope.isPlatformType("com.dynokore.gst.platform.CertificationMarkRequest.v1") shouldBe false
    }
}
