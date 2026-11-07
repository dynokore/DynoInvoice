package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.EntitiesController
import com.dynokore.gst.tenancy.adapters.http.TenantsController
import com.dynokore.gst.tenancy.application.status.EntityLifecycleGuard
import com.dynokore.gst.tenancy.application.status.EntityStatusQueries
import com.dynokore.gst.tenancy.application.status.PhaseAssessmentStore
import com.dynokore.gst.tenancy.contract.model.AssessPhaseRequest
import com.dynokore.gst.tenancy.contract.model.CreateEntityRequest
import com.dynokore.gst.tenancy.contract.model.CreateGroupRequest
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.GroupResponse
import com.dynokore.gst.tenancy.contract.model.PhaseAssessmentReturn
import com.dynokore.gst.tenancy.contract.model.SetEntityStatusRequest
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.configresolver.AcknowledgeRelaxationCommand
import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.PolicyChange
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.RelaxationStatus
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.SetterPermission
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.serviceruntime.policy.RequestBoundSetterPermission
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal typealias Excl = SetEntityStatusRequest.ExcludedBusinessStatus

internal fun day(text: String): LocalDate = LocalDate.parse(text)

/** The business date of the writer's clock: a policy value is effective from today or later, never earlier. */
internal val TODAY: LocalDate = LocalDate.now(ZoneId.of("Asia/Singapore"))

internal fun figure(text: String) = BigDecimal(text)

internal fun gstReturn(
    start: String,
    end: String,
    box4: String,
) = PhaseAssessmentReturn(day(start), day(end), figure(box4))

/** The application context of the service over PostgreSQL, with the helpers the profile tests share. */
internal class ProfileEnv(
    context: AssertableWebApplicationContext,
) {
    val context: AssertableWebApplicationContext = context
    val tenantsApi: TenantsController = context.getBean(TenantsController::class.java)
    val api: EntitiesController = context.getBean(EntitiesController::class.java)
    val queries: EntityStatusQueries = context.getBean(EntityStatusQueries::class.java)
    val guard: EntityLifecycleGuard = context.getBean(EntityLifecycleGuard::class.java)
    val tenants: TenantContext = context.getBean(TenantContext::class.java)
    val captured: CapturedEvents = context.getBean(CapturedEvents::class.java)
    val writer: PolicyWriter = context.getBean(PolicyWriter::class.java)
    val reader: PolicyReader = context.getBean(PolicyReader::class.java)
    val assessments: PhaseAssessmentStore = context.getBean(PhaseAssessmentStore::class.java)
    private val tx: TransactionRunner = context.getBean(TransactionRunner::class.java)

    fun <T> inTenant(
        tenant: TenantId,
        block: () -> T,
    ): T = tenants.bind(tenant, "user:test") { block() }

    /** A new tenant; [block] runs bound to it. */
    fun <T> withTenant(block: (TenantId) -> T): T {
        val contract = "C-${UUID.randomUUID()}"
        val ref = TenantRefs.refOfContract(contract)
        tenantsApi.createTenant(UUID.randomUUID(), CreateTenantRequest(contract, "Tenant $contract", "PLAN-A", ref))
        val tenant = TenantRefs.idOf(ref)
        return inTenant(tenant) { block(tenant) }
    }

    fun group(
        tenant: TenantId,
        kind: CreateGroupRequest.GroupKind = CreateGroupRequest.GroupKind.GST_GROUP,
    ): GroupResponse =
        api
            .createGroup(
                UUID.randomUUID(),
                CreateGroupRequest(
                    tenant.value.toString(),
                    "Group ${UUID.randomUUID()}",
                    kind,
                    if (kind == CreateGroupRequest.GroupKind.GST_GROUP) "GSTN-1" else null,
                ),
            ).body!!

    fun entity(
        tenant: TenantId,
        group: GroupResponse,
    ): EntityResponse =
        api
            .createEntity(
                UUID.randomUUID(),
                CreateEntityRequest(
                    tenant.value.toString(),
                    group.groupRef,
                    "Entity ${UUID.randomUUID()}",
                    CreateEntityRequest.Constitution.PRIVATE_LIMITED,
                    "U${UUID.randomUUID().toString().take(8).uppercase()}",
                    null,
                    null,
                ),
            ).body!!

    /** A tenant with one group holding [members] entities; [block] runs bound to the tenant. */
    fun <T> withGroup(
        members: Int,
        kind: CreateGroupRequest.GroupKind = CreateGroupRequest.GroupKind.GST_GROUP,
        block: (TenantId, GroupResponse, List<EntityResponse>) -> T,
    ): T =
        withTenant { tenant ->
            val group = group(tenant, kind)
            block(tenant, group, List(members) { entity(tenant, group) })
        }

    /** A tenant with one entity in a DIVISIONAL group (INV-Q-8915 A: it follows the entity default). */
    fun <T> withEntity(block: (TenantId, EntityResponse) -> T): T =
        withGroup(1, CreateGroupRequest.GroupKind.DIVISIONAL) { tenant, _, entities ->
            block(tenant, entities.single())
        }

    fun set(
        entity: EntityResponse,
        request: SetEntityStatusRequest,
    ) = api.setEntityStatus(entity.entityRef, UUID.randomUUID(), request)

    fun assess(
        entity: EntityResponse,
        returns: List<PhaseAssessmentReturn>? = null,
        box4: BigDecimal? = null,
        extrapolate: Boolean? = null,
    ) = api.assessPhase(
        entity.entityRef,
        UUID.randomUUID(),
        AssessPhaseRequest(day("2026-01-01"), box4, returns, extrapolate),
    )

    /** setPolicy of config-resolver's writer, as the shared setPolicy operation calls it, in the caller's tenant. */
    @Suppress("LongParameterList") // mirrors the fields of SetPolicyCommand the tests vary
    fun setPolicy(
        key: String,
        level: ScopeLevel,
        group: GroupResponse,
        entity: EntityResponse?,
        option: String,
        valueJson: String? = null,
        role: String = "tenant.admin",
        effectiveFrom: LocalDate = TODAY,
    ) = tx.inTransaction {
        val tenant = tenants.current()
        val scope =
            if (entity == null) {
                PolicyScope.tenant(tenant, group = group.groupRef)
            } else {
                PolicyScope.entity(tenant, entity.entityRef, group = group.groupRef)
            }
        writer.asRole(tenants, role).setPolicy(
            SetPolicyCommand(
                VariationPointKey(key),
                scope,
                level,
                listOf(SelectedOption(option, valueJson)),
                effectiveFrom,
                "test",
                Actor("user:test"),
                CorrelationId("corr-${UUID.randomUUID()}"),
                CausationId("cause-${UUID.randomUUID()}"),
            ),
        )
    }

    /** A read of the service's own state, in a transaction of the bound tenant. */
    fun <T> inTx(block: () -> T): T = tx.inTransaction { block() }

    /** The assessment stored for the entity, read as the service reads it. */
    fun stored(entity: EntityResponse) =
        tx.inTransaction {
            val record =
                context
                    .getBean(com.dynokore.gst.tenancy.application.structure.StructureStore::class.java)
                    .entity(entity.entityRef)!!
            assessments.latest(record.id)
        }
}

