package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.TenancyRefusal
import com.dynokore.gst.tenancy.adapters.http.WorkspacesController
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.tenant.WorkspaceStore
import com.dynokore.gst.tenancy.application.workspace.AccessPointCredential
import com.dynokore.gst.tenancy.application.workspace.ArchiveWorkspace
import com.dynokore.gst.tenancy.application.workspace.CreateWorkspace
import com.dynokore.gst.tenancy.application.workspace.CredentialBinder
import com.dynokore.gst.tenancy.application.workspace.CredentialLoad
import com.dynokore.gst.tenancy.application.workspace.LoadCredential
import com.dynokore.gst.tenancy.application.workspace.SampleCopies
import com.dynokore.gst.tenancy.application.workspace.ServiceEnvironment
import com.dynokore.gst.tenancy.application.workspace.TenantAuthority
import com.dynokore.gst.tenancy.contract.model.CreateTenantRequest
import com.dynokore.gst.tenancy.contract.model.CreateWorkspaceRequest
import com.dynokore.gst.tenancy.contract.model.LoadCredentialRequest
import com.dynokore.gst.tenancy.domain.CredentialRefusal
import com.dynokore.gst.tenancy.domain.Environment
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.TenancyErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Credential isolation (INV-REQ-1126, INV-REQ-502): the credential's own environment tag decides, never its name. */
class EnvironmentSeparationTest {
    /** The binder, and the decision of `ap-credential.manage`: records the key asked and answers [allow]. */
    private class Bound :
        CredentialBinder,
        TenantAuthority {
        @Volatile
        var allow = true
        val asked = CopyOnWriteArrayList<String>()

        override fun holds(
            person: String,
            permission: String,
        ): Boolean {
            asked += permission
            return allow
        }

        val loads = CopyOnWriteArrayList<Pair<TenantId, AccessPointCredential>>()

        override fun bind(
            target: TenantId,
            credential: AccessPointCredential,
        ) {
            loads += target to credential
        }

        fun boundTo(target: TenantId) = loads.filter { it.first == target }.map { it.second }
    }

    private class Setup(
        val env: ProfileEnv,
        val production: TenantId,
        val workspace: TenantId,
        val binder: Bound,
        private val loader: LoadCredential,
        private val api: WorkspacesController,
    ) {
        /** The operation `loadCredential`: the names of the accepted credentials with the environment answered. */
        fun loadThroughApi(
            target: TenantId,
            credential: AccessPointCredential,
        ): String =
            env.inTenant(target) {
                val tag = credential.environmentTag?.let { LoadCredentialRequest.EnvironmentTag.valueOf(it) }
                runCatching { api.loadCredential(UUID.randomUUID(), LoadCredentialRequest(credential.name, tag)) }
                    .fold(
                        { "accepted into ${it.body!!.environment}" },
                        {
                            when (it) {
                                is ApiRefusal -> "refused ${it.code.code} 403"
                                else -> "refused ${(it as TenancyRefusal).code.code} ${it.code.httpStatus}"
                            }
                        },
                    )
            }

        fun load(
            target: TenantId,
            credential: AccessPointCredential,
        ): CredentialLoad = env.inTenant(target) { loader.handle(credential) }
    }

    private fun run(
        environment: String = "PRODUCTION",
        block: (Setup) -> Unit,
    ) {
        BoundaryFixture.runner(environment).run { context ->
            context.startupFailure?.let { throw it }
            block(setup(context))
        }
    }

