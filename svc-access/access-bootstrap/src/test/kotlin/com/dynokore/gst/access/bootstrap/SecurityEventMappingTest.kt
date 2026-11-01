package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.BreakGlassController
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.contract.model.BreakGlassActionRequest
import com.dynokore.gst.access.contract.model.BreakGlassResponse
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.GrantRequest.GranteeKind
import com.dynokore.gst.access.contract.model.UnlockBreakGlassRequest
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.securityevents.SecurityEventProperties
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * INV-T-11068: the break-glass audit actions of access-svc are security events of class break_glass (INV-REQ-1384,
 * INV-Q-11060). The mapping is read from `application.yaml` and the records are received by a local collector.
 */
class SecurityEventMappingTest {
    private val mapper = JsonMapper.builder().build()

    private class Collector : AutoCloseable {
        class Received(
            val method: String,
            val path: String,
            val body: String,
        )

        val received = CopyOnWriteArrayList<Received>()
        private val pool = Executors.newCachedThreadPool()
        private val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = pool
                createContext("/") { exchange ->
                    val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
                    received += Received(exchange.requestMethod, exchange.requestURI.path, body)
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
                start()
            }

        val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

        /** Waits for [count] records, then a short grace period so that a surplus record would be seen too. */
        fun await(count: Int): List<Received> {
            val end = System.nanoTime() + 5_000_000_000L
            while (received.size < count && System.nanoTime() < end) Thread.sleep(10)
            Thread.sleep(300)
            return received.toList()
        }

        override fun close() {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenant = TenantId(UUID.randomUUID())
        val tenants = context.getBean(TenantContext::class.java)
        val tx = context.getBean(TransactionRunner::class.java)
        val breakGlassApi = context.getBean(BreakGlassController::class.java)
        val grantsApi = context.getBean(GrantsController::class.java)

        init {
            Seeds.grant(context, tenant, SeedSpec(ADMIN, Role.TENANT_ADMIN))
            Seeds.grant(context, tenant, SeedSpec(APPROVER, Role.TAX_APPROVER))
        }

        fun unlock(vararg approvers: String) =
            tenants.bind(tenant, ADMIN) {
                breakGlassApi.unlockBreakGlass(
                    UUID.randomUUID(),
                    UnlockBreakGlassRequest(tenant.toString(), approvers.toList()),
                )
            }

        fun action(unlockRef: String) =
            tenants.bind(tenant, ADMIN) {
                breakGlassApi.recordBreakGlassAction(
                    unlockRef,
                    UUID.randomUUID(),
                    BreakGlassActionRequest(BreakGlassActionRequest.Action.VIEW),
                )
            }

        fun grantRole() =
            tenants.bind(tenant, ADMIN) {
                grantsApi.grantRole(
                    UUID.randomUUID(),
                    GrantRequest(
                        GranteeKind.STAFF,
                        "IDN-G",
                        "tax.operator",
                        "tenant:$tenant",
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        LocalDate.now(ZoneOffset.UTC).plusDays(400),
                    ),
                )
            }

        /** The stored audit rows of [action] of this tenant, read as a superuser (row-level security hides none). */
        fun auditRows(action: String): List<JsonNode> =
            BoundaryFixture.asSuperuser { connection ->
                connection
                    .prepareStatement(
                        "SELECT data::text FROM access.outbox WHERE tenant_id = ?::uuid " +
                            "AND type = 'com.dynokore.gst.platform.AuditEntryRecorded.v1' AND data->>'action' = ?",
                    ).use { statement ->
                        statement.setString(1, tenant.toString())
                        statement.setString(2, action)
                        statement.executeQuery().use { rows ->
                            buildList { while (rows.next()) add(mapper.readTree(rows.getString(1))) }
                        }
                    }
            }
    }

    private fun run(block: (Env, Collector) -> Unit) {
        Collector().use { collector ->
            BoundaryFixture
                .runner()
                .withPropertyValues(
                    "gst.bindings.telemetry-export=bundled-otel",
                    "gst.bindings.telemetry-export-otlp-endpoint=${collector.endpoint}",
                ).run { context ->
                    context.startupFailure?.let { throw it }
                    block(Env(context), collector)
                }
        }
    }

