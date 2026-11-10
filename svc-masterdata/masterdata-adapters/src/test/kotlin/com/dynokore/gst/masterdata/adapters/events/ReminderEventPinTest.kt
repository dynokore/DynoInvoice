package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * INV-T-19569: the pinned platform libraries (BOM 0.188.0: outbox-and-queue 6.20.0, product-runtime 2.17.0) accept
 * `ConnectionCredentialExpiryReminderRequested` as a platform event, tenant-wide under a real tenant (INV-REQ-837).
 */
class ReminderEventPinTest {
    private val platformType = "com.dynokore.gst.platform.ConnectionCredentialExpiryReminderRequested.v1"
    private val masterdataType = "com.dynokore.gst.masterdata.ConnectionCredentialExpiryReminderRequested.v1"
    private val lookAlike = "com.dynokore.gst.platform.ConnectionCredentialExpiryReminderRequest.v1"
    private val tenant = TenantId(UUID.randomUUID())

    private fun envelope(type: String) =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/masterdata-svc",
            type = type,
            subject = "s-1",
            time = Instant.parse("2026-11-10T00:00:00Z"),
            tenant = tenant,
            entity = EntityRef.TENANT_WIDE,
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "masterdata-svc-1",
            correlation = CorrelationId("c"),
            causation = CausationId("k"),
            dataJson = """{"v":1}""",
        )

    @Test
    fun `INV-AC-19570 the platform type is built tenant-wide under a real tenant`() {
        val e = envelope(platformType)
        assertThat(e.type).isEqualTo(platformType)
        assertThat(e.tenant).isEqualTo(tenant)
        assertThat(e.entity).isEqualTo(EntityRef.TENANT_WIDE)
    }

    @Test
    fun `INV-AC-19570 the masterdata-namespace type is refused tenant-wide`() {
        assertThatThrownBy { envelope(masterdataType) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `INV-AC-19570 isPlatform is true for the platform type and false for the masterdata type and the look-alike`() {
        assertThat(PlatformEventCatalogue.isPlatform(platformType)).isTrue()
        assertThat(PlatformEventCatalogue.isPlatform(masterdataType)).isFalse()
        assertThat(PlatformEventCatalogue.isPlatform(lookAlike)).isFalse()
    }

    @Test
    fun `INV-AC-19570 the catalogue holds the platform type on its fixed topic`() {
        assertThat(PlatformEventCatalogue.ALL.filter { it.eventType == platformType })
            .singleElement()
            .extracting { it.topic }
            .isEqualTo("gst.platform.connection-credential-expiry-reminder-requested.v1")
    }
}
