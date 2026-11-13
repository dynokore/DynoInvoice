package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.grant.RoleGrantMapper
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantSource
import com.dynokore.gst.access.domain.grant.GrantState
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GroupRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.PeriodRange
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.principal.PrincipalStatus
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DateSpan
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/**
 * Replica round trip (INV-Q-8972 A): the events this service publishes for a scripted sequence of ten changes,
 * decoded by the library's feed handler and fed to a fixture replica of service-runtime, give the answers the sequence
 * implies. It is the evidence that the writer and the reader agree.
 */
class AuthorityRoundTripTest {
    private val tenantValue = "0a000000-0000-4000-8000-00000000000a"
    private val tenant = TenantId.of(tenantValue)
    private val clock = Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC)
    private val identity = IdentityRef("IDN-P")
    private val versions = AtomicLong(0)
    private val mapper = RoleGrantMapper()
    private val writer = CapturedEvents()
    private val grantsOut = AuthorityChangedOutbox(writer, JsonMapper.builder().build(), InMemoryAuthorityHead(), InMemoryAuthorityIncarnation())
    private val revocationsOut =
        PrincipalRevocationOutbox(writer, JsonMapper.builder().build(), clock, InMemoryAuthorityHead(), InMemoryAuthorityIncarnation())

    private val operations =
        mapOf(
            "mapping.approve" to mapOf(Role.TAX_APPROVER.wire to CellAllowance.PERMITTED),
            "profile.edit" to mapOf(Role.ENTITY_ADMIN.wire to CellAllowance.PERMITTED),
            "tenant.settings" to mapOf(Role.TENANT_ADMIN.wire to CellAllowance.PERMITTED),
            "doc.view" to
                listOf(Role.TAX_APPROVER, Role.ENTITY_ADMIN, Role.AUDITOR, Role.FINANCE_VIEWER, Role.TAX_OPERATOR)
                    .associate { it.wire to CellAllowance.PERMITTED },
        )

    private fun structure(): StructureReplica {
        val replica = StructureReplica(clock)

        fun node(
            ref: String,
            kind: StructureNodeKind,
            parent: String?,
            version: Long,
        ) = replica.accept(
            OrganisationStructureChanged(
                tenant,
                ref,
                StructureChange.CREATED,
                StructureNode(kind, parent, false, ref, null, emptyList(), version),
            ),
        )
        node("GRP-1", StructureNodeKind.GROUP, null, 1)
        node("E-D", StructureNodeKind.ENTITY, "GRP-1", 2)
        node("E-X", StructureNodeKind.ENTITY, null, 3)
        replica.onSnapshotComplete(3)
        return replica
    }

    private val replica =
        AuthorityReplica(
            clock,
            OperationRoleMap { operations[it].orEmpty() },
            fallback = null,
            structure = structure(),
        ).also { it.onSnapshotComplete(0) }
    private val feed = AuthorityFeedHandler(replica)

    private fun grant(
        ref: String,
        role: Role,
        scope: Scope,
        status: GrantStatus = GrantStatus.ACTIVE,
    ) = Grant(
        GrantHeader(GrantRef(ref), TenantRef(tenantValue), identity),
        role,
        scope,
        ValidityWindow(Instant.parse("2026-10-19T00:00:00Z"), null),
        GrantState(status, GrantSource.BY_HAND, 0),
    )

    private val q1 = PeriodRange(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30))
    private val granted =
        listOf(
            grant("GRT-1", Role.TAX_APPROVER, Scope.Entity(EntityRef("E-A"))),
            grant("GRT-2", Role.ENTITY_ADMIN, Scope.Entity(EntityRef("E-B"))),
            grant("GRT-3", Role.AUDITOR, Scope.EntityPeriod(EntityRef("E-C"), q1)),
            grant("GRT-4", Role.FINANCE_VIEWER, Scope.Group(GroupRef("GRP-1"), TenantRef(tenantValue))),
            grant("GRT-5", Role.TENANT_ADMIN, Scope.Tenant(TenantRef(tenantValue))),
            grant("GRT-6", Role.TAX_OPERATOR, Scope.Entity(EntityRef("E-F"))),
        )

    /** Delivers what the writer appended since the last call, through the JSON form of the envelope. */
    private fun deliver(): List<Long> {
        val delivered = writer.appended.toList().also { writer.appended.clear() }
        return delivered.map { feed.decode(it.delivered()).authorityVersion }.also {
            delivered.forEach { appended -> feed.handle(appended.delivered()) }
        }
    }

    private fun decide(
        entity: String,
        action: String,
        period: DateSpan? = null,
    ): AuthorityDecision =
        replica.decide(
            AuthorizationRequest(tenant, Principal(identity.value, PrincipalKind.HUMAN), entity, action, period),
            Deadline.after(Duration.ofMillis(500), clock),
        )

    private fun assertAllowed(decision: AuthorityDecision) =
        assertEquals(AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED), decision)

    private fun assertDenied(
        decision: AuthorityDecision,
        reason: DecisionReason = DecisionReason.NO_GRANT,
    ) = assertEquals(AuthorityDecision(false, DecisionSource.LOCAL, reason), decision)

    private val principal =
        ServicePrincipal(
            identity.value,
            TenantRef(tenantValue),
            PrincipalType.INGEST,
            "tenant:$tenantValue",
            null,
            PrincipalStatus.REVOKED,
            1,
        )

    @Test
    fun `INV-AC-2544 the replica answers the 20 requests of the scripted sequence as the sequence implies`() {
        // changes 1 to 6: grants at entity, entity-period, group and tenant scope
        granted.forEach { grantsOut.publish(mapper.forChange(it, versions::incrementAndGet)) }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), deliver())

        // requests 1 to 4: one identity, two entities, each grant within its scope only (INV-AC-2544)
        assertAllowed(decide("E-A", "mapping.approve"))
        assertAllowed(decide("E-B", "profile.edit"))
        assertDenied(decide("E-B", "mapping.approve"))
        assertDenied(decide("E-A", "profile.edit"))
        // requests 5 and 6: the period grant covers a period inside the span only (INV-AC-1911)
        assertAllowed(decide("E-C", "doc.view", DateSpan(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31))))
        assertDenied(decide("E-C", "doc.view", DateSpan(LocalDate.of(2027, 7, 1), LocalDate.of(2027, 9, 30))))
        // requests 7 and 8: the group grant reaches the entity under the group and not the one outside it
        assertAllowed(decide("E-D", "doc.view"))
        assertDenied(decide("E-X", "doc.view"))
        // requests 9 and 10: the tenant grant reaches every entity; the entity grant on E-F
        assertAllowed(decide("E-A", "tenant.settings"))
        assertAllowed(decide("E-F", "doc.view"))

        // change 7: a revoke of the tenant grant; change 8: an unmap of the group grant
        grantsOut.publish(mapper.forChange(granted[4].revoked(), versions::incrementAndGet))
        grantsOut.publish(mapper.forChange(granted[3].revoked(), versions::incrementAndGet))
        assertEquals(listOf(7L, 8L), deliver())
        assertDenied(decide("E-A", "tenant.settings"))
        assertDenied(decide("E-D", "doc.view"))
        assertAllowed(decide("E-A", "mapping.approve"))

        // change 9: the revocation of the principal voids every older grant record
        revocationsOut.publishRevoked(principal, versions::incrementAndGet)
        val revocationVersion = versions.get()
        assertEquals(listOf(9L), deliver())
        assertDenied(decide("E-A", "mapping.approve"), DecisionReason.REVOKED)
        assertDenied(decide("E-B", "profile.edit"), DecisionReason.REVOKED)
        assertDenied(decide("E-F", "doc.view"), DecisionReason.REVOKED)

        // change 10: the reactivation re-emits each grant active after it, at higher versions
        val active = granted.filter { it.ref.value in setOf("GRT-1", "GRT-2", "GRT-3", "GRT-6") }
        grantsOut.publish(mapper.forReactivation(active, versions::incrementAndGet))
        val reissued = deliver()
        assertEquals(listOf(10L, 11L, 12L, 13L), reissued)
        assertTrue(reissued.all { it > revocationVersion })
        assertAllowed(decide("E-A", "mapping.approve"))
        assertAllowed(decide("E-B", "profile.edit"))
        assertDenied(decide("E-D", "doc.view"))
        assertDenied(decide("E-A", "tenant.settings"))
    }

    @Test
    fun `INV-AC-1911 after the validity window every action of a period grant is refused`() {
        val window = ValidityWindow(Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"))
        val lapsed =
            Grant(
                GrantHeader(GrantRef("GRT-9"), TenantRef(tenantValue), identity),
                Role.AUDITOR,
                Scope.EntityPeriod(EntityRef("E-C"), q1),
                window,
                GrantState(GrantStatus.ACTIVE, GrantSource.BY_HAND, 0),
            )
        grantsOut.publish(mapper.forChange(lapsed, versions::incrementAndGet))
        deliver()
        // the fixture clock is 2026-11-08, after validUntil: inside the span, but outside the window
        assertDenied(decide("E-C", "doc.view", DateSpan(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31))))
    }
}
