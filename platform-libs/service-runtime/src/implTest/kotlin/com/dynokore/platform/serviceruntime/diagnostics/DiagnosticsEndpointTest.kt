package com.dynokore.platform.serviceruntime.diagnostics

import com.dynokore.platform.serviceruntime.health.DomainDependencyHealthIndicator
import com.dynokore.platform.serviceruntime.health.DomainDependencyProbe
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
                domainDependencyHealthIndicators = emptyList(),
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
                domainDependencyHealthIndicators = emptyList(),
                recorder = RecentErrorCodesRecorder(Clock.systemUTC()),
                queueDepthSource = providerOf(null),
                outboxDepthSource = providerOf(null),
            )

        val report = endpoint.diagnostics()
        report.version shouldBe "unknown"
        report.queueDepth.shouldBeNull()
        report.outboxDepth.shouldBeNull()
    }

    @Test
    fun `reports DEGRADED when a domain dependency is unreachable, even though the vault is UP`(
        @TempDir mountRoot: Path,
    ) {
        val endpoint =
            DiagnosticsEndpoint(
                buildProperties = providerOf(null),
                vaultHealthIndicator = VaultHealthIndicator(mountRoot),
                domainDependencyHealthIndicators = listOf(
                    DomainDependencyHealthIndicator("canonicalSvc", DomainDependencyProbe { false }),
                ),
                recorder = RecentErrorCodesRecorder(Clock.systemUTC()),
                queueDepthSource = providerOf(null),
                outboxDepthSource = providerOf(null),
            )

        // FR-AH.17 behaviour #2: ingestion-svc reports 503 (degraded) while canonical-svc is down —
        // the uniform diagnostic endpoint must surface exactly that, not a bare "UP" from the vault alone.
        endpoint.diagnostics().health shouldBe "DEGRADED"
    }

    @Test
    fun `reports DOWN, not DEGRADED, when the vault itself is unavailable alongside a degraded dependency`() {
        val endpoint =
            DiagnosticsEndpoint(
                buildProperties = providerOf(null),
                vaultHealthIndicator = VaultHealthIndicator(Path.of("/does/not/exist")),
                domainDependencyHealthIndicators = listOf(
                    DomainDependencyHealthIndicator("canonicalSvc", DomainDependencyProbe { false }),
                ),
                recorder = RecentErrorCodesRecorder(Clock.systemUTC()),
                queueDepthSource = providerOf(null),
                outboxDepthSource = providerOf(null),
            )

        // §36.7: the vault is one of the three things every service needs unconditionally; its
        // absence must outrank a merely-degraded domain dependency, never be masked by it.
        endpoint.diagnostics().health shouldBe "DOWN"
    }
}
