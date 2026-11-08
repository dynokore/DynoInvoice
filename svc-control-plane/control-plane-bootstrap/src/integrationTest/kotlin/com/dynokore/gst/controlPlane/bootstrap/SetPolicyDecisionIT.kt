package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.ControlPlaneHarness
import com.dynokore.gst.controlPlane.adapters.PostgresDb
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.PrincipalState
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * INV-T-9238 (INV-Q-8972 A, INV-Q-10107 C): the production `setPolicy` of control-plane-svc decides VP-45 by the
 * permission `vp-45.set` through the one `AuthorityReplica` bean of the composition, fed here as the AuthorityChanged
 * feed feeds it. The holder set of `vp-45.set` is the role `commercial.admin` (gst-catalogue table). The HTTP answer
 * and the stored value read back by `PolicyReader` are compared by equality; no role name is compared by the service.
 */
class SetPolicyDecisionIT {
    private val vp45 = VariationPointKey("VP-45")

    private class People : EndUserPrincipalResolver {
        val principals = ConcurrentHashMap<String, EndUserPrincipal>()

        override fun resolve(authorization: String?): EndUserPrincipal? = authorization?.let(principals::get)
    }

    private inner class Env(
        val context: AssertableApplicationContext,
        val people: People,
    ) {
        val replica: AuthorityReplica = context.getBean(AuthorityReplica::class.java)
        private val version = AtomicLong()
        private val clock = context.getBean(Clock::class.java)

        // the business date of INV-Q-3140
        private val today: LocalDate = LocalDate.now(clock.withZone(ZoneId.of("Asia/Singapore")))
        private val servlet =
            context
                .getBeansOfType(ServletRegistrationBean::class.java)
                .values
                .map { it.servlet }
                .filterIsInstance<PolicyCommandServlet>()
                .single()

        fun synced() = replica.onSnapshotComplete(0)

        private fun feed(
            person: String,
            tenant: TenantId,
            record: AuthorityRecord,
        ) {
            val v = version.incrementAndGet()
            replica.accept(AuthorityChanged(tenant, person, v, record))
            replica.observeHead(v)
        }

        @Suppress("LongParameterList") // a test helper: one grant has these independent fields (CS-50)
        fun role(
            person: String,
            tenant: TenantId,
            role: String,
            scope: GrantScope,
            ref: String?,
            grantRef: String = "g-${UUID.randomUUID()}",
            state: RoleGrantState = RoleGrantState.ACTIVE,
            from: Long = -60,
            until: Long? = null,
        ) = feed(
            person,
            tenant,
            AuthorityRecord.RoleGrantRecord(
                grantRef,
                state,
                RoleGrant(
                    role,
                    scope,
                    ref,
                    clock.instant().plusSeconds(from),
                    until?.let { clock.instant().plusSeconds(it) },
                ),
            ),
        )

        fun revoke(
            person: String,
            tenant: TenantId,
        ) = feed(person, tenant, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED))

        fun caller(
            person: String,
            tenant: TenantId,
            vararg entities: String,
        ): String {
            val token = "Bearer $person-${UUID.randomUUID()}"
            people.principals[token] = EndUserPrincipal(person, tenant, entities.map { EntityRef(it) }.toSet())
            return token
        }

        /** `POST /internal/v1/policies/VP-45` at TENANT scope of [tenant], choosing the region [region]. */
        fun setRegion(
            token: String,
            tenant: TenantId,
            region: String = "SG",
        ): MockHttpServletResponse {
            val request = MockHttpServletRequest("POST", "/internal/v1/policies/VP-45")
            request.addHeader("Authorization", token)
            request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
            request.contentType = "application/json"
            request.setContent(
                (
                    """{"scopeLevel":"TENANT","scopeRef":"$tenant",""" +
                        """"selected":[{"option":"REGION","value":"$region"}],""" +
                        """"effectiveDate":"$today","reason":"INV-T-9238"}"""
                ).toByteArray(),
            )
            val response = MockHttpServletResponse()
            servlet.service(request, response)
            return response
        }

