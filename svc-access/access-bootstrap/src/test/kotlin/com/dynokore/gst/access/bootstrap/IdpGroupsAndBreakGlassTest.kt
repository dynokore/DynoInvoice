package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.BreakGlassAlertOutbox
import com.dynokore.gst.access.adapters.http.ApiConflict
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.ApiRefusalAdvice
import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.adapters.http.BreakGlassController
import com.dynokore.gst.access.adapters.http.GrantsController
import com.dynokore.gst.access.adapters.http.IdpGroupController
import com.dynokore.gst.access.adapters.idp.KeycloakDirectoryGroupMembers
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
import com.dynokore.gst.access.application.idp.SyncIdpGroupsUseCase
import com.dynokore.gst.access.contract.model.AuthoriseResponse
import com.dynokore.gst.access.contract.model.BreakGlassActionRequest
import com.dynokore.gst.access.contract.model.BreakGlassResponse
import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.contract.model.IdpGroupMappingRequest
import com.dynokore.gst.access.contract.model.UnlockBreakGlassRequest
import com.dynokore.gst.access.domain.grant.GrantSource
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.serviceruntime.binding.IdentityProvider
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The directory the tests move people in and out of, in place of the identity provider's group listing. */
class MovableDirectory : DirectoryGroupMembers {
    private val groups = ConcurrentHashMap<String, Set<IdentityRef>>()
    var failing = false

    fun set(
        group: String,
        vararg members: String,
    ) {
        groups[group] = members.map { IdentityRef(it) }.toSet()
    }

    override fun members(idpGroup: String): Set<IdentityRef> {
        check(!failing) { "the directory is not reachable" }
        return groups[idpGroup].orEmpty()
    }
}

/**
 * Directory-group provisioning (INV-REQ-892, INV-REQ-1263) and break-glass (INV-REQ-896, INV-REQ-897, INV-REQ-1269) at
 * the service boundary: the real context over a real PostgreSQL, the controllers called under a bound tenant. The
 * directory is [MovableDirectory] (the reading of the identity provider's groups is open, see the MR).
 */
class IdpGroupsAndBreakGlassTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val admin = "user:tenant-admin"
    private val directory = MovableDirectory()
    private val actions = listOf("doc.view", "doc.export", "exception.resolve", "pack.approve")

    private fun run(
        members: DirectoryGroupMembers = directory,
        block: (AssertableWebApplicationContext, Env) -> Unit,
    ) {
        BoundaryFixture
            .runner()
            .withBean(DirectoryGroupMembers::class.java, { members })
            .run { context ->
                context.startupFailure?.let { throw it }
                block(context, Env(context))
            }
    }

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val tx = context.getBean(TransactionRunner::class.java)
        val idpApi = context.getBean(IdpGroupController::class.java)
        val breakGlassApi = context.getBean(BreakGlassController::class.java)
        val grantsApi = context.getBean(GrantsController::class.java)
        val authoriseApi = context.getBean(AuthoriseController::class.java)
        val repository = context.getBean(GrantRepository::class.java)
        val sync = context.getBean(SyncIdpGroupsUseCase::class.java)
        val clock = context.getBean(Clock::class.java) as MovableClock
        val captured = context.getBean(CapturedEvents::class.java)

        init {
            Seeds.grant(context, tenant, SeedSpec(admin, Role.TENANT_ADMIN))
            context
                .getBean(StructureReplica::class.java)
                .accept(
                    OrganisationStructureChanged(
                        tenant,
                        "ENT-1",
                        StructureChange.CREATED,
                        BoundaryFixture.entityNode(),
                    ),
                )
        }

        fun <T> asAdmin(block: () -> T): T = tenants.bind(tenant, admin) { block() }

        fun map(
            group: String,
            role: String,
            scope: String = "entity:ENT-1",
            from: LocalDate? = null,
            to: LocalDate? = null,
        ) = asAdmin {
            idpApi.mapIdpGroup(UUID.randomUUID(), IdpGroupMappingRequest(group, role, scope, from, to))
        }

        fun unmap(ref: String) = asAdmin { idpApi.unmapIdpGroup(ref, UUID.randomUUID()) }

        fun grantsOf(identity: String) =
            tenants.bind(tenant, admin) { tx.inTransaction { repository.findByIdentity(IdentityRef(identity)) } }

        fun synchronise() = asAdmin { sync.run(admin, TenantRef(tenant.toString())) }

        fun events(type: String) = captured.envelopes.filter { it.type == type }

        fun audits(action: String) =
            events("com.dynokore.gst.platform.AuditEntryRecorded.v1").filter { it.dataJson.contains("\"$action\"") }

        fun seed(
            identity: String,
            role: Role,
        ) = Seeds.grant(context, tenant, SeedSpec(identity, role))

        fun decide(
            identity: String,
            permission: String,
        ): AuthoriseResponse.Decision =
            tenants.bind(tenant, admin) { authoriseApi.authorise(identity, permission, "entity:ENT-1").body!!.decision }
    }

    @Test
    fun `INV-AC-1932 a user who leaves a mapped group loses the grant and AuthorityChanged is published`() =
        run { _, env ->
            directory.set("acme-tax", "IDN-U1", "IDN-U2")
            val mapping = env.map("acme-tax", "tax.operator").body!!
            assertThat(env.grantsOf("IDN-U2").single().let { it.status to it.source })
                .isEqualTo(GrantStatus.ACTIVE to GrantSource.DIRECTORY_GROUPS)
            assertThat(env.grantsOf("IDN-U2").single().sourceRef).isEqualTo(mapping.mappingRef)
            env.captured.envelopes.clear()
            directory.set("acme-tax", "IDN-U1")
            env.synchronise()
            assertThat(env.grantsOf("IDN-U2").single().status).isEqualTo(GrantStatus.REVOKED)
            assertThat(env.grantsOf("IDN-U1").single().status).isEqualTo(GrantStatus.ACTIVE)
            val changed = env.events(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType)
            assertThat(changed).hasSize(1)
            assertThat(changed.single().dataJson).contains("IDN-U2")
            // the replica that receives the event holds no action for the leaver
            val replica = AuthorityReplica(env.clock, fallback = null)
            replica.onSnapshotComplete(0)
            changed.forEach { AuthorityFeedHandler(replica).handle(it) }
            assertThat(replica.heldEntities(tenant, "IDN-U2")).isEmpty()
        }

    @Test
    fun `INV-AC-8562 a group served by the simulator provisions the grant and leaving the group removes it`() {
        IdentityProviderSimulator().start().use { simulator ->
            val keycloak =
                KeycloakDirectoryGroupMembers(
                    simulator.baseUri,
                    IdentityProviderSimulator.REALM,
                    IdentityProviderSimulator.CLIENT_ID,
                ) { IdentityProviderSimulator.CLIENT_SECRET }
            run(keycloak) { _, env ->
                val from = LocalDate.now(ZoneOffset.UTC).minusDays(1)
                val to = from.plusDays(60)
                val mapping = env.map("tenant.admin", "tax.operator", from = from, to = to).body!!
                val held = env.grantsOf("tenant-admin-1").single()
                assertThat(held.role).isEqualTo(Role.TAX_OPERATOR)
                assertThat(held.status).isEqualTo(GrantStatus.ACTIVE)
                assertThat(held.sourceRef).isEqualTo(mapping.mappingRef)
                assertThat(held.window.from).isEqualTo(from.atStartOfDay(ZoneOffset.UTC).toInstant())
                assertThat(held.window.until).isEqualTo(to.atStartOfDay(ZoneOffset.UTC).toInstant())
                env.captured.envelopes.clear()
                simulator.removeFromGroup("tenant-admin-1", "tenant.admin")
                env.synchronise()
                assertThat(env.grantsOf("tenant-admin-1").single().status).isEqualTo(GrantStatus.REVOKED)
                assertThat(env.events(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType)).hasSize(1)
            }
        }
    }

    @Test
    fun `a mapping carries the validity of its grants and a repeat returns the original`() =
        run { _, env ->
            directory.set("acme-ops", "IDN-V1")
            val from = LocalDate.now(ZoneOffset.UTC).minusDays(2)
            val to = from.plusDays(30)
            val mapping = env.map("acme-ops", "tax.operator", from = from, to = to)
            assertThat(mapping.statusCode.value()).isEqualTo(201)
            val grant = env.grantsOf("IDN-V1").single()
            assertThat(grant.window.from).isEqualTo(from.atStartOfDay(ZoneOffset.UTC).toInstant())
            assertThat(grant.window.until).isEqualTo(to.atStartOfDay(ZoneOffset.UTC).toInstant())
            val repeat = env.map("acme-ops", "tax.operator", from = from, to = to)
            assertThat(repeat.statusCode.value()).isEqualTo(200)
            assertThat(repeat.body!!.mappingRef).isEqualTo(mapping.body!!.mappingRef)
            val open = env.map("acme-open", "tax.operator").body!!
            assertThat(open.validFrom).isEqualTo(LocalDate.now(ZoneOffset.UTC))
            assertThat(open.validTo).isNull()
        }

    @Test
    fun `a validTo that is not after validFrom, an unknown role and a scope that is not supported are 400`() =
        run { _, env ->
            val day = LocalDate.now(ZoneOffset.UTC)
            listOf(
                { env.map("g", "tax.operator", from = day, to = day) },
                { env.map("g", "no.such.role") },
                { env.map("g", "tax.operator", scope = "group:G1") },
            ).forEach { call ->
                assertThatThrownBy { call() }.isInstanceOf(ApiRefusal::class.java).hasMessageContaining("SRT-001")
            }
        }

    @Test
    fun `unmapIdpGroup revokes the grants made from the mapping, leaves by-hand grants and a repeat is a no-op`() =
        run { _, env ->
            directory.set("acme-fin", "IDN-W1", "IDN-W2")
            env.seed("IDN-W2", Role.TAX_OPERATOR)
            val mapping = env.map("acme-fin", "tax.operator", scope = "tenant:$tenant").body!!
            // W2 holds the role by hand at the same scope: no second grant is made, the by-hand grant is left alone
            assertThat(env.grantsOf("IDN-W2")).hasSize(1)
            assertThat(env.grantsOf("IDN-W2").single().source).isEqualTo(GrantSource.BY_HAND)
            assertThat(env.unmap(mapping.mappingRef).statusCode.value()).isEqualTo(204)
            assertThat(env.grantsOf("IDN-W1").single().status).isEqualTo(GrantStatus.REVOKED)
            assertThat(env.grantsOf("IDN-W2").single().status).isEqualTo(GrantStatus.ACTIVE)
            assertThat(env.unmap(mapping.mappingRef).statusCode.value()).isEqualTo(204)
        }

    @Test
    fun `a synchronisation whose directory cannot be read changes nothing and reports the failure`() =
        run { _, env ->
            directory.set("acme-ro", "IDN-X1")
            env.map("acme-ro", "tax.operator")
            directory.failing = true
            assertThatThrownBy { env.synchronise() }.isInstanceOf(IllegalStateException::class.java)
            assertThat(env.grantsOf("IDN-X1").single().status).isEqualTo(GrantStatus.ACTIVE)
        }

    @Test
    fun `a mapping never completes the security officer and tenant admin pair for one person`() =
        run { _, env ->
            env.seed("IDN-Y1", Role.TENANT_ADMIN)
            directory.set("acme-sec", "IDN-Y1")
            env.map("acme-sec", "security.officer", scope = "tenant:$tenant")
            assertThat(env.grantsOf("IDN-Y1").map { it.role }).containsExactly(Role.TENANT_ADMIN)
        }

    @Test
    fun `a caller who may not manage grants cannot map a group`() =
        run { _, env ->
            assertThatThrownBy {
                env.tenants.bind(tenant, "user:nobody") {
                    val request = IdpGroupMappingRequest("g", "tax.operator", "entity:ENT-1")
                    env.idpApi.mapIdpGroup(UUID.randomUUID(), request)
                }
            }.isInstanceOf(ApiRefusal::class.java).hasMessageContaining("SRT-004")
        }

    private fun pair(env: Env) {
        env.seed("IDN-ADM", Role.TENANT_ADMIN)
        env.seed("IDN-APP", Role.TAX_APPROVER)
    }

    private fun unlock(
        env: Env,
        vararg approvers: String,
    ) = env.tenants.bind(tenant, "IDN-ADM") {
        val request = UnlockBreakGlassRequest(tenant.toString(), approvers.toList())
        env.breakGlassApi.unlockBreakGlass(UUID.randomUUID(), request)
    }

    @Test
    fun `INV-AC-2252 INV-AC-8563 the default pair opens 4 hours of the restricted actions with ACC-002`() =
        run { _, env ->
            pair(env)
            env.captured.envelopes.clear()
            env.clock.freeze()
            val before = env.clock.instant()
            val response = unlock(env, "IDN-ADM", "IDN-APP")
            val body = response.body!!
            assertThat(response.statusCode.value()).isEqualTo(201)
            assertThat(body.status).isEqualTo(BreakGlassResponse.Status.UNLOCKED)
            assertThat(body.sessionHours).isEqualTo(4)
            assertThat(body.expiresAt!!.toInstant()).isEqualTo(before.plus(Duration.ofHours(4)))
            assertThat(body.permittedActions!!.map { it.name }.toSet())
                .isEqualTo(setOf("VIEW", "READINESS", "DRAIN", "REPLAY"))
            val alert = env.events(BreakGlassAlertOutbox.EVENT_TYPE).single()
            assertThat(alert.dataJson).contains("\"accCode\":\"ACC-002\"", "\"action\":\"UNLOCK\"")
            // the session is open one second before the expiry and closed at it
            env.clock.advance(Duration.ofHours(4).minusSeconds(10))
            record(env, body.unlockRef, "VIEW")
            env.clock.advance(Duration.ofSeconds(20))
            val refused = catchThrowable { record(env, body.unlockRef, "VIEW") }
            assertThat(refused).isInstanceOf(ApiConflict::class.java)
            // 409, a detail text and no catalogue code, never SRT-008 (INV-Q-8845 A)
            val conflict = ApiRefusalAdvice().conflict(refused as ApiConflict)
            assertThat(conflict.statusCode.value()).isEqualTo(409)
            val problem =
                tools.jackson.databind.json.JsonMapper
                    .builder()
                    .build()
                    .readTree(conflict.body)
            assertThat(problem.has("code")).isFalse()
            assertThat(problem.path("detail").asString()).isNotBlank()
            assertThat(conflict.body).doesNotContain("SRT-008")
        }

    @Test
    fun `INV-AC-924 INV-AC-8563 one person alone is refused and opens no session`() =
        run { _, env ->
            pair(env)

            fun refused(vararg approvers: String) =
                assertThatThrownBy { unlock(env, *approvers) }
                    .isInstanceOf(ApiRefusal::class.java)
                    .hasMessageContaining("SRT-001")
            // one name is refused by the contract's bound (two to three people) before the use case is reached
            assertThatThrownBy { unlock(env, "IDN-ADM") }
                .hasMessageContaining("approvedBy: size must be between 2 and 3")
            refused("IDN-ADM", "IDN-ADM")
            // two people who do not fill both places are refused with 403, and nothing opens
            env.seed("IDN-OTHER", Role.AUDITOR)
            assertThatThrownBy { unlock(env, "IDN-ADM", "IDN-OTHER") }
                .isInstanceOf(ApiRefusal::class.java)
                .hasMessageContaining("SRT-004")
            // one person holding both roles is still one person
            env.seed("IDN-BOTH", Role.TENANT_ADMIN)
            env.seed("IDN-BOTH", Role.TAX_APPROVER)
            assertThatThrownBy { unlock(env, "IDN-BOTH", "IDN-OTHER") }.isInstanceOf(ApiRefusal::class.java)
            assertThat(env.events(BreakGlassAlertOutbox.EVENT_TYPE)).isEmpty()
        }

    @Test
    fun `INV-AC-1934 INV-AC-8564 every unlock and every action raises one ACC-002 and one audit entry`() =
        run { _, env ->
            pair(env)
            env.captured.envelopes.clear()
            val ref = unlock(env, "IDN-ADM", "IDN-APP").body!!.unlockRef
            record(env, ref, "VIEW")
            record(env, ref, "REPLAY")
            val alerts = env.events(BreakGlassAlertOutbox.EVENT_TYPE)
            assertThat(alerts).hasSize(3)
            val json = EnvelopeJson()
            alerts.forEach { alert ->
                // the real tenant, no entityref when read back, ACC-002
                assertThat(alert.tenant).isEqualTo(tenant)
                assertThat(alert.entity).isEqualTo(EntityRef.TENANT_WIDE)
                assertThat(json.write(alert)).doesNotContain("entityref")
                assertThat(json.read(json.write(alert)).entity).isEqualTo(EntityRef.TENANT_WIDE)
                assertThat(alert.dataJson).contains("\"accCode\":\"ACC-002\"")
            }
            assertThat(alerts.map { it.dataJson.substringAfter("\"action\":\"").substringBefore("\"") })
                .containsExactly("UNLOCK", "VIEW", "REPLAY")
            assertThat(env.audits("unlockBreakGlass") + env.audits("recordBreakGlassAction")).hasSize(3)
            // after the expiry nothing more is raised or written
            env.clock.advance(Duration.ofHours(5))
            assertThatThrownBy { record(env, ref, "DRAIN") }.isInstanceOf(ApiConflict::class.java)
            assertThat(env.events(BreakGlassAlertOutbox.EVENT_TYPE)).hasSize(3)
            assertThat(env.audits("recordBreakGlassAction")).hasSize(2)
        }

    @Test
    fun `an unlock for another tenant is refused and an action on an unknown session is 404`() =
        run { _, env ->
            pair(env)
            assertThatThrownBy {
                env.tenants.bind(tenant, "IDN-ADM") {
                    env.breakGlassApi.unlockBreakGlass(
                        UUID.randomUUID(),
                        UnlockBreakGlassRequest(UUID.randomUUID().toString(), listOf("IDN-ADM", "IDN-APP")),
                    )
                }
            }.isInstanceOf(ApiRefusal::class.java).hasMessageContaining("SRT-004")
            assertThatThrownBy { record(env, "BG-NONE", "VIEW") }
                .isInstanceOf(ApiRefusal::class.java)
                .hasMessageContaining("SRT-005")
        }

    private fun record(
        env: Env,
        unlockRef: String,
        action: String,
    ) = env.tenants.bind(tenant, "IDN-ADM") {
        env.breakGlassApi.recordBreakGlassAction(
            unlockRef,
            UUID.randomUUID(),
            BreakGlassActionRequest(BreakGlassActionRequest.Action.valueOf(action)),
        )
    }

    @Test
    fun `an action outside the four members is refused before anything is recorded`() {
        // the request type admits only the four members: a fifth value does not deserialise
        val json = """{"action":"EXPORT"}"""
        assertThatThrownBy {
            tools.jackson.databind.json.JsonMapper
                .builder()
                .findAndAddModules()
                .build()
                .readValue(json, BreakGlassActionRequest::class.java)
        }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `INV-AC-1914 INV-AC-8560 the same grants authorise the same actions under both identity providers`() {
        // two simulators stand for the platform provider (SaaS) and the customer provider (private)
        IdentityProviderSimulator().start().use { platform ->
            IdentityProviderSimulator().start().use { customer ->
                val outcomes =
                    listOf("SAAS" to platform.issuer, "PRIVATE" to customer.issuer).map { (form, issuer) ->
                        lateinit var result: List<AuthoriseResponse.Decision>
                        BoundaryFixture
                            .runner(form = form)
                            .withPropertyValues("access.identity-provider.issuer-uri=$issuer")
                            .withBean(DirectoryGroupMembers::class.java, { directory })
                            .run { context ->
                                context.startupFailure?.let { throw it }
                                // the binding is reached through its port
                                val binding = context.getBean(IdentityProvider::class.java)
                                assertThat(binding.issuer().issuerUri).isEqualTo(issuer)
                                val env = Env(context)
                                env.seed("IDN-A1", Role.AUDITOR)
                                env.seed("IDN-A2", Role.TAX_APPROVER)
                                result =
                                    listOf("IDN-A1", "IDN-A2", "IDN-NONE").flatMap { who ->
                                        actions.map { env.decide(who, it) }
                                    }
                            }
                        result
                    }
                assertThat(outcomes[0]).isEqualTo(outcomes[1])
                assertThat(outcomes[0]).contains(AuthoriseResponse.Decision.ALLOW, AuthoriseResponse.Decision.DENY)
            }
        }
    }
}
