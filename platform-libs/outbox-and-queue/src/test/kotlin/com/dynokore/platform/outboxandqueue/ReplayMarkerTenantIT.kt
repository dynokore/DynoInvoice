package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * A `ReplayCompleted` marker that arrives under a tenant other than the platform tenant still records its marker and
 * completes the replay: the intake writes it in a platform-bound transaction of its own (INV-T-5306, owner ruling).
 * It runs on [ProducerReplayIT]'s scenario.
 */
@Tag("integration")
class ReplayMarkerTenantIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a ReplayCompleted under a non-platform tenant still records its marker and completes the replay`(
        image: String,
    ) {
        ProducerReplayIT().Scenario(image).use { s ->
            val tenant = TenantId(UUID.randomUUID())
            s.change(tenant, UUID.randomUUID(), 1)
            s.publishCanonical()
            s.purgeLive()
            assertTrue(s.behindConsumer.pollOnce().rebuildRequired)
            val replayId = s.request(ReplayScope.Tenant(tenant))
            s.publishReconciliation()
            s.responder().runOnce()
            s.publishCanonical()
            // the marker travels under tenant A, as a writer other than the platform one would publish it
            s.harness.fixture.runAsAdmin(
                "update eventlog.gst_canonical_replay_reconciliation_v1 set tenant_id = '${tenant.value}', " +
                    "envelope = envelope || jsonb_build_object('tenantid', '${tenant.value}'::text) " +
                    "where envelope->>'type' = '${ReplayProtocol.REPLAY_COMPLETED}'",
            )
            s.intake.pollOnce()
            assertEquals(listOf(replayId), s.harness.closed.map { it.replayId }, "marker recorded, replay completed")
        }
    }
}