        /** The value PolicyReader gives for VP-45 at TENANT scope of [tenant], or null while nothing is stored. */
        fun stored(tenant: TenantId): String? =
            context.getBean(TenantContext::class.java).bind(tenant, "reader") {
                context.getBean(TransactionRunner::class.java).inTransaction {
                    runCatching {
                        context
                            .getBean(PolicyReader::class.java)
                            .resolve(vp45, PolicyScope.tenant(tenant), today)
                            .selected
                            .singleOrNull()
                            ?.valueJson
                    }.getOrNull()
                }
            }
    }

    private fun run(
        mount: Path,
        block: (Env) -> Unit,
    ) {
        val database = PostgresDb.runtimeDataSource as PGSimpleDataSource
        ControlPlaneHarness.ownerDsl().transaction { configuration ->
            val tx = DSL.using(configuration)
            tx.execute("set local role controlplane_sys")
            BindingKeyMigration.writeKeys(tx, "controlplane", BINDING_KEY)
        }
        Files.createDirectories(mount.resolve("db"))
        Files.writeString(mount.resolve("db").resolve("controlplane_runtime"), database.getPassword())
        val people = People()
        controlPlaneRunnerFor(
            mount,
            people,
            "spring.datasource.url=${database.getURL().substringBefore('?')}",
            "spring.datasource.username=${database.getUser()}",
            "spring.flyway.enabled=false",
            "service-runtime.replica.authority-lag-bound=PT1S",
        ).run { context: AssertableApplicationContext ->
            context.startupFailure shouldBe null
            block(Env(context, people))
        }
    }

    private fun MockHttpServletResponse.refusedSrt004() {
        status shouldBe DENIED
        contentAsString shouldContain "SRT-004"
    }

    private fun newTenant() = TenantId(UUID.randomUUID())

    @Test
    fun `INV-AC-2224 one AuthorityReplica in the context and commercial admin at TENANT scope sets VP-45`(
        @TempDir mount: Path,
    ) = run(mount) { e ->
        e.context.getBeansOfType(AuthorityReplica::class.java).size shouldBe 1
        e.synced()
        val tenant = newTenant()
        e.role("holder", tenant, "commercial.admin", GrantScope.TENANT, tenant.toString())
        val answer = e.setRegion(e.caller("holder", tenant), tenant)
        withClue(answer.contentAsString) { answer.status shouldBe 200 }
        e.stored(tenant) shouldBe "\"SG\""
    }

    @Test
    fun `INV-AC-2224 a region key absent from the region list is 422 POL-014 and nothing is stored`(
        @TempDir mount: Path,
    ) = run(mount) { e ->
        e.synced()
        val tenant = newTenant()
        e.role("holder", tenant, "commercial.admin", GrantScope.TENANT, tenant.toString())
        val token = e.caller("holder", tenant)
        for (region in listOf("ZZ", "SGX", "sg")) {
            val answer = e.setRegion(token, tenant, region)
            withClue("$region: ${answer.contentAsString}") { answer.status shouldBe 422 }
            answer.contentAsString shouldContain "POL-014"
            e.stored(tenant) shouldBe null
        }
    }

    @Test
    fun `INV-AC-2224 a commercial admin of entity E1 only is 403 SRT-004 at TENANT level and nothing is stored`(
        @TempDir mount: Path,
    ) = run(mount) { e ->
        e.synced()
        val tenant = newTenant()
        e.role("entityOnly", tenant, "commercial.admin", GrantScope.ENTITY, "E1")
        e.setRegion(e.caller("entityOnly", tenant, "E1"), tenant).refusedSrt004()
        e.stored(tenant) shouldBe null
    }

    @Test
    fun `INV-AC-2224 the look-alike role commercial admin2 is 403 SRT-004 and nothing is stored`(
        @TempDir mount: Path,
    ) = run(mount) { e ->
        e.synced()
        val tenant = newTenant()
        e.role("alike", tenant, "commercial.admin2", GrantScope.TENANT, tenant.toString())
        e.setRegion(e.caller("alike", tenant), tenant).refusedSrt004()
        e.stored(tenant) shouldBe null
    }

    @Test
    fun `INV-AC-2224 a caller holding another role of the catalogue is 403 SRT-004 and nothing is stored`(
        @TempDir mount: Path,
    ) = run(mount) { e ->
        e.synced()
        val tenant = newTenant()
        e.role("other", tenant, "tenant.admin", GrantScope.TENANT, tenant.toString())
        e.setRegion(e.caller("other", tenant), tenant).refusedSrt004()
        e.stored(tenant) shouldBe null
    }

    @Test
    fun `INV-AC-2224 a grant of another tenant, an expired, a removed and a voided grant are each 403 SRT-004`(
        @TempDir mount: Path,
    ) = run(mount) { e ->
        e.synced()
        val tenant = newTenant()
        val elsewhere = newTenant()
        e.role("foreign", elsewhere, "commercial.admin", GrantScope.TENANT, elsewhere.toString())
        e.role("expired", tenant, "commercial.admin", GrantScope.TENANT, tenant.toString(), from = -120, until = -60)
        e.role("removed", tenant, "commercial.admin", GrantScope.TENANT, tenant.toString(), grantRef = "r1")
        e.role(
            "removed",
            tenant,
            "commercial.admin",
            GrantScope.TENANT,
            tenant.toString(),
            grantRef = "r1",
            state = RoleGrantState.REMOVED,
        )
        e.role("voided", tenant, "commercial.admin", GrantScope.TENANT, tenant.toString())
        e.revoke("voided", tenant)
        for (person in listOf("foreign", "expired", "removed", "voided")) {
            e.setRegion(e.caller(person, tenant), tenant).refusedSrt004()
        }
        e.stored(tenant) shouldBe null
    }

    @Test
    fun `INV-AC-2224 before the first snapshot and beyond the lag bound the answer is 403 SRT-004`(
        @TempDir mount: Path,
    ) = run(mount) { e ->
        val tenant = newTenant()
        e.role("holder", tenant, "commercial.admin", GrantScope.TENANT, tenant.toString())
        val token = e.caller("holder", tenant)
        e.setRegion(token, tenant).refusedSrt004() // no snapshot
        e.synced()
        e.replica.observeHead(1_000_000) // the feed is ahead of what the replica applied
        Thread.sleep(LAG_WAIT.toMillis())
        e.setRegion(token, tenant).refusedSrt004()
        e.stored(tenant) shouldBe null
    }

    private companion object {
        val LAG_WAIT: Duration = Duration.ofMillis(1_300)
        const val DENIED = 403
        val BINDING_KEY =
            object : BindingKeySource {
                override fun currentKey(): ByteArray = "test-installation-binding-key".toByteArray()

                override fun previousKey(): ByteArray? = null
            }
    }
}
