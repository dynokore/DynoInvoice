package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.PayloadDb
import com.dynokore.gst.payload.adapters.http.ValidationOverrideController
import com.dynokore.gst.payload.adapters.persistence.JooqFindingsStore
import com.dynokore.gst.payload.adapters.persistence.JooqOverrideStore
import com.dynokore.gst.payload.adapters.persistence.jooq.tables.ValidationOverride
import com.dynokore.gst.payload.application.override.NewOverride
import com.dynokore.gst.payload.contract.api.ValidationApi
import com.dynokore.gst.payload.domain.finding.StoredFinding
import com.dynokore.gst.payload.domain.override.OverrideStatus
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.Grant
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
import com.dynokore.platform.tenancyjooq.TenantPredicateViolation
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.web.bind.annotation.RestController
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** INV-Q-17570: the platform audit event through the wired outbox. */
class OverrideAuditWiringTest : OverrideWiringBase() {
    private fun releasedUnder(replica: AuthorityReplica) {
        AccessStub().use { stub ->
            withService(replica, stub) { service ->
                val doc = UUID.randomUUID()
                service.seed(doc)
                val before = auditRowsOf().count { it.second == "released" }
                service.override("u-1", doc).status shouldBe 201
                val released = auditRowsOf().filter { it.second == "released" }
                released.size shouldBe before + 1
                released.last().third shouldBe "u-1"
            }
        }
    }

    @Test
    fun `INV-Q-17570 a released override under the LOCAL source writes one released audit row of the user ID`() =
        releasedUnder(localReplica("u-1" to "validation.override"))

    @Test
    fun `INV-Q-17570 a released override under the FALLBACK source writes one released audit row of the user ID`() =
        releasedUnder(
            AuthorityReplica(
                Clock.systemUTC(),
                fallback =
                    ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ ->
                        FallbackResult.Found(AuthorityVerdict(true))
                    },
            ),
        )

    @Test
    fun `INV-Q-17570 a refused request writes no audit row`() {
        AccessStub().use { stub ->
            withService(localReplica(), stub) { service ->
                val doc = UUID.randomUUID()
                service.seed(doc)
                val before = auditRows()
                service.override("u-denied", doc).status shouldBe 403
                auditRows() shouldBe before
            }
        }
    }

    private fun lookAlikeRefused(type: String) {
        AccessStub().use { stub ->
            withService(localReplica(), stub) { service ->
                val ctx = checkNotNull(service.context)
                val before = auditRowsOf(type).size
                shouldThrow<EmissionNotPermitted> {
                    ctx.getBean(TenantScope::class.java).bind(tenant, "probe-actor") {
                        ctx.getBean(TransactionRunner::class.java).inTransaction {
                            ctx.getBean(OutboxWriter::class.java).append(
                                listOf(OutboundEvent(type, "{}")),
                                EventContext(
                                    tenant,
                                    EntityRef("E-001"),
                                    null,
                                    null,
                                    UUID.randomUUID().toString(),
                                    null,
                                    CorrelationId("corr-1"),
                                    CausationId("cause-1"),
                                ),
                            )
                        }
                    }
                }
                auditRowsOf(type).size shouldBe before
            }
        }
    }

    @Test
    fun `INV-Q-17570 the audit type of another version is refused with EmissionNotPermitted`() =
        lookAlikeRefused("com.dynokore.gst.platform.AuditEntryRecorded.v2")

    @Test
    fun `INV-Q-17570 the audit type of another package is refused with EmissionNotPermitted`() =
        lookAlikeRefused("com.dynokore.gst.payload.AuditEntryRecorded.v1")

    @Test
    fun `INV-Q-17570 the audit type in lower case is refused with EmissionNotPermitted`() =
        lookAlikeRefused("com.dynokore.gst.platform.auditentryrecorded.v1")

    @Test
    fun `INV-Q-17570 the wired catalogue holds exactly the three payload types and the audit type`() {
        AccessStub().use { stub ->
            withService(localReplica(), stub) { service ->
                checkNotNull(service.context)
                    .getBean(EventCatalogue::class.java)
                    .entries()
                    .map { it.eventType }
                    .toSet() shouldBe
                    setOf(
                        "com.dynokore.gst.payload.PayloadReady.v1",
                        "com.dynokore.gst.payload.PayloadRejected.v1",
                        "com.dynokore.gst.payload.RenditionFindingChanged.v1",
                        "com.dynokore.gst.platform.AuditEntryRecorded.v1",
                    )
            }
        }
    }
}
