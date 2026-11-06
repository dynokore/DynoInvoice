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

/** Shared helpers of the override wiring tests: the served service over the real database. */
open class OverrideWiringBase {
    protected val tenant = EndUserDoubles.tenantA
    protected val bff = "console-bff"

    class AccessStub : AutoCloseable {
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
    protected fun localReplica(vararg grants: Pair<String, String>): AuthorityReplica {
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

    protected val resolver =
        EndUserPrincipalResolver { header ->
            header
                ?.takeIf { it.startsWith("Bearer U-") }
                ?.let { EndUserPrincipal(it.removePrefix("Bearer U-"), tenant, setOf(EntityRef("E-001"))) }
        }

    protected fun <T> withService(
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
    protected fun ServedService.seed(
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

    protected fun overrides(): Long =
        PayloadDb.superuser().use { c ->
            c.createStatement().executeQuery("select count(*) from payload.validation_override").use {
                it.next()
                it.getLong(1)
            }
        }

    protected fun ServedService.override(
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

    protected val tokenOfBff: String
        get() = "W:subject-$bff"

    protected val auditType = "com.dynokore.gst.platform.AuditEntryRecorded.v1"

    /** Audit outbox rows of the test tenant, as `type` / `action` / `actor` triples (read as the database owner). */
    protected fun auditRowsOf(type: String = auditType): List<Triple<String, String?, String?>> =
        PayloadDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select type, data->>'action', data->>'actor' from payload.outbox where tenant_id = ? and type = ?",
                ).use { p ->
                    p.setObject(1, tenant.value)
                    p.setString(2, type)
                    p.executeQuery().use { r ->
                        buildList { while (r.next()) add(Triple(r.getString(1), r.getString(2), r.getString(3))) }
                    }
                }
        }

    protected fun auditRows(): Int = auditRowsOf().size
}
