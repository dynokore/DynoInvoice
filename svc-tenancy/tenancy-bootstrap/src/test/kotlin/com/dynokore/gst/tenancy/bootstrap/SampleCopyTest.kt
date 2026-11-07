package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.TenancyRefusal
import com.dynokore.gst.tenancy.adapters.http.WorkspacesController
import com.dynokore.gst.tenancy.adapters.workspace.ReplicaTenantAuthority
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.structure.StructureChangePublisher
import com.dynokore.gst.tenancy.application.tenant.WorkspaceStore
import com.dynokore.gst.tenancy.application.workspace.ArchiveWorkspace
import com.dynokore.gst.tenancy.application.workspace.CreateWorkspace
import com.dynokore.gst.tenancy.application.workspace.LoadCredential
import com.dynokore.gst.tenancy.application.workspace.ProductionSamplePolicy
import com.dynokore.gst.tenancy.application.workspace.SampleApprovals
import com.dynokore.gst.tenancy.application.workspace.SampleCopies
import com.dynokore.gst.tenancy.application.workspace.SampleCopyPort
import com.dynokore.gst.tenancy.application.workspace.SampleRowSource
import com.dynokore.gst.tenancy.application.workspace.SampleTransfer
import com.dynokore.gst.tenancy.application.workspace.TenantAuthority
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.CreateWorkspaceRequest
import com.dynokore.gst.tenancy.contract.model.RecordSampleApprovalRequest
import com.dynokore.gst.tenancy.contract.model.RequestSampleCopyRequest
import com.dynokore.gst.tenancy.contract.model.SampleCopyResponse
import com.dynokore.gst.tenancy.domain.SampleRow
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.configresolver.AcknowledgeRelaxationCommand
import com.dynokore.platform.configresolver.Actor
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.TenancyErrorCode
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import com.dynokore.platform.configresolver.ScopeLevel as PolicyLevel

/**
 * The anonymised production sample copy (INV-REQ-1130, INV-REQ-1305; VP-44) through the operations
 * `recordSampleApproval` and `requestSampleCopy` of the tenancy contract: the setting is switched on through the real
 * config-resolver writer with the acknowledgement the catalogue row requires, both operations are decided by the scoped
 * permission keys through `AuthorityReplica.decideTenant`, and the rows reach the copy port anonymised.
 */
class SampleCopyTest {
    private class RecordingPort : SampleCopyPort {
        val handed = CopyOnWriteArrayList<SampleRow>()

        override fun copy(
            workspace: TenantId,
            sampleRef: String,
            rows: List<SampleRow>,
        ) {
            handed += rows
        }
    }

    /** Tenant-scope role grants held in a real replica; the caller is whoever [caller] names. */
    private class People : CallerSource {
        @Volatile
        var caller: String = "USR-NONE"
        val replica = AuthorityReplica(Clock.systemUTC(), TestOperationRoles.map).also { it.onSnapshotComplete(0) }
        private val version = AtomicLong()

        override fun actor(): String = caller

        /** A grant of the first role the generated matrix lists for [permission]. */
        fun grant(
            tenant: TenantId,
            person: String,
            permission: String,
            level: GrantScope = GrantScope.TENANT,
        ) = grantRole(tenant, person, TestOperationRoles.holders(permission).sorted().first(), level)

        fun grantRole(
            tenant: TenantId,
            person: String,
            role: String,
            level: GrantScope = GrantScope.TENANT,
        ) {
            val ref = tenant.value.toString()
            replica.accept(
                AuthorityChanged(
                    tenant,
                    person,
                    version.incrementAndGet(),
                    AuthorityRecord.RoleGrantRecord(
                        "GR-${version.get()}",
                        RoleGrantState.ACTIVE,
                        RoleGrant(role, level, ref, Clock.systemUTC().instant().minusSeconds(60)),
                    ),
                ),
            )
            replica.observeHead(version.get())
        }
    }

