package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.composition.TenancyKernelWiring
import com.dynokore.platform.serviceruntime.datasource.withBindingKey
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

/**
 * INV-T-7520 (INV-REQ-866, INV-AC-1912): the entity set the transaction binds comes from the authority replica
 * (INV-Q-8972 A: ENTITY grants by their entity, GROUP and TENANT grants by the structure replica), and is empty when
 * the replica gives no entity.
 */
class ReplicaBoundEntitiesTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val e1 = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val e2 = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val now = Instant.parse("2026-10-20T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val structure = StructureReplica(clock)
    private val replica = AuthorityReplica(clock, OperationRoleMap { emptyMap() }, structure = structure)
    private val source = ReplicaBoundEntities(replica)

    private fun grant(
        v: Long,
        ref: String,
        scope: GrantScope,
        principal: String = "p1",
    ) = replica.accept(
        AuthorityChanged(
            tenant,
            principal,
            v,
            AuthorityRecord.RoleGrantRecord(
                "g$v",
                RoleGrantState.ACTIVE,
                RoleGrant("reader", scope, ref, now.minusSeconds(60)),
            ),
        ),
    )

    private fun node(
        ref: String,
        kind: StructureNodeKind,
        parent: String? = null,
    ) = structure.replica.apply(
        ReplicaChange.Upsert(
            StructureKey(tenant, ref),
            StructureNode(kind, parent, false, ref, null, emptyList(), 1),
            1,
        ),
    )

    @Test
    fun `grants on E1 and E2 give the set E1 and E2 as a set`() {
        node("$e2", StructureNodeKind.ENTITY, parent = "grp")
        node("grp", StructureNodeKind.GROUP)
        grant(1, "$e1", GrantScope.ENTITY)
        grant(2, "grp", GrantScope.GROUP)
        source.entitiesOf(tenant, "p1") shouldBe setOf(e1, e2)
    }

    @Test
    fun `a TENANT grant gives the entities of the tenant in the structure replica`() {
        node("$e1", StructureNodeKind.ENTITY)
        grant(1, tenant.toString(), GrantScope.TENANT)
        source.entitiesOf(tenant, "p1") shouldBe setOf(e1)
    }

    @Test
    fun `a principal with no grant gives the empty set`() {
        grant(1, "$e1", GrantScope.ENTITY)
        source.entitiesOf(tenant, "p2") shouldBe emptySet()
    }

    @Test
    fun `a replica with no answer gives the empty set`() {
        source.entitiesOf(tenant, "p1") shouldBe emptySet()
    }

    @Test
    fun `a grant of scope level UNKNOWN gives the empty set`() {
        grant(1, "$e1", GrantScope.UNKNOWN)
        source.entitiesOf(tenant, "p1") shouldBe emptySet()
    }

    @Test
    fun `a look-alike principal id, a prefix of the real one, gives the empty set`() {
        grant(1, "$e1", GrantScope.ENTITY, principal = "p1")
        source.entitiesOf(tenant, "p") shouldBe emptySet()
        source.entitiesOf(tenant, "p1x") shouldBe emptySet()
        source.entitiesOf(tenant, "P1") shouldBe emptySet()
    }

    @Test
    fun `the same principal under another tenant gives the empty set`() {
        grant(1, "$e1", GrantScope.ENTITY)
        source.entitiesOf(TenantId(UUID.randomUUID()), "p1") shouldBe emptySet()
    }

    @Test
    fun `an entity reference that is not a canonical UUID is left out`() {
        grant(1, "$e1", GrantScope.ENTITY)
        grant(2, "not-a-uuid", GrantScope.ENTITY)
        grant(3, "ABCDEFAB-ABCD-ABCD-ABCD-ABCDEFABCDEF", GrantScope.ENTITY)
        source.entitiesOf(tenant, "p1") shouldBe setOf(e1)
    }

    /** The context a service gets from [TenancyKernelWiring]: the runner binds what the replica holds. */
    private fun boundSignature(withReplica: Boolean): String {
        val set = mutableMapOf<Int, String>()
        val statement = mockk<PreparedStatement>(relaxed = true)
        every { statement.setString(any(), any()) } answers { set[firstArg()] = secondArg() }
        val connection = mockk<Connection>(relaxed = true)
        every { connection.prepareStatement(any<String>()) } returns statement
        val pool = mockk<DataSource>()
        every { pool.connection } returns connection
        grant(1, "$e2", GrantScope.ENTITY)
        grant(2, "$e1", GrantScope.ENTITY)
        var signature = ""
        val base =
            ApplicationContextRunner()
                .withBindingKey()
                .withUserConfiguration(TenancyKernelWiring::class.java)
                .withBean(DataSource::class.java, { pool })
        (if (withReplica) base.withBean(AuthorityReplica::class.java, { replica }) else base)
            .run { context ->
                context.startupFailure shouldBe null
                context.getBean(TenantContext::class.java).bind(tenant, "p1") {
                    context.getBean(TransactionRunner::class.java).inTransaction { }
                }
                signature = set.getValue(3)
            }
        return signature
    }

    @Test
    fun `the service wiring binds the replica's set, signed, in five fields`() {
        val fields = boundSignature(withReplica = true).split('.')
        fields.size shouldBe 5
        fields[3] shouldBe "$e1,$e2"
    }

    @Test
    fun `without an authority replica the wiring binds the four-field form with no set`() {
        boundSignature(withReplica = false).split('.').size shouldBe 4
    }
}
