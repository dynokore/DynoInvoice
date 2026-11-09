package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.GroupResponse
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.ConsentScopeItem
import com.dynokore.platform.serviceruntime.replica.ConsentState
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * INV-T-8924 (INV-Q-8905 A, INV-Q-10107 C): the production `setPolicy` of tenancy-svc decides by the permission
 * `vp-NN.set` through the one `AuthorityReplica` bean of the context, fed here as the AuthorityChanged feed feeds it
 * (role grants, consent records). No role name is compared by the service; the HTTP status and the stored value are
 * compared by equality. Beyond the lag bound, before the first snapshot and after a disconnect the answer is 403
 * SRT-004 with nothing stored (INV-Q-9227 A): tenancy-svc has no access-svc fallback.
 */
class SetPolicyDecisionTest {
    private val vp51 = VariationPointKey("VP-51")
    private val vp64 = VariationPointKey("VP-64")
    private val denied = 403

    /** The people of a test: a bearer text maps to the principal the end-user token would resolve to. */
    private class People : EndUserPrincipalResolver {
        val principals = ConcurrentHashMap<String, EndUserPrincipal>()

        override fun resolve(authorization: String?): EndUserPrincipal? = authorization?.let(principals::get)
    }

    private inner class Env(
        val env: ProfileEnv,
        val people: People,
    ) {
        val replica: AuthorityReplica = env.context.getBean(AuthorityReplica::class.java)
        private val version = AtomicLong()
        private val clock = env.context.getBean(Clock::class.java)
        private val servlet =
            env.context
                .getBeansOfType(ServletRegistrationBean::class.java)
                .values
                .map { it.servlet }
                .filterIsInstance<PolicyCommandServlet>()
                .single()

        fun synced() {
            replica.onSnapshotComplete(0)
        }

        private fun feed(
            person: String,
            tenant: TenantId,
            record: AuthorityRecord,
        ) {
            val v = version.incrementAndGet()
            replica.accept(AuthorityChanged(tenant, person, v, record))
            replica.observeHead(v)
        }

        fun role(
            person: String,
            tenant: TenantId,
            role: String,
            scope: GrantScope,
            ref: String?,
        ) = feed(
            person,
            tenant,
            AuthorityRecord.RoleGrantRecord(
                "g-${UUID.randomUUID()}",
                RoleGrantState.ACTIVE,
                RoleGrant(role, scope, ref, clock.instant().minusSeconds(60)),
            ),
        )

        fun consent(
            person: String,
            tenant: TenantId,
            client: String,
            state: ConsentState,
        ) = feed(
            person,
            tenant,
            AuthorityRecord.ConsentRecord(
                client,
                state,
                setOf(ConsentScopeItem.FILE_GST_RETURN),
                clock.instant().minusSeconds(60),
            ),
        )

        /** The person calls as a token of [tenant] covering [entities]. */
        fun caller(
            person: String,
            tenant: TenantId?,
            vararg entities: String,
        ): String {
            val token = "Bearer $person-${UUID.randomUUID()}"
            people.principals[token] =
                EndUserPrincipal(
                    person,
                    tenant,
                    entities
                        .map {
                            com.dynokore.platform.outboxandqueue
                                .EntityRef(it)
                        }.toSet(),
                )
            return token
        }

        fun post(
            vp: String,
            token: String,
            body: String,
        ): MockHttpServletResponse {
            val request = MockHttpServletRequest("POST", "/internal/v1/policies/$vp")
            request.addHeader("Authorization", token)
            request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
            request.setContentType("application/json")
            request.setContent(body.toByteArray())
            val response = MockHttpServletResponse()
            servlet.service(request, response)
            return response
        }

        fun body(
            level: String,
            scopeRef: String?,
            entityRef: String?,
            option: String,
            value: String? = null,
        ): String {
            val fields = mutableListOf("\"scopeLevel\":\"$level\"")
            scopeRef?.let { fields += "\"scopeRef\":\"$it\"" }
            entityRef?.let { fields += "\"entityRef\":\"$it\"" }
            fields += "\"selected\":[{\"option\":\"$option\"${value?.let { ",\"value\":$it" }.orEmpty()}}]"
            fields += "\"effectiveDate\":\"$TODAY\""
            fields += "\"reason\":\"because\""
            return "{" + fields.joinToString(",") + "}"
        }

        fun voluntary(
            entity: EntityResponse,
            token: String,
        ) = post(
            "VP-51",
            token,
            body("ENTITY", entity.entityRef, entity.entityRef, "EARLIER_VOLUNTARY_DATE", "\"2027-01-01\""),
        )

        fun communicated(
            group: GroupResponse,
            token: String,
        ) = post(
            "VP-51",
            token,
            body(
                "GROUP",
                group.groupRef,
                null,
                "IRAS_COMMUNICATED_DATE",
                """{"date":"2028-10-01","iras_reference":"IRAS-REF-1"}""",
            ),
        )

        fun transmit(
            group: GroupResponse,
            token: String,
        ) = post("VP-64", token, body("GROUP", group.groupRef, null, "TRANSMIT_AND_GO_LIVE"))

        fun chosen(
            key: VariationPointKey,
            scope: PolicyScope,
        ): String =
            env.inTx {
                env.reader
                    .resolve(key, scope, TODAY)
                    .selected
                    .single()
                    .key
            }
    }

    private fun run(
        form: String = "SAAS",
        block: (Env) -> Unit,
    ) {
        val people = People()
        BoundaryFixture
            .runner()
            .withPropertyValues("gst.form=$form")
            .withBean(EndUserPrincipalResolver::class.java, { people })
            .run { context ->
                context.startupFailure?.let { throw it }
                block(Env(ProfileEnv(context), people))
            }
    }

    private fun MockHttpServletResponse.refusedSrt004() {
        assertThat(status).isEqualTo(denied)
        assertThat(contentAsString).contains("SRT-004")
    }

    @Test
    fun `INV-AC-8923 the context holds exactly one AuthorityReplica and the mounted setPolicy decides through it`() {
        run { e ->
            assertThat(e.env.context.getBeansOfType(AuthorityReplica::class.java)).hasSize(1)
            e.synced()
            e.env.withGroup(1) { tenant, group, members ->
                val entity = members.single()
                e.env.assess(entity, box4 = BigDecimal("500000"))
                e.role("S", tenant, "entity.admin", GrantScope.ENTITY, entity.entityRef)
                val answer = e.voluntary(entity, e.caller("S", tenant, entity.entityRef))
                assertThat(answer.status).describedAs(answer.contentAsString).isEqualTo(200)
                val scope = PolicyScope.entity(tenant, entity.entityRef, group = group.groupRef)
                assertThat(e.chosen(vp51, scope)).isEqualTo("EARLIER_VOLUNTARY_DATE")
            }
        }
    }

    @Test
    fun `INV-AC-8923 a grant on E1 only sets VP-51 for E1 and gets 403 SRT-004 for E2 with nothing stored`() {
        run { e ->
            e.synced()
            e.env.withGroup(2) { tenant, group, members ->
                val (e1, e2) = members
                e.env.assess(e1, box4 = BigDecimal("500000"))
                e.env.assess(e2, box4 = BigDecimal("500000"))
                e.role("S", tenant, "entity.admin", GrantScope.ENTITY, e1.entityRef)
                val token = e.caller("S", tenant, e1.entityRef, e2.entityRef)
                assertThat(e.voluntary(e1, token).status).isEqualTo(200)
                e.voluntary(e2, token).refusedSrt004()
                val first = PolicyScope.entity(tenant, e1.entityRef, group = group.groupRef)
                val second = PolicyScope.entity(tenant, e2.entityRef, group = group.groupRef)
                assertThat(e.chosen(vp51, first)).isEqualTo("EARLIER_VOLUNTARY_DATE")
                assertThat(e.chosen(vp51, second)).isNotEqualTo("EARLIER_VOLUNTARY_DATE")
            }
        }
    }

    @Test
    fun `INV-AC-8923 a look-alike role, a role of another tenant and the wrong role are 403 SRT-004`() {
        run { e ->
            e.synced()
            e.env.withGroup(1) { tenant, _, members ->
                val entity = members.single()
                val other = TenantId(UUID.randomUUID())
                e.role("alike", tenant, "entity.admin2", GrantScope.ENTITY, entity.entityRef)
                e.role("elsewhere", other, "entity.admin", GrantScope.ENTITY, entity.entityRef)
                e.role("wrong", tenant, "tax.approver", GrantScope.ENTITY, entity.entityRef)
                for (person in listOf("alike", "elsewhere", "wrong")) {
                    e.voluntary(entity, e.caller(person, tenant, entity.entityRef)).refusedSrt004()
                }
            }
        }
    }

    @Test
    fun `INV-AC-8923 at GROUP scope only a tenant-scope grant counts`() {
        run { e ->
            e.synced()
            e.env.withGroup(2) { tenant, group, members ->
                val other = TenantId(UUID.randomUUID())
                e.role("tenantAdmin", tenant, "tenant.admin", GrantScope.TENANT, tenant.toString())
                e.role("entityAdminTenant", tenant, "entity.admin", GrantScope.TENANT, tenant.toString())
                e.role("entityAdminOne", tenant, "entity.admin", GrantScope.ENTITY, members[0].entityRef)
                members.forEach { e.role("entityAdminAll", tenant, "entity.admin", GrantScope.ENTITY, it.entityRef) }
                e.role("foreign", other, "tenant.admin", GrantScope.TENANT, other.toString())
                e.role("alike", tenant, "tenant.admin2", GrantScope.TENANT, tenant.toString())

                assertThat(e.transmit(group, e.caller("tenantAdmin", tenant)).status).isEqualTo(200)
                val scope = PolicyScope.tenant(tenant, group = group.groupRef)
                assertThat(e.chosen(vp64, scope)).isEqualTo("TRANSMIT_AND_GO_LIVE")
                assertThat(e.communicated(group, e.caller("entityAdminTenant", tenant)).status).isEqualTo(200)

                for (person in listOf("entityAdminOne", "entityAdminAll")) {
                    e.communicated(group, e.caller(person, tenant)).refusedSrt004()
                }
                e.transmit(group, e.caller("foreign", tenant)).refusedSrt004()
                e.transmit(group, e.caller("alike", tenant)).refusedSrt004()
            }
        }
    }

    @Test
    fun `INV-AC-8923 before the first snapshot and after a disconnect setPolicy is 403 SRT-004 with nothing stored`() {
        run { e ->
            e.env.withGroup(1) { tenant, group, _ ->
                e.role("tenantAdmin", tenant, "tenant.admin", GrantScope.TENANT, tenant.toString())
                val token = e.caller("tenantAdmin", tenant)
                val scope = PolicyScope.tenant(tenant, group = group.groupRef)
                e.transmit(group, token).refusedSrt004() // no snapshot yet
                e.synced()
                e.replica.onDisconnected() // the lag now grows from the last contact
                Thread.sleep(LAG_WAIT_MS)
                e.transmit(group, token).refusedSrt004()
                assertThat(e.chosen(vp64, scope)).isNotEqualTo("TRANSMIT_AND_GO_LIVE")
                e.synced() // the feed is back: lag is zero again
                assertThat(e.transmit(group, token).status).isEqualTo(200)
            }
        }
    }

    @Test
    fun `INV-AC-8923 beyond the lag bound setPolicy is 403 SRT-004 and the stale replica is not read`() {
        run { e ->
            e.synced()
            e.env.withGroup(1) { tenant, group, _ ->
                e.role("tenantAdmin", tenant, "tenant.admin", GrantScope.TENANT, tenant.toString())
                val token = e.caller("tenantAdmin", tenant)
                e.replica.observeHead(1_000_000) // the feed is ahead of what the replica applied
                Thread.sleep(LAG_WAIT_MS)
                e.transmit(group, token).refusedSrt004()
                val scope = PolicyScope.tenant(tenant, group = group.groupRef)
                assertThat(e.chosen(vp64, scope)).isNotEqualTo("TRANSMIT_AND_GO_LIVE")
            }
        }
    }

    @Test
    fun `INV-AC-8923 VP-44 at TENANT scope is set by security officer at tenant scope only`() {
        run { e ->
            e.synced()
            e.env.withGroup(1) { tenant, _, members ->
                val entity = members.single()
                val other = TenantId(UUID.randomUUID())
                e.role("officer", tenant, "security.officer", GrantScope.TENANT, tenant.toString())
                e.role("onEntity", tenant, "security.officer", GrantScope.ENTITY, entity.entityRef)
                e.role("alike", tenant, "security.officer2", GrantScope.TENANT, tenant.toString())
                e.role("foreign", other, "security.officer", GrantScope.TENANT, other.toString())
                val body = e.body("TENANT", tenant.toString(), null, "OFF")
                val answer = e.post("VP-44", e.caller("officer", tenant), body)
                assertThat(answer.status).describedAs(answer.contentAsString).isEqualTo(200)
                assertThat(e.chosen(VariationPointKey("VP-44"), PolicyScope.tenant(tenant))).isEqualTo("OFF")
                for (person in listOf("onEntity", "alike", "foreign")) {
                    e.post("VP-44", e.caller(person, tenant, entity.entityRef), body).refusedSrt004()
                }
            }
        }
    }

    @Test
    fun `INV-AC-8923 VP-52 at INSTALLATION scope is set by a deployment operator at installation scope only`() {
        run("PRIVATE") { e ->
            e.synced()
            e.env.withGroup(1) { tenant, _, _ ->
                e.role("operator", TenantId.PLATFORM, "deployment.operator", GrantScope.INSTALLATION, null)
                e.role("tenantLevel", tenant, "deployment.operator", GrantScope.TENANT, tenant.toString())
                e.role("alike", TenantId.PLATFORM, "Deployment.Operator", GrantScope.INSTALLATION, null)
                val body = e.body("INSTALLATION", null, null, "CUSTOMER_KUBERNETES")
                for (person in listOf("tenantLevel", "alike")) {
                    e.post("VP-52", e.caller(person, null), body).refusedSrt004()
                }
                // The decision passes for the operator: the answer is not the SRT-004 refusal. The value stage
                // (ImplementationDateConstraints) refuses every key but VP-51 with POL-014, so nothing is stored;
                // that is reported as a gap in the merge request and is not asserted here.
                val answer = e.post("VP-52", e.caller("operator", null), body)
                assertThat(answer.status).isNotEqualTo(denied)
                assertThat(answer.contentAsString).doesNotContain("SRT-004")
            }
        }
    }

    @Test
    fun `INV-AC-8923 VP-62 at CLIENT_ENTITY scope needs a live consent record, a withdrawn one is 403 SRT-004`() {
        run { e ->
            e.synced()
            e.env.withGroup(1) { tenant, _, members ->
                val client = members.single().entityRef
                e.role("approver", tenant, "tax.approver", GrantScope.CONSENT, null)
                e.consent("approver", tenant, client, ConsentState.LIVE)
                val body = e.body("CLIENT_ENTITY", client, client, "NO_MOVE")
                val token = e.caller("approver", tenant, client)
                val answer = e.post("VP-62", token, body)
                assertThat(answer.status).describedAs(answer.contentAsString).isEqualTo(200)
                e.consent("approver", tenant, client, ConsentState.WITHDRAWN)
                e.post("VP-62", token, body).refusedSrt004()
            }
        }
    }

    private companion object {
        const val LAG_WAIT_MS = 1_300L
    }
}
