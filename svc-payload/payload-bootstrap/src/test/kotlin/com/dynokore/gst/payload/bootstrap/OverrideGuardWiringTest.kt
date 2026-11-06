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

/** INV-Q-17522: the tenant-bound stores under the wired guard. */
class OverrideGuardWiringTest : OverrideWiringBase() {
    private fun Throwable.chain(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    /** Runs [body] as the wired, guarded `DSLContext` under the bound tenant, in a transaction. */
    private fun <T> ServedService.guarded(body: (org.jooq.DSLContext) -> T): T {
        val ctx = checkNotNull(context)
        return ctx.getBean(TenantScope::class.java).bind(tenant, "probe-actor") {
            ctx
                .getBean(
                    TransactionRunner::class.java,
                ).inTransaction { body(ctx.getBean(org.jooq.DSLContext::class.java)) }
        }
    }

    private fun newOverride(doc: UUID) =
        NewOverride(doc, 1, "PAY-011", "register lag", OverrideStatus.PENDING, "A1", null)

    private fun storeThroughWiredBeans(source: String) {
        val replica =
            if (source == "LOCAL") {
                localReplica("u-1" to "validation.override")
            } else {
                AuthorityReplica(
                    Clock.systemUTC(),
                    fallback =
                        ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ ->
                            FallbackResult.Found(AuthorityVerdict(true))
                        },
                )
            }
        AccessStub().use { stub ->
            withService(replica, stub) { service ->
                val doc = UUID.randomUUID()
                service.seed(doc)
                val stored =
                    service.guarded { dsl ->
                        JooqOverrideStore(dsl) { tenant.value }.insert(newOverride(doc))
                    }
                (stored != null) shouldBe true
            }
        }
    }

    @Test
    fun `INV-Q-17522 under the LOCAL source a recording then an override insert is not refused by the guard`() =
        storeThroughWiredBeans("LOCAL")

    @Test
    fun `INV-Q-17522 under the FALLBACK source a recording then an override insert is not refused by the guard`() =
        storeThroughWiredBeans("FALLBACK")

    @Test
    fun `INV-Q-17522 a store built over another tenant is refused by the guard`() {
        AccessStub().use { stub ->
            withService(localReplica(), stub) { service ->
                val other = UUID.randomUUID()
                val failure =
                    runCatching {
                        service.guarded { dsl ->
                            JooqOverrideStore(dsl) { other }.insert(newOverride(UUID.randomUUID()))
                        }
                    }.exceptionOrNull()
                checkNotNull(failure).chain().any { it is TenantPredicateViolation } shouldBe true
            }
        }
    }

    @Test
    fun `INV-Q-17522 a store that writes the SQL function field as the tenant is refused by the guard`() {
        AccessStub().use { stub ->
            withService(localReplica(), stub) { service ->
                val table = ValidationOverride.VALIDATION_OVERRIDE
                val boundField = DSL.function(DSL.name("payload", "bound_tenant"), SQLDataType.UUID)
                val failure =
                    runCatching {
                        service.guarded { dsl ->
                            dsl
                                .insertInto(table)
                                .set(table.TENANT_ID, boundField)
                                .set(table.DOC_UUID, UUID.randomUUID())
                                .set(table.VERSION, 1)
                                .set(table.CODE, "PAY-011")
                                .set(table.REASON, "r")
                                .set(table.STATUS, OverrideStatus.PENDING.name)
                                .set(table.REQUESTED_BY, "A1")
                                .execute()
                        }
                    }.exceptionOrNull()
                checkNotNull(failure).chain().any { it is TenantPredicateViolation } shouldBe true
            }
        }
    }
}
