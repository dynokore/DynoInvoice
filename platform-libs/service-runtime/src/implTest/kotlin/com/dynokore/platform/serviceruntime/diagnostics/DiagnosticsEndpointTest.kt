package com.dynokore.platform.serviceruntime.diagnostics

import com.dynokore.platform.serviceruntime.health.OutboxDepthSource
import com.dynokore.platform.serviceruntime.health.QueueDepthSource
import com.dynokore.platform.serviceruntime.health.VaultHealthIndicator
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import java.nio.file.Path
import java.time.Clock
import java.util.Properties

/** FR-AE.82: "each service exposes the same diagnostic endpoint: version, health, recent error codes, queue and outbox lag". */
class DiagnosticsEndpointTest {
    private fun <T : Any> providerOf(value: T?): ObjectProvider<T> = object : ObjectProvider<T> {
        override fun getIfAvailable(): T? = value
    }

    @Test
    fun `reports version, health, recent error codes and both depths when bound`(
        @TempDir mountRoot: Path,
    ) {
        val recorder = RecentErrorCodesRecorder(Clock.systemUTC())
        recorder.record("RUL-001")
        val buildProperties = BuildProperties(Properties().apply { setProperty("version", "1.2.3") })

        val endpoint =
            DiagnosticsEndpoint(
                buildProperties = providerOf(buildProperties),
                vaultHealthIndicator = VaultHealthIndicator(mountRoot),
                recorder = recorder,
                queueDepthSource = providerOf(QueueDepthSource { 7L }),
                outboxDepthSource = providerOf(OutboxDepthSource { 3L }),
            )

        val report = endpoint.diagnostics()
        report.version shouldBe "1.2.3"
        report.health shouldBe "UP"
        report.recentErrorCodes.single().code shouldBe "RUL-001"
        report.queueDepth shouldBe 7L
        report.outboxDepth shouldBe 3L
    }

    @Test
    fun `reports null depths when outbox-and-queue has not bound a source yet`(
        @TempDir mountRoot: Path,
    ) {
        val endpoint =
            DiagnosticsEndpoint(
                buildProperties = providerOf(null),
                vaultHealthIndicator = VaultHealthIndicator(mountRoot),
                recorder = RecentErrorCodesRecorder(Clock.systemUTC()),
                queueDepthSource = providerOf(null),
                outboxDepthSource = providerOf(null),
            )

        val report = endpoint.diagnostics()
        report.version shouldBe "unknown"
        report.queueDepth.shouldBeNull()
        report.outboxDepth.shouldBeNull()
    }
}
