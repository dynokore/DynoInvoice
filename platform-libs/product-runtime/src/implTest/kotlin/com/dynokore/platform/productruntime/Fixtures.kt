package com.dynokore.platform.productruntime

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

internal object Fixtures {
    val TENANT = TenantId(UUID.fromString("0a000000-0000-0000-0000-00000000000a"))
    val NOW: Instant = Instant.parse("2026-10-12T02:00:00Z")
    val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

    fun envelope(
        type: String,
        dataJson: String,
        tenant: TenantId = TENANT,
    ) = Envelope(
        EventId(UUID.randomUUID().toString()),
        "/gst/tenancy-svc",
        type,
        "agg-1",
        NOW,
        tenant,
        EntityRef("E-001"),
        null,
        null,
        null,
        "svc-1",
        CorrelationId("corr-1"),
        CausationId("cause-1"),
        dataJson,
    )
}
