package com.dynokore.gst.evidence.adapters

import com.dynokore.gst.evidence.adapters.http.ReplicaCaseAuthorizer
import com.dynokore.gst.evidence.application.cases.CaseAccessDenied
import com.dynokore.gst.evidence.application.cases.CasePermissions
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * INV-AC-11698, INV-Q-11686 A, INV-Q-11688 A: [ReplicaCaseAuthorizer] permits a key only on a TENANT-scope grant that
 * `AuthorityReplica.decideTenant` decides LOCAL, asks for the key string exactly, and refuses everything else.
 */
class ReplicaCaseAuthorizerTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val asked = CopyOnWriteArrayList<String>()
    private val version = AtomicLong()
    private val record = CasePermissions.SECURITY_CASE_RECORD
    private val read = "privacy-case.read"

    // one role per key, so a principal can hold one key and not another; the role named security.officer lists none
    private val roles =
        OperationRoleMap { action ->
            asked += action
            when (action) {
                record -> mapOf("recorder" to CellAllowance.PERMITTED)
                read -> mapOf("reader" to CellAllowance.PERMITTED)
                CasePermissions.PRIVACY_REQUEST_HANDLE -> mapOf("handler" to CellAllowance.PERMITTED)
                else -> emptyMap()
            }
        }
    private val replica = AuthorityReplica(Clock.systemUTC(), roles)
    private val authorizer = ReplicaCaseAuthorizer(replica)

    private fun grant(
        principal: String,
        role: String,
        scope: GrantScope = GrantScope.TENANT,
        scopeRef: String = tenant.value.toString(),
    ) {
        val grant = RoleGrant(role, scope, scopeRef, Instant.parse("2026-01-01T00:00:00Z"), null)
        val at = version.incrementAndGet()
        replica.accept(
            AuthorityChanged(
                tenant,
                principal,
                at,
                AuthorityRecord.RoleGrantRecord("g-$at", RoleGrantState.ACTIVE, grant),
            ),
        )
        replica.onSnapshotComplete(at)
        replica.observeHead(at)
    }

    private fun refused(
        principal: String,
        key: String,
    ) {
        shouldThrow<CaseAccessDenied> {
            authorizer.require(tenant, Principal(principal, PrincipalKind.HUMAN), key)
        }.permission shouldBe
            key
    }

    private fun permitted(
        principal: String,
        key: String,
    ) = authorizer.require(tenant, Principal(principal, PrincipalKind.HUMAN), key)

    @Test
    fun `INV-AC-11698 a principal whose decision permits the key is permitted`() {
        grant("rec", "recorder")
        permitted("rec", record)
    }

    @Test
    fun `INV-AC-11698 a principal with no grant is refused every key`() {
        refused("nobody", record)
        refused("nobody", read)
    }

    @Test
    fun `INV-AC-11698 a principal who holds privacy-request handle but not security-case record is refused record`() {
        grant("handler-1", "handler")
        permitted("handler-1", CasePermissions.PRIVACY_REQUEST_HANDLE)
        refused("handler-1", record)
        refused("handler-1", read)
    }

    @Test
    fun `INV-AC-11698 a principal who holds privacy-case read but not security-case record reads and cannot record`() {
        grant("reader-1", "reader")
        permitted("reader-1", read)
        refused("reader-1", record)
    }

    @Test
    fun `INV-AC-11698 a principal who holds security-case record but not privacy-case read cannot read`() {
        grant("rec", "recorder")
        refused("rec", read)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["security-case.record ", "security-cases.record", "Security-case.record", "privacy-request.view"],
    )
    fun `INV-AC-11698 a look-alike key is refused even for a principal who holds the real key`(key: String) {
        grant("rec", "recorder")
        refused("rec", key)
    }

    @ParameterizedTest
    @ValueSource(strings = ["privacy-case.read ", "privacy-cases.read", "Privacy-case.read", "privacy-request.view"])
    fun `INV-AC-11698 a look-alike read key is refused even for a principal who holds the real key`(key: String) {
        grant("reader-1", "reader")
        refused("reader-1", key)
    }

    @Test
    fun `INV-AC-11698 a principal whose role is named security officer but whose decision is refuse is refused`() {
        grant("officer", "security.officer")
        refused("officer", record)
        refused("officer", read)
    }

    @Test
    fun `INV-AC-11698 a principal whose only grant of the key is at scope ENTITY is refused both keys`() {
        grant("scoped", "recorder", GrantScope.ENTITY, "ENT-A")
        grant("scoped", "reader", GrantScope.ENTITY, "ENT-A")
        refused("scoped", record)
        refused("scoped", read)
    }

    @Test
    fun `INV-AC-11698 a grant of another tenant does not permit`() {
        grant("rec", "recorder")
        val other = TenantId(UUID.randomUUID())
        shouldThrow<CaseAccessDenied> { authorizer.require(other, Principal("rec", PrincipalKind.HUMAN), record) }
    }

    @Test
    fun `INV-AC-11698 the key decideTenant receives equals the key string exactly`() {
        grant("rec", "recorder")
        asked.clear()
        permitted("rec", record)
        asked.toList() shouldBe listOf(record)
        asked.clear()
        runCatching { authorizer.require(tenant, Principal("rec", PrincipalKind.HUMAN), "security-case.record ") }
        asked.toList() shouldBe listOf("security-case.record ")
    }

    @Test
    fun `INV-AC-11698 a decision that is not LOCAL refuses and so does a failure of the decision`() {
        // no version received: the replica answers DEGRADED, never a permit
        val cold = ReplicaCaseAuthorizer(AuthorityReplica(Clock.systemUTC(), roles))
        shouldThrow<CaseAccessDenied> { cold.require(tenant, Principal("rec", PrincipalKind.HUMAN), record) }
        // a failure of the decision itself (the role map throws) refuses: fail closed
        val broken =
            AuthorityReplica(
                Clock.systemUTC(),
                OperationRoleMap { error("the permission matrix is unreadable") },
            )
        val grantOf =
            RoleGrant(
                "recorder",
                GrantScope.TENANT,
                tenant.value.toString(),
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
            )
        broken.accept(
            AuthorityChanged(tenant, "rec", 1, AuthorityRecord.RoleGrantRecord("g", RoleGrantState.ACTIVE, grantOf)),
        )
        broken.onSnapshotComplete(1)
        broken.observeHead(1)
        shouldThrow<CaseAccessDenied> {
            ReplicaCaseAuthorizer(broken).require(tenant, Principal("rec", PrincipalKind.HUMAN), record)
        }
    }
}
