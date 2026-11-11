package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.elevation.ElevationController
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.keys.VerificationKeyStore
import com.dynokore.gst.access.application.operatorsession.OpenOperatorSessionUseCase
import com.dynokore.gst.access.application.operatorsession.SessionPreparation
import com.dynokore.gst.access.contract.model.DecideElevationRequest
import com.dynokore.gst.access.contract.model.ElevationRequest
import com.dynokore.gst.access.contract.model.OperatorSessionRequest
import com.dynokore.gst.access.contract.model.OperatorSessionResponse
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * INV-T-4154 (INV-REQ-862 b1-b4, INV-AC-922; INV-Q-16469 A, INV-Q-16471 A, INV-Q-16473 A, INV-Q-16475 A):
 * `openOperatorSession` at the service boundary against the real catalogue matrix, the real database and a local
 * collector for the security event (INV-Q-11058).
 */
class OperatorSessionTest {
    private val mapper = JsonMapper.builder().build()
    private val platform = "ffffffff-ffff-ffff-ffff-ffffffffffff"
    private val run = UUID.randomUUID().toString().take(8)

    private fun user(name: String) = "user:$name-$run"

    private class Collector : AutoCloseable {
        class Received(
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
                    received += Received(exchange.requestURI.path, body)
                    exchange.sendResponseHeaders(200, -1)
                    exchange.close()
                }
                start()
            }