    private class Setup(
        val env: ProfileEnv,
        val people: People,
        val api: WorkspacesController,
        val port: RecordingPort,
        val approvals: SampleApprovals,
        val policy: ProductionSamplePolicy,
    ) {
        fun newTenant(): Pair<TenantId, String> {
            val contract = "C-${UUID.randomUUID()}"
            val ref = TenantRefs.refOfContract(contract)
            env.tenantsApi.createTenant(
                UUID.randomUUID(),
                CreateTenantRequest(contract, "Tenant $contract", "PLAN-A", ref),
            )
            val tenant = TenantRefs.idOf(ref)
            val workspaceRef =
                env.inTenant(tenant) {
                    api.createWorkspace(UUID.randomUUID(), CreateWorkspaceRequest(ref, "pre-prod")).body!!.workspaceRef
                }
            return tenant to workspaceRef
        }

        fun setting(tenant: TenantId) = env.inTenant(tenant) { env.inTx { policy.isOn() } }

        /** VP-44 ON through the real writer: tenant.admin acknowledges, then security.officer sets it. */
        fun switchOn(tenant: TenantId) {
            val key = VariationPointKey("VP-44")
            val scope = PolicyScope.tenant(tenant)
            val on = listOf(SelectedOption("ON", null))
            env.inTenant(tenant) {
                env.inTx {
                    val relaxation =
                        env.writer
                            .acknowledgeControlRelaxation(
                                AcknowledgeRelaxationCommand(
                                    key,
                                    scope,
                                    PolicyLevel.TENANT,
                                    on,
                                    TODAY,
                                    Actor("user:ta", setOf("tenant.admin")),
                                    "tenant.admin",
                                    CorrelationId("corr-${UUID.randomUUID()}"),
                                    CausationId("cause-${UUID.randomUUID()}"),
                                    null,
                                    "accepted risk",
                                ),
                            ).relaxation
                    env.writer.setPolicy(
                        SetPolicyCommand(
                            key,
                            scope,
                            PolicyLevel.TENANT,
                            on,
                            TODAY,
                            "dry runs",
                            Actor("user:officer", setOf("security.officer")),
                            CorrelationId("corr-${UUID.randomUUID()}"),
                            CausationId("cause-${UUID.randomUUID()}"),
                            relaxation = relaxation,
                        ),
                    )
                }
            }
        }

        fun <T> asCaller(
            tenant: TenantId,
            person: String,
            block: () -> T,
        ): T {
            people.caller = person
            return env.inTenant(tenant, block)
        }

        fun approve(
            tenant: TenantId,
            person: String,
            sample: String = SAMPLE,
        ) = asCaller(
            tenant,
            person,
        ) { api.recordSampleApproval(UUID.randomUUID(), RecordSampleApprovalRequest(sample)) }

        fun request(
            tenant: TenantId,
            workspaceRef: String,
            person: String,
            sample: String = SAMPLE,
        ) = asCaller(tenant, person) {
            api.requestSampleCopy(workspaceRef, UUID.randomUUID(), RequestSampleCopyRequest(sample))
        }
    }

