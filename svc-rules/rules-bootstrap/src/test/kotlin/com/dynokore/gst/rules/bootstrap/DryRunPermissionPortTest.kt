package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.rules.adapters.web.MappingRefusal
import com.dynokore.gst.rules.application.DryRunPermissionPort
import com.dynokore.gst.rules.served.MovableClock
import com.dynokore.gst.rules.served.PolicyFixtures
import com.dynokore.gst.rules.served.ReplicaFallbackDouble
import com.dynokore.gst.rules.served.subjectOf
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private const val DRY_RUN_KEY = "pack.dry-run"
private const val LOOK_ALIKE_KEY = "regulatory-pack.approve"

/**
 * INV-AC-22855 (INV-REQ-957): the `DryRunPermissionPort` bean answers from the scoped key `pack.dry-run` decided by the
 * real `AuthorityReplica`, with the roles read from the generated `OperationRoles` map (no role literal here). The
 * deployment form is fixed per context, so the cases live in one base class run by a SAAS and a PRIVATE subclass.
 */
@Suppress("AbstractClassCanBeConcreteClass") // JUnit must not run the base itself: only the two form subclasses
abstract class DryRunPermissionPortCases(
    private val form: DeploymentForm,
    private val replica: AuthorityReplica,
    private val clock: MovableClock,
    private val tenants: TenantContext,
    private val port: DryRunPermissionPort,
) {
    private val tenant = TenantId(UUID.randomUUID())

    @BeforeEach
    fun freshReplica() {
        ReplicaFallbackDouble.reset()
        replica.tracker.admitIncarnation(epoch.get())
        replica.onSnapshotComplete(0)
        replica.tracker.onApplied(versions.get())
    }

    private fun grantRole(
        user: String,
        role: String,
    ) {
        replica.accept(
            AuthorityChanged(
                tenant,
                user,
                versions.incrementAndGet(),
                AuthorityRecord.RoleGrantRecord(
                    "g-$user-$role",
                    RoleGrantState.ACTIVE,
                    RoleGrant(
                        role,
                        GrantScope.TENANT,
                        tenant.toString(),
                        clock.instant().minus(Duration.ofHours(1)),
                        null,
                    ),
                ),
                epoch.get(),
            ),
        )
    }

    private fun may(
        user: String,
        thePort: DryRunPermissionPort = port,
    ): Boolean = tenants.bind(tenant, user) { thePort.mayDryRun(user) }

    private fun assertRefusal(block: () -> Unit) {
        val refusal = shouldThrow<MappingRefusal> { block() }
        refusal.status shouldBe 503
        refusal.code shouldBe "SRT-013"
    }

    @Test
    fun `INV-AC-22855 each role of the pack dry-run map holds the key and the map has exactly three roles`() {
        val roles = OperationRoles.rolesFor(form, DRY_RUN_KEY).keys
        roles.size shouldBe 3
        for (role in roles) {
            val user = "u-" + UUID.randomUUID()
            may(user) shouldBe false
            grantRole(user, role)
            may(user) shouldBe true
        }
    }

    @Test
    fun `INV-AC-22855 a role absent from the pack dry-run map and present for regulatory-pack approve gets false`() {
        val dryRunRoles = OperationRoles.rolesFor(form, DRY_RUN_KEY).keys
        val outside = OperationRoles.rolesFor(form, LOOK_ALIKE_KEY).keys - dryRunRoles
        check(outside.isNotEmpty()) { "$form: no role holds $LOOK_ALIKE_KEY and not $DRY_RUN_KEY" }
        for (role in outside) {
            val user = "u-" + UUID.randomUUID()
            grantRole(user, role)
            may(user) shouldBe false
        }
    }

    @Test
    fun `INV-AC-22855 a user with no grant gets false`() {
        may("u-" + UUID.randomUUID()) shouldBe false
    }

    @Test
    fun `INV-AC-22855 a decision not answered from the local replica is the 503 SRT-013 refusal`() {
        val user = "u-" + UUID.randomUUID()
        grantRole(user, OperationRoles.rolesFor(form, DRY_RUN_KEY).keys.first())
        replica.tracker.onDisconnected()
        clock.advance(Duration.ofSeconds(30))
        assertRefusal { may(user) }
    }

    @Test
    fun `INV-AC-22855 a context with no replica is the 503 SRT-013 refusal`() {
        val none = StaticListableBeanFactory().getBeanProvider(AuthorityReplica::class.java)
        assertRefusal { may("u-" + UUID.randomUUID(), RulesConfiguration().dryRunPermissionPort(none, tenants)) }
    }

    companion object {
        val versions = AtomicLong(0)
        val epoch = AtomicLong(1)

        fun register(
            registry: DynamicPropertyRegistry,
            form: String,
        ) {
            PostgresRules.register(registry)
            registry.add("service-runtime.modules.schema") { "rules" }
            registry.add("inv.tenancy.reference-tables") { "rules.pack_registry,rules.pack_entry_source" }
            registry.add("service-runtime.workload-identity.callers[0].service") { "console-bff" }
            registry.add("service-runtime.workload-identity.callers[0].subject") { subjectOf("console-bff") }
            registry.add("gst.form") { form }
        }
    }
}

@SpringBootTest(classes = [PolicyFixtures::class, RulesApplication::class])
class DryRunPermissionPortSaasTest
    @org.springframework.beans.factory.annotation.Autowired
    constructor(
        replica: AuthorityReplica,
        clock: MovableClock,
        tenants: TenantContext,
        port: DryRunPermissionPort,
    ) : DryRunPermissionPortCases(DeploymentForm.SAAS, replica, clock, tenants, port) {
        companion object {
            @JvmStatic
            @DynamicPropertySource
            fun properties(registry: DynamicPropertyRegistry) = register(registry, "saas")
        }
    }

@SpringBootTest(classes = [PolicyFixtures::class, RulesApplication::class])
class DryRunPermissionPortPrivateTest
    @org.springframework.beans.factory.annotation.Autowired
    constructor(
        replica: AuthorityReplica,
        clock: MovableClock,
        tenants: TenantContext,
        port: DryRunPermissionPort,
    ) : DryRunPermissionPortCases(DeploymentForm.PRIVATE, replica, clock, tenants, port) {
        companion object {
            @JvmStatic
            @DynamicPropertySource
            fun properties(registry: DynamicPropertyRegistry) = register(registry, "private")
        }
    }
