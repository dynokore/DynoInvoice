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
import com.dynokore.platform.outboxandqueue.EntityRef
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
class OverrideWiringTest {
    /**
     * The probe of INV-Q-17264 A: a second `@RestController` implementing `ValidationApi`. An inner class is not an
     * independent class, so the component scan of the application never sees it; only the test that registers it does.
     */
    @RestController
    open inner class SecondValidationController : ValidationApi

    private val tenant = EndUserDoubles.tenantA
    private val bff = "console-bff"

    private class AccessStub : AutoCloseable {
        @Volatile var status = 200

        @Volatile var body = """{"decision":"ALLOW","soleOperator":false,"outcome":"RELEASED"}"""
        private val server =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange ->
                    exchange.requestBody.readAllBytes()
                    val bytes = body.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                    if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
                    exchange.close()
                }
                start()
            }
        val url = "http://127.0.0.1:${server.address.port}"

        override fun close() = server.stop(0)
    }

    /** A replica whose snapshot is complete: a grant of [action] for [user] on E-001, LOCAL. */
    private fun localReplica(vararg grants: Pair<String, String>): AuthorityReplica {
        val replica = AuthorityReplica(Clock.systemUTC())
        replica.onSnapshotComplete(0)
        grants.forEachIndexed { index, (user, action) ->
            replica.accept(
                AuthorityChanged(
                    tenant,
                    user,
                    index + 1L,
                    AuthorityRecord.GrantRecord(
                        "E-001",
                        listOf(Grant(action, ScopeLevel.ENTITY, "E-001", Instant.EPOCH)),
                    ),
                ),
            )
        }
        return replica
    }

    private val resolver =
        EndUserPrincipalResolver { header ->
            header
                ?.takeIf { it.startsWith("Bearer U-") }
                ?.let { EndUserPrincipal(it.removePrefix("Bearer U-"), tenant, setOf(EntityRef("E-001"))) }
        }

    private fun <T> withService(
        replica: AuthorityReplica,
        stub: AccessStub,
        configure: (WebApplicationContextRunner) -> WebApplicationContextRunner = { it },
        block: (ServedService) -> T,
    ): T {
        var result: T? = null
        ServedService.run(
            WorkloadDoubles(callersOf(ContractDocument.operations(ContractDocument.text()))),
            resolver = resolver,
            configure = {
                configure(
                    it
                        .withBean(AuthorityReplica::class.java, { replica })
                        .withPropertyValues("payload.access-svc-url=${stub.url}"),
                )
            },
        ) { service -> result = block(service) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /**
     * Seeds the facts of one version as the database owner and records its findings through the wired
     * `JooqFindingsStore.replace` under the bound tenant, in a transaction on the guarded `DSLContext` (INV-Q-17522).
     */
    private fun ServedService.seed(
        doc: UUID,
        version: Int = 1,
        codes: List<String> = listOf("PAY-011"),
    ) {
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "insert into payload.document_version_fact (tenant_id, doc_uuid, version, entity_ref, total_sgd) " +
                        "values (?, ?, ?, 'E-001', 100.00)",
                ).use {
                    it.setObject(1, tenant.value)
                    it.setObject(2, doc)
                    it.setInt(3, version)
                    it.executeUpdate()
                }
        }
        val ctx = checkNotNull(context)
        val store = ctx.getBean(JooqFindingsStore::class.java)
        ctx.getBean(TenantScope::class.java).bind(tenant, "seed-actor") {
            ctx.getBean(TransactionRunner::class.java).inTransaction {
                store.replace(doc, version, codes.map { StoredFinding(it, "total", "m", true) })
            }
        }
    }

    private fun overrides(): Long =
        PayloadDb.superuser().use { c ->
            c.createStatement().executeQuery("select count(*) from payload.validation_override").use {
                it.next()
                it.getLong(1)
            }
        }

    private fun ServedService.override(
        user: String,
        doc: UUID,
        code: String = "PAY-011",
    ): Answer =
        send(
            "POST",
            "/internal/v1/payload/documents/$doc/versions/1/overrides",
            tokenOfBff,
            "Bearer U-$user",
            """{"code":"$code","reason":"register lag"}""",
        )

    private val tokenOfBff: String
        get() = "W:subject-$bff"

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

    @Test
    fun `INV-AC-17187 a denied, a revoked and a trailing space key answer 403 and store nothing`() {
        val replica = localReplica("u-ok" to "validation.override", "u-space" to "validation.override ")
        replica.accept(
            AuthorityChanged(tenant, "u-revoked", 10, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)),
        )
        AccessStub().use { stub ->
            withService(replica, stub) { service ->
                val doc = UUID.randomUUID()
                service.seed(doc)
                val before = overrides()
                listOf("u-denied", "u-revoked", "u-space").forEach { user ->
                    val answer = service.override(user, doc)
                    answer.status shouldBe 403
                    answer.body shouldContain "SRT-004"
                    answer.body shouldNotContain "reasonCode"
                }
                overrides() shouldBe before
            }
        }
    }

    @Test
    fun `INV-AC-17187 a caller allowed from FALLBACK reaches the use case and one with a lag and no answer is 403`() {
        val fallback =
            ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { request, _ ->
                if (request.principal.id == "u-fb") {
                    FallbackResult.Found(AuthorityVerdict(true))
                } else {
                    FallbackResult.Unavailable
                }
            }
        // no snapshot received: the replica is beyond its lag bound and asks the fallback
        val replica = AuthorityReplica(Clock.systemUTC(), fallback = fallback)
        AccessStub().use { stub ->
            withService(replica, stub) { service ->
                val doc = UUID.randomUUID()
                service.seed(doc)
                val before = overrides()
                service.override("u-lag", doc).status shouldBe 403
                overrides() shouldBe before
                service.override("u-fb", doc).status shouldBe 201
                overrides() shouldBe before + 1
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
                val before = overrides()
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
