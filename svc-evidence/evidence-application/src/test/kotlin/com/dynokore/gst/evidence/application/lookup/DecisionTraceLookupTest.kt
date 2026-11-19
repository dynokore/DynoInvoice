package com.dynokore.gst.evidence.application.lookup

import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class DecisionTraceLookupTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val doc = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private val user = Principal("user-42", PrincipalKind.HUMAN)
    private val at = Instant.parse("2026-03-20T03:10:00Z")
    private val decisions =
        listOf(
            DecisionEntry(
                "RUL-001",
                "R1",
                listOf(PackVersionRef("pack-a", "1.2")),
                listOf(TraceInput("amount", "10", "IBT-123"), TraceInput("note", "x", null)),
            ),
        )
    private val reads = mutableListOf<String>()

    private fun reader(answer: StoredDecisionTrace?) =
        DecisionTraceReader { _, _, _ ->
            reads += "read"
            answer
        }

    private fun replica(
        key: String = DOC_VIEW_CONTENT,
        current: Boolean = true,
    ): AuthorityReplica {
        val roles = OperationRoleMap { if (it == key) mapOf("auditor" to CellAllowance.PERMITTED) else emptyMap() }
        val unavailable = ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { _, _ -> FallbackResult.Unavailable }
        val replica = AuthorityReplica(clock, roles, fallback = unavailable)
        replica.accept(
            AuthorityChanged(
                t1,
                user.id,
                1,
                AuthorityRecord.RoleGrantRecord(
                    "g1",
                    RoleGrantState.ACTIVE,
                    RoleGrant(
                        "auditor",
                        GrantScope.TENANT,
                        t1.value.toString(),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        null,
                    ),
                ),
            ),
        )
        if (current) {
            replica.onSnapshotComplete(1)
            replica.observeHead(1)
        }
        return replica
    }

    private fun lookup(
        answer: StoredDecisionTrace?,
        replica: AuthorityReplica = replica(),
    ) = DecisionTraceLookup(replica, reader(answer)).lookup(t1, user, doc, 1)

    @Test
    fun `INV-AC-20775 Found carries the decisions of the reader unchanged`() {
        val found = lookup(StoredDecisionTrace.Valid(at, decisions)).shouldBeInstanceOf<DecisionTraceResult.Found>()
        found.decisions shouldBe decisions
        found.docUuid shouldBe doc
        found.version shouldBe 1
        found.recordedAt shouldBe at
    }

    @Test
    fun `INV-AC-20775 NotFound for no row`() {
        lookup(null) shouldBe DecisionTraceResult.NotFound
    }

    @Test
    fun `INV-AC-20775 Malformed is passed through, never NotFound or an empty Found`() {
        lookup(StoredDecisionTrace.Malformed) shouldBe DecisionTraceResult.Malformed
    }

    @Test
    fun `INV-AC-20775 the caller without the key is Forbidden and the reader is not called`() {
        lookup(StoredDecisionTrace.Valid(at, decisions), replica(key = "other.key")) shouldBe
            DecisionTraceResult.Forbidden
        reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20775 AUTHORITY_UNAVAILABLE is Forbidden and the reader is not called`() {
        lookup(StoredDecisionTrace.Valid(at, decisions), replica(current = false)) shouldBe
            DecisionTraceResult.Forbidden
        reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20775 the look-alike key enquiry-lookup by-acknowledgement does not allow the lookup`() {
        val lookAlike = replica(key = ENQUIRY_LOOKUP_BY_ACKNOWLEDGEMENT)
        lookup(StoredDecisionTrace.Valid(at, decisions), lookAlike) shouldBe DecisionTraceResult.Forbidden
        reads shouldBe emptyList()
    }

    @Test
    fun `INV-AC-20775 the constructor has exactly the parameters AuthorityReplica and DecisionTraceReader`() {
        DecisionTraceLookup::class.java.constructors
            .single()
            .parameterTypes
            .toList() shouldBe listOf(AuthorityReplica::class.java, DecisionTraceReader::class.java)
    }
}