    private fun setup(context: AssertableWebApplicationContext): Setup {
        val env = ProfileEnv(context)
        val contract = "C-${UUID.randomUUID()}"
        val ref = TenantRefs.refOfContract(contract)
        env.tenantsApi.createTenant(UUID.randomUUID(), CreateTenantRequest(contract, "Tenant $contract", "PLAN-A", ref))
        val production = TenantRefs.idOf(ref)
        val workspace =
            env.inTenant(production) {
                val body =
                    context
                        .getBean(WorkspacesController::class.java)
                        .createWorkspace(UUID.randomUUID(), CreateWorkspaceRequest(ref, "pre-prod"))
                        .body!!
                TenantRefs.idOf(body.workspaceRef)
            }
        val binder = Bound()
        val loader =
            LoadCredential(
                context.getBean(TransactionRunner::class.java),
                context.getBean(WorkspaceStore::class.java),
                context.getBean(ServiceEnvironment::class.java),
                binder,
            )
        val api =
            WorkspacesController(
                context.getBean(CreateWorkspace::class.java),
                context.getBean(ArchiveWorkspace::class.java),
                loader,
                context.getBean(SampleCopies::class.java),
                context.getBean(CallerSource::class.java),
                binder,
            )
        return Setup(env, production, workspace, binder, loader, api)
    }

    private fun credential(
        name: String,
        tag: String?,
    ) = AccessPointCredential(name, tag)

    /**
     * The four combinations of (service environment, tenant row environment) through the operation `loadCredential`:
     * the target is SANDBOX when either says so; a name that contains prod or sandbox never decides (INV-Q-11905 A).
     */
    private fun combination(
        service: String,
        sandboxRow: Boolean,
        accepted: Environment,
    ) {
        val refused = "refused TNC-005 422"
        val into = "accepted into $accepted"
        run(service) { s ->
            val target = if (sandboxRow) s.workspace else s.production
            val production = credential("sandbox-ap", "PRODUCTION")
            val sandbox = credential("prod-ap", "SANDBOX")
            assertThat(s.loadThroughApi(target, production))
                .isEqualTo(if (accepted == Environment.PRODUCTION) into else refused)
            assertThat(s.loadThroughApi(target, sandbox))
                .isEqualTo(if (accepted == Environment.SANDBOX) into else refused)
            assertThat(s.loadThroughApi(target, credential("live-ap", null))).isEqualTo(refused)
        }
    }

    @Test
    fun `loadCredential service PRODUCTION and production row accepts the production tag only`() =
        combination("PRODUCTION", sandboxRow = false, accepted = Environment.PRODUCTION)

    @Test
    fun `loadCredential service PRODUCTION and sandbox row accepts the sandbox tag only`() =
        combination("PRODUCTION", sandboxRow = true, accepted = Environment.SANDBOX)

    @Test
    fun `loadCredential service SANDBOX and production row accepts the sandbox tag only`() =
        combination("SANDBOX", sandboxRow = false, accepted = Environment.SANDBOX)

    @Test
    fun `loadCredential service SANDBOX and sandbox row accepts the sandbox tag only`() =
        combination("SANDBOX", sandboxRow = true, accepted = Environment.SANDBOX)

    private val refusedTagDiffers = CredentialLoad.Refused(CredentialRefusal.TAG_DIFFERS)

    @Test
    fun `INV-AC-533 a production AP credential loaded into a sandbox workspace is refused on its tag`() {
        run { s ->
            val outcome = s.load(s.workspace, credential("ap-credential", "PRODUCTION"))
            assertThat(outcome).isEqualTo(refusedTagDiffers)
            assertThat(s.binder.boundTo(s.workspace)).isEmpty()
        }
    }

    @Test
    fun `INV-AC-2120 a sandbox-tagged credential named like a production one is refused by production`() {
        run { s ->
            val outcome = s.load(s.production, credential("prod-ap", "SANDBOX"))
            assertThat(outcome).isEqualTo(refusedTagDiffers)
            assertThat(s.binder.boundTo(s.production)).isEmpty()
        }
    }