    private fun run(block: (Setup) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            val people = People()
            val port = RecordingPort()
            val tx = context.getBean(TransactionRunner::class.java)
            val store = context.getBean(WorkspaceStore::class.java)
            val policy = context.getBean(ProductionSamplePolicy::class.java)
            val approvals = context.getBean(SampleApprovals::class.java)
            val copies =
                SampleCopies(
                    tx,
                    store,
                    policy,
                    approvals,
                    ReplicaTenantAuthority(people.replica, context.getBean(TenantContext::class.java)),
                    SampleTransfer(SampleRowSource { sample }, port),
                )
            val api =
                WorkspacesController(
                    CreateWorkspace(
                        context.getBean(TenantContext::class.java),
                        tx,
                        store,
                        context.getBean(StructureChangePublisher::class.java),
                    ),
                    ArchiveWorkspace(tx, store, context.getBean(StructureChangePublisher::class.java)),
                    context.getBean(LoadCredential::class.java),
                    copies,
                    people,
                    TenantAuthority { _, _ -> true },
                )
            block(Setup(env, people, api, port, approvals, policy))
        }
    }

    private fun tenancyCode(block: () -> Unit): TenancyErrorCode? =
        runCatching(block).exceptionOrNull().let { (it as? TenancyRefusal)?.code }

    private fun Setup.forbidden(block: () -> Unit) =
        assertThat((runCatching(block).exceptionOrNull() as? ApiRefusal)?.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)

    @Test
    fun `INV-AC-2292 with the setting never changed no production sample is copied`() {
        run { s ->
            val (tenant, workspace) = s.newTenant()
            s.people.grant(tenant, "USR-REQ", "sample-copy.request")
            assertThat(
                tenancyCode { s.request(tenant, workspace, "USR-REQ") },
            ).isEqualTo(TenancyErrorCode.SAMPLE_COPY_OFF)
            assertThat(s.port.handed).isEmpty()
        }
    }

    @Test
    fun `INV-AC-2386 a copy with security officer approval is refused while samples are off and nothing is copied`() {
        run { s ->
            val (tenant, workspace) = s.newTenant()
            s.people.grant(tenant, "USR-OFF", "sample-copy.approve")
            s.people.grant(tenant, "USR-REQ", "sample-copy.request")
            assertThat(tenancyCode { s.approve(tenant, "USR-OFF") }).isEqualTo(TenancyErrorCode.SAMPLE_COPY_OFF)
            assertThat(
                tenancyCode { s.request(tenant, workspace, "USR-REQ") },
            ).isEqualTo(TenancyErrorCode.SAMPLE_COPY_OFF)
            assertThat(s.port.handed).isEmpty()
            assertThat(s.env.inTenant(tenant) { s.env.inTx { s.approvals.open(SAMPLE) } }).isNull()
        }
    }

    @Test
    fun `INV-AC-2122 wave 2 part a sample is refused by default and arrives anonymised once approved`() {
        run { s ->
            val (tenant, workspace) = s.newTenant()
            s.people.grant(tenant, "USR-OFF", "sample-copy.approve")
            s.people.grant(tenant, "USR-REQ", "sample-copy.request")
            assertThat(tenancyCode { s.request(tenant, workspace, "USR-REQ") })
                .isEqualTo(TenancyErrorCode.SAMPLE_COPY_OFF)
            assertThat(s.port.handed).isEmpty()
            s.switchOn(tenant)
            s.approve(tenant, "USR-OFF")
            assertThat(s.request(tenant, workspace, "USR-REQ").statusCode.value()).isEqualTo(202)
            assertThat(s.port.handed.map { it.amount })
                .containsExactly(BigDecimal("1200.50"), BigDecimal("80.00"), BigDecimal("0.07"))
            assertThat(s.port.handed.map { it.counterpartyName }).doesNotContain("Acme Pte Ltd", "Beta Trading")
        }
    }

    @Test
    fun `INV-AC-9576 an approval recorded by a principal without the approve permission does not authorise a copy`() {
        run { s ->
            val (tenant, workspace) = s.newTenant()
            s.switchOn(tenant)
            // tenant.admin holds sample-copy.request and not sample-copy.approve
            s.people.grant(tenant, "USR-TA", "sample-copy.request")
            s.forbidden { s.approve(tenant, "USR-TA") }
            assertThat(tenancyCode { s.request(tenant, workspace, "USR-TA") })
                .isEqualTo(TenancyErrorCode.SAMPLE_COPY_NOT_APPROVED)
            assertThat(s.port.handed).isEmpty()
            assertThat(s.env.inTenant(tenant) { s.env.inTx { s.approvals.open(SAMPLE) } }).isNull()
        }
    }

    @Test
    fun `INV-AC-9577 the setting is per tenant a tenant that never wrote it reads off while another is on`() {
        run { s ->
            val (a, _) = s.newTenant()
            val (b, workspaceB) = s.newTenant()
            s.switchOn(a)
            s.people.grant(b, "USR-REQ", "sample-copy.request")
            assertThat(s.setting(a)).isTrue()
            assertThat(s.setting(b)).isFalse()
            assertThat(tenancyCode { s.request(b, workspaceB, "USR-REQ") }).isEqualTo(TenancyErrorCode.SAMPLE_COPY_OFF)
            assertThat(s.port.handed).isEmpty()
        }
    }

    @Test
    fun `setting VP-44 on without the acknowledgement of the catalogue row is refused`() {
        run { s ->
            val (tenant, _) = s.newTenant()
            val refused =
                runCatching {
                    s.env.inTenant(tenant) {
                        s.env.inTx {
                            s.env.writer.setPolicy(
                                SetPolicyCommand(
                                    VariationPointKey("VP-44"),
                                    PolicyScope.tenant(tenant),
                                    PolicyLevel.TENANT,
                                    listOf(SelectedOption("ON", null)),
                                    TODAY,
                                    "dry runs",
                                    Actor("user:officer", setOf("security.officer")),
                                    CorrelationId("corr-${UUID.randomUUID()}"),
                                    CausationId("cause-${UUID.randomUUID()}"),
                                ),
                            )
                        }
                    }
                }.exceptionOrNull()
            assertThat((refused as PolicyRefused).refusal).isEqualTo(PolicyRefusal.RELAXATION_REQUIRED)
            assertThat(s.setting(tenant)).isFalse()
        }
    }

    @Test
    fun `INV-REQ-1130 with the setting on a copy needs one approval each and arrives anonymised with amounts kept`() {
        run { s ->
            val (tenant, workspace) = s.newTenant()
            s.switchOn(tenant)
            s.people.grant(tenant, "USR-OFF", "sample-copy.approve")
            s.people.grant(tenant, "USR-REQ", "sample-copy.request")
            assertThat(tenancyCode { s.request(tenant, workspace, "USR-REQ") })
                .isEqualTo(TenancyErrorCode.SAMPLE_COPY_NOT_APPROVED)
            val first = s.approve(tenant, "USR-OFF")
            // the approval record stores the acting user ID
            assertThat(first.statusCode.value()).isEqualTo(201)
            assertThat(first.body!!.approvedBy).isEqualTo("USR-OFF")
            // a repeat records no second approval
            assertThat(s.approve(tenant, "USR-OFF").statusCode.value()).isEqualTo(200)
            val copied = s.request(tenant, workspace, "USR-REQ")
            assertThat(copied.statusCode.value()).isEqualTo(202)
            assertThat(copied.body!!.status).isEqualTo(SampleCopyResponse.Status.REQUESTED)
            assertThat(s.port.handed.map { it.amount })
                .containsExactly(BigDecimal("1200.50"), BigDecimal("80.00"), BigDecimal("0.07"))
            assertThat(s.port.handed.map { it.counterpartyName }).doesNotContain("Acme Pte Ltd", "Beta Trading")
            assertThat(s.port.handed.map { it.counterpartyId }).noneMatch { it.startsWith("UEN-") }
            assertThat(
                s.port.handed
                    .map { it.counterpartyId }
                    .distinct(),
            ).hasSize(2)
            // the approval is spent: the next copy needs its own
            assertThat(tenancyCode { s.request(tenant, workspace, "USR-REQ") })
                .isEqualTo(TenancyErrorCode.SAMPLE_COPY_NOT_APPROVED)
            assertThat(s.port.handed).hasSize(3)
        }
    }

    @Test
    fun `a role name or an entity-scope grant of the key does not decide`() {
        run { s ->
            val (tenant, workspace) = s.newTenant()
            s.switchOn(tenant)
            val request = "sample-copy.request"
            val approve = "sample-copy.approve"
            val unlisted = "tax.approver"
            assertThat(
                TestOperationRoles.holders(request) + TestOperationRoles.holders(approve),
            ).doesNotContain(unlisted)
            // a role the matrix does not list for the key
            s.people.grantRole(tenant, "USR-ROLE", unlisted)
            // a holder role at entity scope: the tenant decision reads tenant-scope grants only
            s.people.grant(tenant, "USR-ENT", approve, GrantScope.ENTITY)
            // a role whose text equals the key text
            s.people.grantRole(tenant, "USR-LOOK", approve)
            listOf("USR-ROLE", "USR-ENT", "USR-LOOK", "USR-UNKNOWN").forEach { person ->
                s.forbidden { s.approve(tenant, person) }
                s.forbidden { s.request(tenant, workspace, person) }
            }
            assertThat(s.env.inTenant(tenant) { s.env.inTx { s.approvals.open(SAMPLE) } }).isNull()
            assertThat(s.port.handed).isEmpty()
        }
    }

    @Test
    fun `with no replica bound the permission is refused`() {
        run { s ->
            val (tenant, _) = s.newTenant()
            val tenants = s.env.tenants
            val authority: TenantAuthority = ReplicaTenantAuthority(null, tenants)
            assertThat(s.env.inTenant(tenant) { authority.holds("USR-OFF", "sample-copy.approve") }).isFalse()
        }
    }

    @Test
    fun `a copy into a ref that is no workspace of the tenant is refused with 404`() {
        run { s ->
            val (tenant, _) = s.newTenant()
            s.switchOn(tenant)
            s.people.grant(tenant, "USR-OFF", "sample-copy.approve")
            s.people.grant(tenant, "USR-REQ", "sample-copy.request")
            s.approve(tenant, "USR-OFF")
            val refused = runCatching { s.request(tenant, tenant.value.toString(), "USR-REQ") }.exceptionOrNull()
            assertThat(refused).isNotNull()
            assertThat(s.port.handed).isEmpty()
        }
    }

    private companion object {
        const val SAMPLE = "SMP-1"
        val sample =
            listOf(
                SampleRow("Acme Pte Ltd", "UEN-201900001A", BigDecimal("1200.50")),
                SampleRow("Beta Trading", "UEN-201900002B", BigDecimal("80.00")),
                SampleRow("Acme Pte Ltd", "UEN-201900001A", BigDecimal("0.07")),
            )
    }
}