    private fun attributes(record: Collector.Received): Map<String, String> {
        val logRecord = mapper.readTree(record.body).at("/resourceLogs/0/scopeLogs/0/logRecords/0")
        return logRecord.get("attributes").associate {
            it.get("key").asString() to
                it.at("/value/stringValue").asString()
        }
    }

    private fun assertBreakGlassRecord(
        record: Collector.Received,
        actor: String,
        env: Env,
    ) {
        assertThat(record.method).isEqualTo("POST")
        assertThat(record.path).isEqualTo("/v1/logs")
        val logRecord = mapper.readTree(record.body).at("/resourceLogs/0/scopeLogs/0/logRecords/0")
        assertThat(logRecord.at("/body/stringValue").asString()).isEqualTo("break_glass")
        assertThat(attributes(record))
            .isEqualTo(
                mapOf(
                    "gst.security.class" to "break_glass",
                    "user.id" to actor,
                    "gst.tenant.id" to env.tenant.toString(),
                ),
            )
    }

    @Test
    fun `INV-AC-2336 the effective mapping holds the shipped default and the two break-glass actions`() =
        run { env, _ ->
            assertThat(env.context.getBean(SecurityEventProperties::class.java).mapping)
                .isEqualTo(
                    mapOf(
                        "acknowledgeControlRelaxation" to "control_relaxation",
                        "unlockBreakGlass" to "break_glass",
                        "recordBreakGlassAction" to "break_glass",
                    ),
                )
        }

    @Test
    fun `INV-AC-2336 a break-glass unlock yields one break_glass record naming the actor and the tenant`() =
        run { env, collector ->
            val response = env.unlock(ADMIN, APPROVER)
            assertThat(response.body!!.status).isEqualTo(BreakGlassResponse.Status.UNLOCKED)
            val audits = env.auditRows("unlockBreakGlass")
            assertThat(audits).hasSize(1)
            val records = collector.await(1)
            assertThat(records).hasSize(1)
            assertBreakGlassRecord(records.single(), audits.single().get("actor").asString(), env)
            assertThat(audits.single().get("actor").asString()).isEqualTo(ADMIN)
        }

    @Test
    fun `INV-AC-2336 a break-glass action yields one break_glass record`() =
        run { env, collector ->
            val unlockRef = env.unlock(ADMIN, APPROVER).body!!.unlockRef
            assertThat(collector.await(1)).hasSize(1)
            env.action(unlockRef)
            val audits = env.auditRows("recordBreakGlassAction")
            assertThat(audits).hasSize(1)
            val records = collector.await(2)
            assertThat(records).hasSize(2)
            assertBreakGlassRecord(records.last(), audits.single().get("actor").asString(), env)
        }

    @Test
    fun `INV-AC-2336 an unlock refused for one person alone writes no audit entry and yields no record`() =
        run { env, collector ->
            assertThatThrownBy { env.unlock(ADMIN, ADMIN) }.isInstanceOf(ApiRefusal::class.java)
            assertThat(env.auditRows("unlockBreakGlass")).isEmpty()
            assertThat(collector.await(1)).isEmpty()
        }

    @Test
    fun `INV-AC-2336 an audit action that is not a key writes its entry and yields no record`() =
        run { env, collector ->
            env.grantRole()
            assertThat(env.auditRows("grantRole")).hasSize(1)
            assertThat(collector.await(1)).isEmpty()
        }

    @Test
    fun `INV-AC-2336 an unlock inside an outer transaction that throws yields no record and leaves no audit row`() =
        run { env, collector ->
            assertThatThrownBy {
                env.tenants.bind(env.tenant, ADMIN) {
                    env.tx.inTransaction {
                        env.unlock(ADMIN, APPROVER)
                        error("outer rollback")
                    }
                }
            }.hasMessageContaining("outer rollback")
            assertThat(env.auditRows("unlockBreakGlass")).isEmpty()
            assertThat(collector.await(1)).isEmpty()
        }

    private companion object {
        const val ADMIN = "IDN-ADM"
        const val APPROVER = "IDN-APP"
    }
}