/**
 * The writer as the person [person] holding [roles] (INV-Q-10027): at each call the person is given the roles by
 * [TestPeople.grantRole] at the scope the command writes at (ENTITY on the entity, or TENANT), in a real
 * [AuthorityReplica] built over the generated catalogue matrix, and the one [SetterPermission] below asks that
 * replica's `decide` for the permission key the writer asks about. No role name is compared here; an empty [roles]
 * holds nothing, so the writer refuses with POL-004.
 */
internal fun PolicyWriter.asRoles(
    tenants: TenantContext,
    roles: Set<String>,
    person: String = "user:test",
): PolicyWriter {
    val delegate = this
    val people = TestPeople()
    val decided =
        SetterPermission { _, _, level, _, ofEntity, permissionKey ->
            val request =
                AuthorizationRequest(
                    tenants.current(),
                    Principal(person, PrincipalKind.HUMAN),
                    ofEntity.orEmpty(),
                    permissionKey,
                )
            val decision =
                if (level == ScopeLevel.ENTITY) {
                    people.replica.decide(request, Deadline.after(Duration.ofSeconds(2), Clock.systemUTC()))
                } else {
                    people.replica.decideTenant(request.tenant, request.principal, permissionKey)
                }
            decision.allowed
        }

    fun grant(
        scope: PolicyScope,
        level: ScopeLevel,
    ) {
        val tenant = tenants.current()
        roles.forEach {
            if (level == ScopeLevel.ENTITY) {
                people.grantRole(person, checkNotNull(scope.entity), it, site = GrantSite(tenant))
            } else {
                people.grantRole(person, tenant.value.toString(), it, site = GrantSite(tenant, GrantScope.TENANT))
            }
        }
    }
    return object : PolicyWriter by delegate {
        override fun setPolicy(command: SetPolicyCommand): PolicyChange {
            grant(command.scope, command.level)
            return RequestBoundSetterPermission.bind(decided) { delegate.setPolicy(command) }
        }

        override fun acknowledgeControlRelaxation(command: AcknowledgeRelaxationCommand): RelaxationStatus {
            grant(command.scope, command.level)
            return RequestBoundSetterPermission.bind(decided) { delegate.acknowledgeControlRelaxation(command) }
        }
    }
}

/** As [asRoles] for a person holding the one role [role]. */
internal fun PolicyWriter.asRole(
    tenants: TenantContext,
    role: String,
): PolicyWriter = asRoles(tenants, setOf(role))