        val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

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
        val operatorKey: ECKey,
        val mount: Path,
    ) {
        val a = TenantId(UUID.randomUUID())
        val tenants = context.getBean(TenantContext::class.java)
        val binding = context.getBean(PlatformTenantBinding::class.java)
        val api = context.getBean(ElevationController::class.java)
        val clock = context.getBean(Clock::class.java) as MovableClock
        val support = user("support")
        val engineer = user("engineer")
        val operator = user("operator")
        val officer = user("officer")
        val stranger = user("stranger")

        init {
            platformRow(support, "platform.support")
            platformRow(engineer, "platform.engineer")
            platformRow(operator, "deployment.operator")
            Seeds.grant(context, a, SeedSpec(officer, Role.SECURITY_OFFICER))
            structure(a, StructureNodeKind.TENANT, retired = false)
        }

        fun structure(
            tenant: TenantId,
            kind: StructureNodeKind,
            retired: Boolean,
            ref: String = "NODE-${UUID.randomUUID().toString().take(6)}",
        ) = context.getBean(StructureReplica::class.java).accept(
            OrganisationStructureChanged(
                tenant,
                ref,
                StructureChange.CREATED,
                StructureNode(kind, null, retired, "node", null, emptyList(), 1),
            ),
        )

        private fun platformRow(
            identity: String,
            role: String,
        ) = BoundaryFixture.asSuperuser { c ->
            c.createStatement().use {
                it.execute(
                    "insert into access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, created_by, " +
                        "updated_at, updated_by, version) select gen_random_uuid(), '$platform', '$identity', " +
                        "'STAFF', now(), 'mig', now(), 'mig', 0 where not exists (select 1 from access.identity " +
                        "where tenant_id = '$platform' and identity_ref = '$identity')",
                )
                it.execute(
                    "insert into access.role_grant (id, tenant_id, grant_ref, identity_ref, role, scope_level, " +
                        "scope_ref, valid_from, valid_until, status, grant_source, created_at, created_by, " +
                        "updated_at, updated_by, version) values (gen_random_uuid(), '$platform', " +
                        "'GRT-${UUID.randomUUID().toString().take(8)}', '$identity', '$role', 'TENANT', '$platform', " +
                        "now() - interval '1 day', now() + interval '30 days', 'ACTIVE', 'BY_HAND', now(), 'mig', " +
                        "now(), 'mig', 0)",
                )
            }
        }

        fun open(
            actor: String,
            tenantRef: String = a.toString(),
            case: String = "CASE-1",
        ): OperatorSessionResponse =
            binding.bindPlatform(actor) {
                val request = OperatorSessionRequest(tenantRef, case, "investigate")
                api.openOperatorSession(UUID.randomUUID(), request).body!!
            }

        fun refusal(
            actor: String,
            tenantRef: String = a.toString(),
            case: String = "CASE-1",
        ): ApiRefusal =
            catchThrowableOfType(ApiRefusal::class.java) { open(actor, tenantRef, case) }
                ?: error("the call was not refused")

        fun auditRows(
            action: String,
            tenant: TenantId = a,
        ): List<tools.jackson.databind.JsonNode> =
            BoundaryFixture.asSuperuser { c ->
                c
                    .prepareStatement(
                        "SELECT data::text FROM access.outbox WHERE tenant_id = ?::uuid " +
                            "AND type = 'com.dynokore.gst.platform.AuditEntryRecorded.v1' AND data->>'action' = ?",
                    ).use { st ->
                        st.setString(1, tenant.toString())
                        st.setString(2, action)
                        st.executeQuery().use { rs -> rows(rs) }
                    }
            }

        fun keyEvents(): List<tools.jackson.databind.JsonNode> =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "select data::text from access.outbox where type like '%OperatorSessionKeysChanged%' " +
                                "order by seq",
                        ).use { rs -> buildList { while (rs.next()) add(mapper.readTree(rs.getString(1))) } }
                }
            }

        fun storedKeys(): List<Pair<String, String>> =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use { st ->
                    st.executeQuery("select kid, use from access.verification_key order by kid").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                    }
                }
            }

        /** An approved elevation of [actor] for the tenant [a], lasting [minutes]. */
        fun elevate(
            actor: String,
            minutes: Int = 30,
        ) {
            val ref =
                tenants.bind(a, actor) {
                    api
                        .requestElevation(
                            UUID.randomUUID(),
                            ElevationRequest("CASE-1", a.toString(), "x"),
                        ).body!!
                        .requestRef
                }
            tenants.bind(a, officer) {
                api.decideElevation(
                    ref,
                    UUID.randomUUID(),
                    DecideElevationRequest(DecideElevationRequest.Decision.GRANT, minutes),
                )
            }
        }
    }

    private fun rows(rs: java.sql.ResultSet) = buildList { while (rs.next()) add(mapper.readTree(rs.getString(1))) }

    private fun kidOf(key: ECKey): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(key.toECPublicKey().encoded)
            .take(8)
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    private fun run(
        form: String = "SAAS",
        operatorKey: ECKey? = TestSecrets.newSigningKey(),
        next: ECKey? = null,
        block: (Env, Collector) -> Unit,
    ) {
        Collector().use { collector ->
            val mount = BoundaryFixture.mountOperator(operatorKey, next)
            BoundaryFixture.deleteOperatorVerificationKeys()
            BoundaryFixture
                .runner(catalogueMatrix = true, form = form, mounted = mount, requirements = null)
                .withPropertyValues(
                    "gst.bindings.telemetry-export=bundled-otel",
                    "gst.bindings.telemetry-export-otlp-endpoint=${collector.endpoint}",
                ).run { context ->
                    context.startupFailure?.let { throw it }
                    block(Env(context, operatorKey ?: TestSecrets.newSigningKey(), mount), collector)
                }
        }
    }

    @Test
    fun `a granted operator gets a token of at most 15 minutes signed with the operator key published first`() =
        run { env, _ ->
            val opened = env.open(env.support)
            val jwt = SignedJWT.parse(opened.token)
            assertThat(jwt.header.algorithm.name).isEqualTo("ES256")
            assertThat(jwt.header.keyID).isEqualTo(kidOf(env.operatorKey))
            assertThat(jwt.verify(ECDSAVerifier(env.operatorKey.toECPublicKey()))).isTrue()
            val claims = jwt.jwtClaimsSet
            assertThat(claims.subject).isEqualTo(env.support)
            assertThat(claims.getStringClaim("tenant_id")).isEqualTo(env.a.toString())
            val act = mapOf("role" to "platform.support", "case" to "CASE-1")
            assertThat(claims.getJSONObjectClaim("act")).isEqualTo(act)
            assertThat(Duration.between(claims.issueTime.toInstant(), claims.expirationTime.toInstant()).seconds)
                .isLessThanOrEqualTo(900)
            assertThat(opened.contentMode).isEqualTo(OperatorSessionResponse.ContentMode.MASKED)
            // the key row and the event were committed before the token existed
            assertThat(env.storedKeys()).contains(kidOf(env.operatorKey) to "OPERATOR_SESSION")
            val items = env.keyEvents().last()["keys"].toList()
            val pairs: List<Pair<String, String>> = items.map { it["kid"].asString() to it["use"].asString() }
            assertThat(pairs).contains(kidOf(env.operatorKey) to "OPERATOR_SESSION")
            val own = items.first { it["kid"].asString() == kidOf(env.operatorKey) }
            assertThat(own["alg"].asString()).isEqualTo("ES256")
        }

    @Test
    fun `getVerificationKeys lists no operator-session key`() =
        run { env, _ ->
            env.open(env.support)
            val store = env.context.getBean(VerificationKeyStore::class.java)
            assertThat(store.all().map { it.kid }).doesNotContain(kidOf(env.operatorKey))
        }

    @Test
    fun `a platform engineer is allowed, a caller without the grant and a deployment operator in SaaS are refused`() =
        run { env, collector ->
            assertThat(env.open(env.engineer).contentMode).isEqualTo(OperatorSessionResponse.ContentMode.MASKED)
            assertThat(collector.await(1)).hasSize(1)
            listOf(env.stranger, env.operator).forEach {
                assertThat(env.refusal(it).code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            }
            assertThat(env.auditRows("openOperatorSession")).hasSize(1)
            assertThat(collector.await(2)).hasSize(1)
        }

    @Test
    fun `a deployment operator is allowed in the private form and a platform support grant is not a cell there`() =
        run(form = "PRIVATE") { env, _ ->
            assertThat(env.open(env.operator).token).isNotBlank()
            assertThat(env.refusal(env.support).code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        }

    @Test
    fun `a missing, blank, long or colon case is refused with no audit entry, token or event`() =
        run { env, collector ->
            listOf("", "   ", "A".repeat(65), "CASE:1", "CASE\u0007").forEach {
                // the contract's pattern and size rules refuse it before the controller (400 SRT-001)
                val refused = catchThrowable { env.open(env.support, case = it) }
                assertThat(refused?.javaClass?.simpleName).isEqualTo("ConstraintViolationException")
            }
            // the use case holds the same rule for a caller that bypasses the contract validation
            val prepared =
                env.context.getBean(OpenOperatorSessionUseCase::class.java).prepare(
                    IdentityRef(env.support),
                    env.a.toString(),
                    "CASE:1",
                )
            assertThat(prepared).isEqualTo(SessionPreparation.BadCase)
            assertThat(env.auditRows("openOperatorSession")).isEmpty()
            assertThat(collector.await(1)).isEmpty()
        }

    @Test
    fun `an unparsable, platform, unheld, entity-only and retired tenant each give the identical 404`() =
        run { env, collector ->
            val entityOnly = TenantId(UUID.randomUUID())
            env.structure(entityOnly, StructureNodeKind.ENTITY, retired = false)
            val retired = TenantId(UUID.randomUUID())
            env.structure(retired, StructureNodeKind.TENANT, retired = true)
            val unheld = UUID.randomUUID().toString()
            val refs = listOf("not-a-uuid", platform, unheld, entityOnly.toString(), retired.toString(), "")
            val refusals = refs.map { env.refusal(env.support, tenantRef = it) }
            assertThat(refusals.map { it.code }.toSet()).containsExactly(ClosedErrorCode.NOT_FOUND)
            assertThat(refusals.map { it.detail }.toSet()).hasSize(1)
            assertThat(env.open(env.support).token).isNotBlank()
            assertThat(env.auditRows("openOperatorSession", entityOnly)).isEmpty()
            assertThat(collector.await(1)).hasSize(1)
        }

    @Test
    fun `the audit entry names the operator, the tenant scope and the case, and one elevation record is exported`() =
        run { env, collector ->
            env.open(env.support, case = "CASE-77")
            val audit = env.auditRows("openOperatorSession").single()
            assertThat(audit["actor"].asString()).isEqualTo(env.support)
            assertThat(audit["scope"].asString()).isEqualTo("tenant:${env.a}")
            assertThat(audit["reason"].asString()).isEqualTo("CASE-77")
            val records = collector.await(1)
            assertThat(records).hasSize(1)
            assertThat(records.single().path).isEqualTo("/v1/logs")
            val logRecord = mapper.readTree(records.single().body).at("/resourceLogs/0/scopeLogs/0/logRecords/0")
            val attributes =
                logRecord["attributes"].associate { it["key"].asString() to it.at("/value/stringValue").asString() }
            assertThat(attributes)
                .isEqualTo(
                    mapOf(
                        "gst.security.class" to "elevation",
                        "user.id" to env.support,
                        "gst.tenant.id" to env.a.toString(),
                    ),
                )
        }

    @Test
    fun `look-alike audit actions emit nothing`() =
        run { env, collector ->
            val audit = env.context.getBean(GrantAudit::class.java)
            val tx = env.context.getBean(TransactionRunner::class.java)
            listOf("OpenOperatorSession", "openOperatorSessions", "openoperatorsession").forEach {
                env.tenants.bind(env.a, env.support) {
                    val entry = GrantAuditEntry(env.support, it, "tenant:${env.a}", "CASE-1", null)
                    tx.inTransaction { audit.record(entry) }
                }
            }
            assertThat(collector.await(1)).isEmpty()
        }

    @Test
    fun `INV-AC-922 content mode is MASKED without an elevation and ELEVATED during one, only for its holder`() =
        run { env, _ ->
            assertThat(env.open(env.support).contentMode).isEqualTo(OperatorSessionResponse.ContentMode.MASKED)
            env.elevate(env.support)
            assertThat(env.open(env.support).contentMode).isEqualTo(OperatorSessionResponse.ContentMode.ELEVATED)
            assertThat(env.open(env.engineer).contentMode).isEqualTo(OperatorSessionResponse.ContentMode.MASKED)
            env.clock.advance(Duration.ofMinutes(31))
            assertThat(env.open(env.support).contentMode).isEqualTo(OperatorSessionResponse.ContentMode.MASKED)
        }

    private class OutboxRow(
        val tenant: String,
        val entity: String,
        val subject: String,
        val topic: String,
        val type: String,
        val data: tools.jackson.databind.JsonNode,
    )

    private fun outboxRows(
        where: String,
        vararg args: String,
    ): List<OutboxRow> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select tenant_id::text, entity_ref, subject, topic, type, data::text from access.outbox " +
                        "where $where order by seq",
                ).use { st ->
                    args.forEachIndexed { i, v -> st.setString(i + 1, v) }
                    st.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    OutboxRow(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        mapper.readTree(rs.getString(6)),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    private fun accessRecords(
        env: Env,
        operator: String,
    ) = outboxRows(
        "tenant_id = ?::uuid and type = 'com.dynokore.gst.platform.AuthorityChanged.v1' and subject = ? " +
            "and data->>'changeKind' = 'OPERATOR_ACCESS'",
        env.a.toString(),
        operator,
    )

    @Test
    fun `INV-AC-922 the operator access record is appended with the claims, masked without elevation`() =
        run { env, _ ->
            val opened = env.open(env.support)
            val claims = SignedJWT.parse(opened.token).jwtClaimsSet
            val row = accessRecords(env, env.support).single()
            assertThat(row.data["principalRef"].asString()).isEqualTo(env.support)
            assertThat(row.data["authorityVersion"].asLong()).isPositive()
            val record = row.data["operatorAccessRecord"]
            assertThat(record["caseRef"].asString()).isEqualTo(claims.getJSONObjectClaim("act")["case"])
            assertThat(record["role"].asString()).isEqualTo(claims.getJSONObjectClaim("act")["role"])
            assertThat(record["state"].asString()).isEqualTo("ACTIVE")
            assertThat(record["permissions"].toList().map { it.asString() })
                .containsExactlyInAnyOrder("submission.view-status", "period-readiness.view")
            assertThat(java.time.Instant.parse(record["validUntil"].asString()))
                .isEqualTo(claims.expirationTime.toInstant())
            assertThat(record.has("elevation")).isFalse()
        }

    @Test
    fun `INV-AC-922 the operator access record carries the approved elevation exactly when the session is elevated`() =
        run { env, _ ->
            env.elevate(env.support, minutes = 30)
            val opened = env.open(env.support)
            assertThat(opened.contentMode).isEqualTo(OperatorSessionResponse.ContentMode.ELEVATED)
            val claims = SignedJWT.parse(opened.token).jwtClaimsSet
            val record = accessRecords(env, env.support).single().data["operatorAccessRecord"]
            assertThat(record["permissions"].toList().map { it.asString() }).doesNotContain("doc.view.content")
            val elevation = record["elevation"]
            assertThat(elevation["elevationRef"].asString()).startsWith("ELV-")
            assertThat(java.time.Instant.parse(record["validUntil"].asString()))
                .isEqualTo(claims.expirationTime.toInstant())
            assertThat(java.time.Instant.parse(elevation["validUntil"].asString()))
                .isAfter(java.time.Instant.parse(elevation["validFrom"].asString()))
            // a platform engineer holds no elevation cell: masked, so no elevation object
            env.open(env.engineer)
            assertThat(accessRecords(env, env.engineer).single().data["operatorAccessRecord"].has("elevation"))
                .isFalse()
        }

    @Test
    fun `a refused call writes no operator access record and no audit entry`() =
        run { env, _ ->
            env.refusal(env.stranger)
            env.refusal(env.support, tenantRef = "not-a-tenant")
            assertThat(accessRecords(env, env.stranger)).isEmpty()
            assertThat(accessRecords(env, env.support)).isEmpty()
            assertThat(env.auditRows("openOperatorSession")).isEmpty()
        }

    @Test
    fun `the key set record is a platform event under the platform tenant and is not repeated`() {
        val current = TestSecrets.newSigningKey()
        val next = TestSecrets.newSigningKey()
        run(operatorKey = current, next = next) { env, _ ->
            val before = outboxRows("type like '%OperatorSessionKeysChanged%'").size
            env.open(env.support)
            val rows = outboxRows("type like '%OperatorSessionKeysChanged%'")
            assertThat(rows).hasSize(before + 1)
            val row = rows.last()
            assertThat(row.type).isEqualTo("com.dynokore.gst.platform.OperatorSessionKeysChanged.v1")
            assertThat(row.topic).isEqualTo("gst.platform.operator-session-keys-changed.v1")
            assertThat(row.tenant).isEqualTo(platform)
            assertThat(row.subject).isEqualTo("operator-session-keys")
            assertThat(row.data["keys"].toList().size).isBetween(1, 4)
            env.open(env.support)
            assertThat(outboxRows("type like '%OperatorSessionKeysChanged%'")).hasSize(before + 1)
        }
    }

    @Test
    fun `an elevation for another tenant keeps the content masked`() =
        run { env, _ ->
            val other = TenantId(UUID.randomUUID())
            env.structure(other, StructureNodeKind.TENANT, retired = false)
            Seeds.grant(env.context, other, SeedSpec(env.officer, Role.SECURITY_OFFICER))
            val ref =
                env.tenants.bind(other, env.support) {
                    val request = ElevationRequest("CASE-1", other.toString(), "x")
                    env.api
                        .requestElevation(UUID.randomUUID(), request)
                        .body!!
                        .requestRef
                }
            env.tenants.bind(other, env.officer) {
                val grant = DecideElevationRequest(DecideElevationRequest.Decision.GRANT, 30)
                env.api.decideElevation(ref, UUID.randomUUID(), grant)
            }
            assertThat(env.open(env.support).contentMode).isEqualTo(OperatorSessionResponse.ContentMode.MASKED)
            val inOther = env.open(env.support, other.toString())
            assertThat(inOther.contentMode).isEqualTo(OperatorSessionResponse.ContentMode.ELEVATED)
        }

    @Test
    fun `the next key is published before use and the old key stays published after the swap`() {
        val current = TestSecrets.newSigningKey()
        val next = TestSecrets.newSigningKey()
        run(operatorKey = current, next = next) { env, _ ->
            env.open(env.support)
            assertThat(env.storedKeys())
                .contains(kidOf(current) to "OPERATOR_SESSION", kidOf(next) to "OPERATOR_SESSION")
            val event =
                env
                    .keyEvents()
                    .last()["keys"]
                    .toList()
                    .map { it["kid"].asString() }
            assertThat(event).contains(kidOf(current), kidOf(next))
            // the swap: the next key becomes the current one; the old key stays published until its validUntil
            TestSecrets.replaceOperatorKey(env.mount, next)
            val token = SignedJWT.parse(env.open(env.support).token)
            assertThat(token.header.keyID).isEqualTo(kidOf(next))
            assertThat(env.storedKeys().map { it.first }).contains(kidOf(current))
        }
    }

    @Test
    fun `a key past its signing interval is refused with ISSUER_UNAVAILABLE and nothing is issued`() =
        run { env, collector ->
            env.open(env.support)
            env.clock.advance(Duration.ofHours(1).plusSeconds(1))
            assertThat(env.refusal(env.support).code).isEqualTo(ClosedErrorCode.ISSUER_UNAVAILABLE)
            assertThat(env.auditRows("openOperatorSession")).hasSize(1)
            assertThat(collector.await(2)).hasSize(1)
        }

    @Test
    fun `an unreadable, a non-P-256 and the machine key alone are refused and nothing is issued`() {
        run(operatorKey = null) { env, collector ->
            // only the machine signing key is mounted: it never signs an operator token
            assertThat(env.refusal(env.support).code).isEqualTo(ClosedErrorCode.ISSUER_UNAVAILABLE)
            TestSecrets.writeOperatorKeyText(env.mount, "not a key")
            assertThat(env.refusal(env.support).code).isEqualTo(ClosedErrorCode.ISSUER_UNAVAILABLE)
            val wrongCurve =
                com.nimbusds.jose.jwk.gen
                    .ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_384)
                    .generate()
            TestSecrets.writeOperatorKeyText(env.mount, wrongCurve.toJSONString())
            assertThat(env.refusal(env.support).code).isEqualTo(ClosedErrorCode.ISSUER_UNAVAILABLE)
            val publicOnly = TestSecrets.newSigningKey().toPublicJWK()
            TestSecrets.writeOperatorKeyText(env.mount, publicOnly.toJSONString())
            assertThat(env.refusal(env.support).code).isEqualTo(ClosedErrorCode.ISSUER_UNAVAILABLE)
            assertThat(env.auditRows("openOperatorSession")).isEmpty()
            assertThat(env.storedKeys().map { it.second }).doesNotContain("OPERATOR_SESSION")
            assertThat(collector.await(1)).isEmpty()
        }
    }

    @Test
    fun `the signing key encoding of the unit under test is the base64 DER public key`() =
        run { env, _ ->
            env.open(env.support)
            val published =
                env
                    .keyEvents()
                    .last()["keys"]
                    .toList()
                    .first { it["kid"].asString() == kidOf(env.operatorKey) }["publicKey"]
                    .asString()
            assertThat(Base64.getDecoder().decode(published)).isEqualTo(env.operatorKey.toECPublicKey().encoded)
        }
}
