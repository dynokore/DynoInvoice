package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.UUID

/** INV-Q-9065 A, INV-AC-9075: a degraded machine action needs a marked operation, token and covered entity. */
class DegradedAllowTest {
    private val clock = MutableClock()
    private val tenant2 = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))
    private val allowed = AuthorityDecision(true, DecisionSource.DEGRADED, DecisionReason.MACHINE_FLOW_CONTINUES)
    private val denied = AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)
    private val seen = mutableListOf<DegradedAllow>()
    private val table = DegradedAllowTable(setOf("POST" to "/v1/transactions", "GET" to "/v1/batches/{batchId}"))

    private fun replica(
        marked: DegradedAllowTable = table,
        entityOfTenant: (TenantId, String) -> Boolean = { _, _ -> false },
    ) = AuthorityReplica(clock, degradedAllow = marked, entityOfTenant = entityOfTenant, observer = { seen += it })

    private fun key(
        template: String = "/v1/transactions",
        principal: String = "ingest",
        method: String = "POST",
    ) = OperationKey(method, template, principal)

    private fun token(
        scope: String? = "ingest",
        tenant: TenantId? = TENANT_A,
        coverage: MachineEntityCoverage = MachineEntityCoverage.Entity("E1"),
    ) = Principal.machine("client-1", tenant, coverage, scope, "jti-1")

    private fun ask(
        replica: AuthorityReplica = replica(),
        principal: Principal = token(),
        entity: String = "E1",
        tenant: TenantId = TENANT_A,
        operation: OperationKey? = key(),
    ) = replica.decide(
        AuthorizationRequest(tenant, principal, entity, "ingest", operation = operation),
        deadline(clock),
    )

    @Test
    fun `a marked operation, matching scope, tenant and covered entity is allowed`() {
        ask() shouldBe allowed
        seen.size shouldBe 1
    }

    @Test
    fun `the same token for entity E2 is denied`() {
        ask(entity = "E2") shouldBe denied
    }

    @Test
    fun `an unmarked operation is denied`() {
        ask(operation = key("/v1/other")) shouldBe denied
    }

    @Test
    fun `a request without an operation is denied`() {
        ask(operation = null) shouldBe denied
    }

    @Test
    fun `a look-alike path of a marked template is denied`() {
        ask(operation = key("/v10/transactions")) shouldBe denied
        ask(operation = key("/v1/transactions/")) shouldBe denied
        ask(operation = key("/v1/transactions", method = "PUT")) shouldBe denied
    }

    @Test
    fun `a scope of another principal on a marked operation is denied`() {
        ask(principal = token(scope = "reporting")) shouldBe denied
        ask(principal = token(scope = null)) shouldBe denied
        ask(principal = token(scope = "INGEST")) shouldBe denied
    }

    @Test
    fun `a token of tenant T2 for a request of tenant T1 is denied`() {
        ask(principal = token(tenant = tenant2)) shouldBe denied
        ask(principal = token(tenant = null)) shouldBe denied
    }

    @Test
    fun `an empty table denies every machine case`() {
        ask(replica = replica(DegradedAllowTable.EMPTY)) shouldBe denied
        ask(replica = AuthorityReplica(clock)) shouldBe denied
    }

    @Test
    fun `installation and none coverage cover nothing, tenant coverage needs an entity of the tenant`() {
        ask(principal = token(coverage = MachineEntityCoverage.Installation)) shouldBe denied
        ask(principal = token(coverage = MachineEntityCoverage.None)) shouldBe denied
        val byTenant = token(coverage = MachineEntityCoverage.Tenant(TENANT_A.toString()))
        ask(principal = byTenant) shouldBe denied
        ask(replica = replica(entityOfTenant = { t, e -> t == TENANT_A && e == "E1" }), principal = byTenant) shouldBe
            allowed
        ask(
            replica =
                replica(entityOfTenant = {
                    _,
                    _,
                    ->
                    true
                }),
            principal = token(coverage = MachineEntityCoverage.Tenant("other")),
        ) shouldBe
            denied
    }

    @Test
    fun `humans and consent agents stay denied`() {
        for (kind in listOf(PrincipalKind.HUMAN, PrincipalKind.CONSENT_AGENT)) {
            ask(principal = Principal("p1", kind)) shouldBe denied
        }
    }

    @Test
    fun `three allows and two denials count three`() {
        ask()
        ask()
        ask()
        ask(entity = "E2")
        ask(operation = null)
        seen.size shouldBe 3
    }

    @Test
    fun `the audit line names operation, tenant, principal, entity and token and escapes control characters`() {
        val line = DegradedAllow(key(), TENANT_A, "client-1", "E1", "jti-1").line()
        line shouldContain "operation=\"POST /v1/transactions\""
        line shouldContain "principal=\"client-1\""
        line shouldContain "entity=\"E1\""
        line shouldContain "token=\"jti-1\""
        DegradedAllow(key(), TENANT_A, "a\nb", "E1", null).line() shouldNotContain "\n"
    }
}