    @Test
    fun `INV-AC-9575 a credential is accepted only when its tag equals the target environment, whatever its name`() {
        run { s ->
            val credentials =
                listOf(
                    credential("prod-ap", "SANDBOX"),
                    credential("sandbox-ap", "PRODUCTION"),
                    credential("live-ap", "PRODUCTION"),
                    credential("untagged-ap", null),
                )
            val accepted =
                credentials.flatMap { c ->
                    listOf("workspace" to s.workspace, "production" to s.production)
                        .filter { (_, target) -> s.load(target, c) is CredentialLoad.Accepted }
                        .map { (label, _) -> "${c.environmentTag} ${c.name} into $label" }
                }
            assertThat(accepted).containsExactlyInAnyOrder(
                "SANDBOX prod-ap into workspace",
                "PRODUCTION sandbox-ap into production",
                "PRODUCTION live-ap into production",
            )
            val untagged = CredentialLoad.Refused(CredentialRefusal.UNTAGGED)
            assertThat(s.load(s.workspace, credentials.last())).isEqualTo(untagged)
            assertThat(s.load(s.production, credentials.last())).isEqualTo(untagged)
            assertThat(
                s.binder
                    .boundTo(s.workspace)
                    .map { it.environmentTag }
                    .distinct(),
            ).containsExactly("SANDBOX")
        }
    }

    @Test
    fun `loadCredential is decided by the key ap-credential-manage and a caller without it binds nothing`() {
        run { s ->
            s.binder.allow = false
            assertThat(s.loadThroughApi(s.workspace, credential("ap", "SANDBOX")))
                .isEqualTo("refused ${ClosedErrorCode.ACCESS_DENIED.code} 403")
            assertThat(s.binder.loads).isEmpty()
            s.binder.allow = true
            assertThat(s.loadThroughApi(s.workspace, credential("ap", "SANDBOX"))).isEqualTo("accepted into SANDBOX")
            assertThat(s.binder.asked).containsOnly("ap-credential.manage")
        }
    }

    @Test
    fun `a tag that only looks like an environment is refused in both targets`() {
        run { s ->
            listOf("production", "Sandbox", " SANDBOX", "SANDBOX ", "PRODUCTION,SANDBOX", "*", "").forEach { tag ->
                assertThat(
                    s.load(s.workspace, credential("c", tag)),
                ).describedAs("workspace '$tag'").isInstanceOf(CredentialLoad.Refused::class.java)
                assertThat(
                    s.load(s.production, credential("c", tag)),
                ).describedAs("production '$tag'").isInstanceOf(CredentialLoad.Refused::class.java)
            }
            assertThat(s.binder.loads).isEmpty()
        }
    }

    @Test
    fun `INV-AC-9578 the production credential is refused in a SaaS workspace and a private sandbox namespace`() {
        val production = credential("ap", "PRODUCTION")
        var saas: CredentialLoad? = null
        var saasBound: Int? = null
        run("PRODUCTION") { s ->
            saas = s.load(s.workspace, production)
            saasBound = s.binder.boundTo(s.workspace).count { it.environmentTag == "PRODUCTION" }
        }
        // the private form: a namespace of the same release train, started as SANDBOX, holds an ordinary tenant
        var private: CredentialLoad? = null
        var privateBound: Int? = null
        run("SANDBOX") { s ->
            private = s.load(s.production, production)
            privateBound = s.binder.boundTo(s.production).count { it.environmentTag == "PRODUCTION" }
        }
        assertThat(saas).isEqualTo(refusedTagDiffers)
        assertThat(private).isEqualTo(saas)
        assertThat(saasBound).isZero()
        assertThat(privateBound).isEqualTo(saasBound)
    }

    @Test
    fun `INV-AC-9579 a start without an explicit environment fails and an explicit one starts with that environment`() {
        BoundaryFixture.runner(null).run { context ->
            assertThat(context.startupFailure).isNotNull()
            assertThat(generateSequence(context.startupFailure) { it.cause }.map { it.message }.joinToString(" "))
                .contains("inv.tenancy.environment")
        }
        listOf("SANDBOX", "PRODUCTION").forEach { configured ->
            BoundaryFixture.runner(configured).run { context ->
                context.startupFailure?.let { throw it }
                assertThat(
                    context.getBean(ServiceEnvironment::class.java).value,
                ).isEqualTo(Environment.valueOf(configured))
            }
        }
        listOf("", "production", "STAGING", "PRODUCTION,SANDBOX").forEach { configured ->
            BoundaryFixture.runner(configured).run { context ->
                assertThat(context.startupFailure).describedAs(configured).isNotNull()
            }
        }
    }
}
