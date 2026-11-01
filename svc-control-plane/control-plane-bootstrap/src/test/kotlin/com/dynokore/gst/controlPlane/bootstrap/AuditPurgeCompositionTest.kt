package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.audit.UndeterminedRetentionFloorSource
import com.dynokore.gst.controlPlane.application.audit.AuditPurgeAuthority
import com.dynokore.gst.controlPlane.application.audit.AuditPurgeFloorSource
import com.dynokore.gst.controlPlane.application.audit.AuditPurgeIdSource
import com.dynokore.gst.controlPlane.application.audit.PurgeAuditEntries
import com.dynokore.gst.controlPlane.application.audit.PurgeAuditEntriesCommand
import com.dynokore.gst.controlPlane.application.audit.PurgeAuditOutcome
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * TPL-18 (P109): the purge use case is built by the service's own composition. Nothing here reaches a database: the
 * authority and the floor decide before the purger runs.
 */
class AuditPurgeCompositionTest {
    private val command =
        PurgeAuditEntriesCommand(
            "operator-1",
            Instant.now().minus(4000, ChronoUnit.DAYS),
            null,
            RequestContext("corr", "cause"),
        )

    @Test
    fun `INV-AC-3823 with no authorise binding every call is NotPermitted`(
        @TempDir mount: Path,
    ) {
        controlPlaneRunner(mount).run { context ->
            context.startupFailure shouldBe null
            context.getBean(PurgeAuditEntries::class.java).execute(command) shouldBe PurgeAuditOutcome.NotPermitted
            context
                .getBean(
                    AuditPurgeAuthority::class.java,
                ).allows("operator-1", "audit-entry.purge", "platform") shouldBe
                false
        }
    }

    @Test
    fun `INV-AC-3823 the composition binds the undeterminable floor source and a UUID id source`(
        @TempDir mount: Path,
    ) {
        controlPlaneRunner(mount).run { context ->
            context.getBean(AuditPurgeFloorSource::class.java).shouldBeInstanceOf<UndeterminedRetentionFloorSource>()
            val ids = context.getBean(AuditPurgeIdSource::class.java)
            (ids.next() == ids.next()) shouldBe false
        }
    }

    @Test
    fun `INV-AC-3823 an authority that allows every call reaches the floor, which answers undeterminable`(
        @TempDir mount: Path,
    ) {
        controlPlaneRunner(mount)
            .withBean(AuditPurgeAuthority::class.java, { AuditPurgeAuthority { _, _, _ -> true } })
            .run { context ->
                context.startupFailure shouldBe null
                context.getBean(PurgeAuditEntries::class.java).execute(command) shouldBe
                    PurgeAuditOutcome.FloorUndeterminable
            }
    }

    @Test
    fun `INV-AC-3823 an authority that allows the key with a trailing space refuses the purge`(
        @TempDir mount: Path,
    ) {
        controlPlaneRunner(mount)
            .withBean(
                AuditPurgeAuthority::class.java,
                { AuditPurgeAuthority { _, permission, _ -> permission == "audit-entry.purge " } },
            ).run { context ->
                context.getBean(PurgeAuditEntries::class.java).execute(command) shouldBe PurgeAuditOutcome.NotPermitted
            }
    }

    @Test
    fun `INV-AC-3823 the authority asks for the platform scope only and the exact key`(
        @TempDir mount: Path,
    ) {
        val asked = mutableListOf<Pair<String, String>>()
        controlPlaneRunner(mount)
            .withBean(
                OperatorPermissions::class.java,
                { OperatorPermissions { actor, key -> asked.add(actor to key).let { true } } },
            ).run { context ->
                val authority = context.getBean(AuditPurgeAuthority::class.java)
                authority.allows("op", "audit-entry.purge", "tenant") shouldBe false
                authority.allows("op", "audit-entry.purge", "platform") shouldBe true
                asked shouldBe listOf("op" to "audit-entry.purge")
            }
    }
}
