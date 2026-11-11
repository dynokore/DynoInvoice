package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.OperatorSession
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** INV-AC-16965, INV-Q-16496 A: an operator-session request is decided from the operator access record. */
class OperatorAccessReplicaTest {
    private val clock = MutableClock()
    private val fallback =
        FakeFallback<AuthorizationRequest, AuthorityVerdict> { FallbackResult.Found(AuthorityVerdict(true)) }
    private val roles =
        OperationRoleMap {
            if (it ==
                "doc.read"
            ) {
                mapOf("viewer" to CellAllowance.PERMITTED)
            } else {
                emptyMap()
            }
        }
    private val replica = AuthorityReplica(clock, roles, fallback = fallback)
    private val feed = FakeFeed(replica)
    private val now: Instant = clock.instant()
    private val metadataKey = "doc.view.metadata"
    private val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
    private val noGrant = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
    private val revoked = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.REVOKED)
    private val unavailable = AuthorityDecision(false, DecisionSource.DEGRADED, DecisionReason.AUTHORITY_UNAVAILABLE)

    private fun access(
        state: OperatorAccessState = OperatorAccessState.ACTIVE,
        permissions: Set<String> = setOf(metadataKey),
        validUntil: Instant = now.plusSeconds(900),
        elevation: OperatorElevation? = OperatorElevation("e1", now.minusSeconds(10), now.plusSeconds(300)),
        role: String = "platform.support",
        case: String = "CASE-1",
    ) = OperatorAccess(case, role, state, permissions, validUntil, elevation)

    private fun publish(
        v: Long,
        access: OperatorAccess = access(),
        tenant: TenantId = TENANT_A,
    ) = feed.publish(AuthorityChanged(tenant, "op1", v, AuthorityRecord.OperatorAccessRecord(access)))

    private fun ask(
        action: String,
        tenant: TenantId = TENANT_A,
        role: String = "platform.support",
        case: String = "CASE-1",
        entityRef: String = "ent1",
    ) = replica.decide(
        AuthorizationRequest(
            tenant,
            Principal("op1", PrincipalKind.HUMAN),
            entityRef,
            action,
            operatorSession = OperatorSession(role, case),
        ),
        deadline(clock),
    )

    @Test
    @DisplayName("INV-AC-16965 a permitted metadata key is GRANTED")
    fun permittedKey() {
        publish(1)
        ask(metadataKey) shouldBe granted
    }

    @Test
    @DisplayName("INV-AC-16965 doc.view.content inside the window is GRANTED, one second after validUntil NO_GRANT")
    fun contentInsideWindow() {
        publish(1)
        ask(OperatorAccess.CONTENT_KEY) shouldBe granted
        clock.advance(Duration.ofSeconds(299))
        ask(OperatorAccess.CONTENT_KEY) shouldBe granted
        clock.advance(Duration.ofSeconds(2))
        ask(OperatorAccess.CONTENT_KEY) shouldBe noGrant
    }

    @Test
    @DisplayName("INV-AC-16965 doc.view.content before the window opens is NO_GRANT")
    fun contentBeforeWindow() {
        publish(1, access(elevation = OperatorElevation("e1", now.plusSeconds(5), now.plusSeconds(300))))
        ask(OperatorAccess.CONTENT_KEY) shouldBe noGrant
    }

    @Test
    @DisplayName("INV-AC-16965 no elevation is NO_GRANT for doc.view.content")
    fun noElevation() {
        publish(1, access(elevation = null))
        ask(OperatorAccess.CONTENT_KEY) shouldBe noGrant
    }

    @Test
    @DisplayName("INV-AC-16965 a key outside permissions is NO_GRANT")
    fun keyOutside() {
        publish(1)
        ask("doc.edit") shouldBe noGrant
    }

    @Test
    @DisplayName("INV-AC-16965 another tenant, case or role is NO_GRANT")
    fun otherTenantCaseRole() {
        publish(1)
        ask(metadataKey, tenant = TENANT_B) shouldBe noGrant
        ask(metadataKey, case = "CASE-2") shouldBe noGrant
        ask(metadataKey, role = "platform.engineer") shouldBe noGrant
        ask(metadataKey, role = "platform.Support") shouldBe noGrant
    }

    @Test
    @DisplayName("INV-AC-16965 ENDED, then an older redelivered ACTIVE stays ended")
    fun ended() {
        publish(2)
        publish(3, access(state = OperatorAccessState.ENDED))
        ask(metadataKey) shouldBe noGrant
        publish(2)
        ask(metadataKey) shouldBe noGrant
        replica.operatorAccess.size() shouldBe 0
    }

    @Test
    @DisplayName("INV-AC-16965 the state parser reads only ACTIVE as ACTIVE")
    fun stateParser() {
        OperatorAccessState.of("ACTIVE") shouldBe OperatorAccessState.ACTIVE
        listOf("active", "Active", " ACTIVE", "ENDED", "", "X", null).forEach {
            OperatorAccessState.of(it) shouldBe OperatorAccessState.ENDED
        }
    }

    @Test
    @DisplayName("INV-AC-16965 validUntil passed is NO_GRANT and the entry is evicted on the next accept")
    fun expired() {
        publish(1, access(validUntil = now.plusSeconds(60)))
        ask(metadataKey) shouldBe granted
        clock.advance(Duration.ofSeconds(60))
        ask(metadataKey) shouldBe noGrant
        replica.operatorAccess.size() shouldBe 1
        publish(2, access(case = "CASE-9"))
        replica.operatorAccess.peek(OperatorAccessKey(TENANT_A, "op1", "CASE-1")) shouldBe null
    }

    @Test
    @DisplayName("INV-AC-16965 the principal record REVOKED with a higher version gives REVOKED")
    fun principalRevoked() {
        publish(1)
        feed.publish(AuthorityChanged(TENANT_A, "op1", 2, AuthorityRecord.PrincipalRecord(PrincipalState.REVOKED)))
        ask(metadataKey) shouldBe revoked
        publish(3)
        ask(metadataKey) shouldBe granted
    }

    @Test
    @DisplayName("INV-AC-16965 beyond the lag bound the answer is AUTHORITY_UNAVAILABLE and the fallback is not called")
    fun beyondLag() {
        publish(1)
        feed.heartbeat(50)
        clock.advance(Duration.ofSeconds(5))
        ask(metadataKey) shouldBe unavailable
        fallback.calls shouldBe 0
    }

    @Test
    @DisplayName("INV-AC-16965 before the first version the answer is AUTHORITY_UNAVAILABLE with no fallback call")
    fun beforeFirstVersion() {
        val cold = AuthorityReplica(clock, roles, fallback = fallback)
        cold
            .decide(
                AuthorizationRequest(
                    TENANT_A,
                    Principal("op1", PrincipalKind.HUMAN),
                    "ent1",
                    metadataKey,
                    operatorSession = OperatorSession("platform.support", "CASE-1"),
                ),
                deadline(clock),
            ) shouldBe unavailable
        fallback.calls shouldBe 0
    }

    @Test
    @DisplayName("INV-AC-16965 a grant record for the same entity does not grant a key only the grant covers")
    fun grantRecordsIgnored() {
        val grant = RoleGrant("viewer", GrantScope.ENTITY, "ent1", now.minusSeconds(60))
        feed.publish(AuthorityChanged(TENANT_A, "op1", 1, roleGrantRecord("g1", grant)))
        // no operator record: the grant never opens a session request
        ask("doc.read") shouldBe noGrant
        publish(2)
        ask("doc.read") shouldBe noGrant
        // the same operator without a session is still granted by the grant
        replica.decide(
            AuthorizationRequest(TENANT_A, Principal("op1", PrincipalKind.HUMAN), "ent1", "doc.read"),
            deadline(clock),
        ) shouldBe granted
    }

    @Test
    @DisplayName("INV-AC-16965 doc.view.content listed in permissions with no elevation is NO_GRANT")
    fun contentInPermissions() {
        publish(1, access(permissions = setOf(OperatorAccess.CONTENT_KEY, metadataKey), elevation = null))
        ask(OperatorAccess.CONTENT_KEY) shouldBe noGrant
        ask(metadataKey) shouldBe granted
    }

    @Test
    @DisplayName("INV-AC-16965 a machine principal with a session is NO_GRANT")
    fun machineWithSession() {
        publish(1)
        val machine = Principal.machine("op1", TENANT_A, MachineEntityCoverage.None, "filing-workflow")
        replica.decide(
            AuthorizationRequest(
                TENANT_A,
                machine,
                "ent1",
                metadataKey,
                operatorSession = OperatorSession("platform.support", "CASE-1"),
            ),
            deadline(clock),
        ) shouldBe noGrant
    }

    @Test
    @DisplayName("INV-AC-16965 the factory copies the operator session of the end-user principal")
    fun factoryCopiesSession() {
        val session = OperatorSession("platform.support", "CASE-1")
        val principal = EndUserPrincipal("op1", TENANT_A, emptySet(), operatorSession = session)
        AuthorizationRequest.of(principal, TENANT_A, "ent1", metadataKey).operatorSession shouldBe session
        AuthorizationRequest
            .of(
                EndUserPrincipal("op1", TENANT_A, emptySet()),
                TENANT_A,
                "e",
                "a",
            ).operatorSession shouldBe
            null
    }
}
