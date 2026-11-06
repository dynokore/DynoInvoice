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

/** A class whose name only starts like the real controller (INV-Q-17264 A look-alike). */
open class ValidationOverrideControllerX : ValidationApi

/**
 * INV-Q-17264 A, INV-Q-17266 A, INV-Q-17175 A: the override wiring, booted. The web context starts over the real
 * database with a real [AuthorityReplica] fed per scenario and a stub HTTP server for access-svc; the three operations
 * answer through the controller, and a caller is served only for a replica decision LOCAL or FALLBACK.
 */
class OverrideWiringTest : OverrideWiringBase() {
    /**
     * The probe of INV-Q-17264 A: a second `@RestController` implementing `ValidationApi`. An inner class is not an
     * independent class, so the component scan of the application never sees it; only the test that registers it does.
     */
    @RestController
    open inner class SecondValidationController : ValidationApi

    @Test
    fun `INV-AC-17187 a caller allowed from LOCAL reaches the use case and the three operations are not 501`() {
        AccessStub().use { stub ->
            withService(localReplica("u-local" to "validation.override"), stub) { service ->
                val doc = UUID.randomUUID()
                service.seed(doc)
                val before = overrides()
                val answer = service.override("u-local", doc)
                answer.status shouldBe 201
                answer.body shouldContain "PAY-011"
                overrides() shouldBe before + 1
                val base = "/internal/v1/payload/documents/$doc/versions/1/overrides"
                service.send("GET", base, tokenOfBff, "Bearer U-u-local").status shouldBe 200
                // approve: the row is released, so there is no pending row, a 404 and not a 501
                service.send("POST", "$base/PAY-011/approval", tokenOfBff, "Bearer U-u-local").status shouldBe 404
            }
        }
    }

    private fun refusedWithoutStoring(user: String) {
        val replica = localReplica("u-ok" to "validation.override", "u-space" to "validation.override ")
        replica.accept(
            AuthorityChanged(tenant, "u-revoked", 10, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)),
        )
        AccessStub().use { stub ->
            withService(replica, stub) { service ->
                val doc = UUID.randomUUID()
                service.seed(doc)
                val before = overrides()
                val audits = auditRows()
                val answer = service.override(user, doc)
                answer.status shouldBe 403
                answer.body shouldContain "SRT-004"
                answer.body shouldNotContain "reasonCode"
                overrides() shouldBe before
                auditRows() shouldBe audits
            }
        }
    }

    @Test
    fun `INV-AC-17187 a caller without the grant answers 403 and stores nothing`() = refusedWithoutStoring("u-denied")

    @Test
    fun `INV-AC-17187 a revoked principal answers 403 and stores nothing`() = refusedWithoutStoring("u-revoked")

    @Test
    fun `INV-AC-17187 a key with a trailing space answers 403 and stores nothing`() = refusedWithoutStoring("u-space")

    private fun fallbackReplica(): AuthorityReplica {
        val fallback =
            ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { request, _ ->
                if (request.principal.id == "u-fb") {
                    FallbackResult.Found(AuthorityVerdict(true))
                } else {
                    FallbackResult.Unavailable
                }
            }
        // no snapshot received: the replica is beyond its lag bound and asks the fallback
        return AuthorityReplica(Clock.systemUTC(), fallback = fallback)
    }

    @Test
    fun `INV-AC-17187 a caller allowed from FALLBACK reaches the use case`() {
        AccessStub().use { stub ->
            withService(fallbackReplica(), stub) { service ->
                val doc = UUID.randomUUID()
                service.seed(doc)
                val before = overrides()
                service.override("u-fb", doc).status shouldBe 201
                overrides() shouldBe before + 1
            }
        }
    }

    @Test
    fun `INV-AC-17187 a caller with a lag and no fallback answer is 403 and stores nothing`() {
        AccessStub().use { stub ->
            withService(fallbackReplica(), stub) { service ->
                val doc = UUID.randomUUID()
                service.seed(doc)
                val before = overrides()
                service.override("u-lag", doc).status shouldBe 403
                overrides() shouldBe before
            }
        }
    }

    @Test
    fun `INV-Q-17335 A a 500 from access-svc is 403 SRT-004 with reasonCode ACCESS_DECISION_UNAVAILABLE`() {
        AccessStub().use { stub ->
            stub.status = 500
            stub.body = ""
            withService(localReplica("u-local" to "validation.override"), stub) { service ->
                val doc = UUID.randomUUID()
                service.seed(doc)
                val before = overrides()
                val answer = service.override("u-local", doc)
                answer.status shouldBe 403
                answer.body shouldContain "SRT-004"
                answer.body shouldContain "\"reasonCode\":\"ACCESS_DECISION_UNAVAILABLE\""
                answer.body shouldNotContain "NOT_OVERRIDABLE"
                overrides() shouldBe before
            }
        }
    }

    @Test
    fun `exactly one ValidationApi bean exists and it is the ValidationOverrideController`() {
        AccessStub().use { stub ->
            withService(localReplica(), stub) { service ->
                val beans = checkNotNull(service.context).getBeansOfType(ValidationApi::class.java).values.toList()
                beans.size shouldBe 1
                (
                    beans
                        .single()
                        .javaClass.name
                        .substringBefore("$$")
                ) shouldBe
                    ValidationOverrideController::class.java.name
                (beans.single().javaClass.name == ValidationOverrideControllerX::class.java.name) shouldBe false
                // validateIssuance keeps the generated default
                val answer =
                    service.send(
                        "POST",
                        "/internal/v1/payload/documents/${UUID.randomUUID()}/versions/1/validate",
                        "W:subject-ingestion-svc",
                        "Bearer U-u1",
                        """{"submissionType":"TYPE_1A"}""",
                    )
                answer.status shouldBe 501
            }
        }
    }

    @Test
    fun `a second RestController implementing ValidationApi breaks the one bean assertion`() {
        AccessStub().use { stub ->
            val failure =
                runCatching {
                    withService(
                        localReplica(),
                        stub,
                        configure = {
                            it.withBean(
                                SecondValidationController::class.java,
                                { SecondValidationController() },
                            )
                        },
                    ) { service ->
                        checkNotNull(service.context).getBeansOfType(ValidationApi::class.java).size
                    }
                }
            // either the context refuses the duplicate mapping or two beans exist; one bean is the only pass
            (failure.isFailure || failure.getOrThrow() != 1) shouldBe true
        }
    }
}
