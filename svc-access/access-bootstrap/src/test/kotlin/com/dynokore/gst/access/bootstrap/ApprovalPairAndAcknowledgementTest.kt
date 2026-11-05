package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.contract.model.GrantRequest
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * The role pair `tax.approver` and `tenant.admin` (INV-T-4936; INV-REQ-860 behaviour 3, INV-AC-2042): the rules of
 * VP-81 (one holder, withdrawal) apply to it as they do to the officer pair, and `getMappingAcknowledgement` answers
 * the stored acknowledgement of exactly one pack version and client (INV-AC-9557 for the part this service produces).
 */
class ApprovalPairAndAcknowledgementTest {
    private val oneHolder =
        "only one person may hold tax.approver and tenant.admin under the sole-operator acknowledgement"
    private val ackRequired = "tax.approver and tenant.admin need the recorded sole-operator acknowledgement"
    private val holderRemains = "a person still holds tax.approver and tenant.admin; revoke one of the roles first"

    private fun ApprovalEnv.tenantScope() = "tenant:$tenant"

    private fun ApprovalEnv.bothFor(person: String) {
        grant(person, "tax.approver", "entity:ENT-1")
        grant(person, "tenant.admin", tenantScope())
    }

    @Test
    fun `INV-AC-2042 the pair is refused in both orders without the acknowledgement and granted with it`() =
        approvalRun { env ->
            env.grant("IDN-U", "tax.approver", "entity:ENT-1")
            assertThat(env.refusal { env.grant("IDN-U", "tenant.admin", env.tenantScope()) }.detail)
                .isEqualTo(ackRequired)
            env.grant("IDN-V", "tenant.admin", env.tenantScope())
            assertThat(env.refusal { env.grant("IDN-V", "tax.approver", "entity:ENT-1") }.detail)
                .isEqualTo(ackRequired)
            env.acknowledge()
            assertThat(env.grant("IDN-U", "tenant.admin", env.tenantScope()).statusCode.value()).isEqualTo(201)
            // one person holds both now: a second holder is refused under the default value of VP-81
            assertThat(env.refusal { env.grant("IDN-V", "tax.approver", "entity:ENT-1") }.detail).isEqualTo(oneHolder)
            // a look-alike pair is no conflict, with or without the acknowledgement
            assertThat(env.grant("IDN-W", "entity.admin", "entity:ENT-1").statusCode.value()).isEqualTo(201)
            assertThat(env.grant("IDN-W", "tenant.admin", env.tenantScope()).statusCode.value()).isEqualTo(201)
        }

    @Test
    fun `INV-AC-2042 the acknowledgement of another tenant does not admit the pair`() =
        approvalRun { env ->
            env.grant("IDN-U", "tax.approver", "entity:ENT-1")
            ApprovalEnv(env.context, TenantId(UUID.randomUUID())).acknowledge()
            assertThat(
                env.refusal { env.grant("IDN-U", "tenant.admin", env.tenantScope()) }.detail,
            ).isEqualTo(ackRequired)
        }

    @Test
    fun `INV-AC-2042 a second holder is refused and the withdrawal waits until nobody holds both`() =
        approvalRun { env ->
            val ack = env.acknowledge()
            env.grant("IDN-Z", "tenant.admin", env.tenantScope())
            env.grant("IDN-X", "tax.approver", "entity:ENT-1")
            val adminGrant = env.grant("IDN-X", "tenant.admin", env.tenantScope()).body!!.grantRef
            env.grant("IDN-Y", "tax.approver", "entity:ENT-1")
            val second = env.refusal { env.grant("IDN-Y", "tenant.admin", env.tenantScope()) }
            assertThat(second.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(second.detail).isEqualTo(oneHolder)
            assertThat(env.refusal { env.withdraw(ack) }.detail).isEqualTo(holderRemains)
            env.asUser(ApprovalEnv.ADMIN) { env.grants.revokeGrant(adminGrant, UUID.randomUUID()) }
            assertThat(env.withdraw(ack).statusCode.value()).isEqualTo(204)
            assertThat(env.refusal { env.grant("IDN-X", "tenant.admin", env.tenantScope()) }.detail)
                .isEqualTo(ackRequired)
        }

    @Test
    fun `INV-AC-2042 a sole holder of the pair cannot see the acknowledgement withdrawn`() =
        approvalRun { env ->
            val ack = env.acknowledge()
            env.grant("IDN-X", "tax.approver", "entity:ENT-1")
            env.grant("IDN-X", "tenant.admin", env.tenantScope())
            // the only people holding tenant.admin or tax.approver are the administrator and X, and X holds both
            assertThat(env.refusal { env.withdraw(ack) }.detail).isEqualTo(holderRemains)
        }

    @Test
    fun `INV-AC-2042 a tenant that never combined the pair is withdrawn exactly as before`() =
        approvalRun { env ->
            val ack = env.acknowledge()
            env.grant("IDN-Z", "tenant.admin", env.tenantScope())
            env.grant("IDN-Y", "tax.approver", "entity:ENT-1")
            assertThat(env.withdraw(ack).statusCode.value()).isEqualTo(204)
            assertThat(
                env.refusal { env.grant("IDN-Y", "tenant.admin", env.tenantScope()) }.detail,
            ).isEqualTo(ackRequired)
        }

    private fun seedAcknowledgement(
        tenant: TenantId,
        packVersion: String,
        clientRef: String,
        at: Instant,
    ) = BoundaryFixture.asSuperuser { connection ->
        connection
            .prepareStatement(
                "insert into access.mapping_acknowledgement " +
                    "(id, tenant_id, pack_version, client_ref, acknowledged_by, acknowledged_at) " +
                    "values (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, tenant.value)
                statement.setString(3, packVersion)
                statement.setString(4, clientRef)
                statement.setString(5, "user:signatory")
                statement.setTimestamp(6, Timestamp.from(at))
                statement.executeUpdate()
            }
    }

    @Test
    fun `INV-AC-9557 the stored acknowledgement is read for its pack version and client only`() =
        approvalRun { env ->
            val at = Instant.parse("2026-09-27T02:00:00Z")
            seedAcknowledgement(env.tenant, "2026.3", "CLI-2201", at)
            val found = env.asUser("user:reader") { env.operations.getMappingAcknowledgement("2026.3", "CLI-2201") }
            assertThat(found.statusCode.value()).isEqualTo(200)
            assertThat(found.body!!.packVersion).isEqualTo("2026.3")
            assertThat(found.body!!.clientRef).isEqualTo("CLI-2201")
            assertThat(found.body!!.acknowledgedAt.toInstant()).isEqualTo(at)
            listOf(
                "2026.30" to "CLI-2201",
                "2026.3" to "CLI-2202",
                "2026.3" to "cli-2201",
                "2026.3 " to "CLI-2201",
            ).forEach {
                val refusal =
                    env.refusal {
                        env.asUser("user:reader") { env.operations.getMappingAcknowledgement(it.first, it.second) }
                    }
                assertThat(refusal.code).describedAs(it.toString()).isEqualTo(ClosedErrorCode.NOT_FOUND)
            }
            // the row of another tenant is not visible
            val other = ApprovalEnv(env.context, TenantId(UUID.randomUUID()))
            val refusal =
                env.refusal {
                    other.asUser("user:reader") { other.operations.getMappingAcknowledgement("2026.3", "CLI-2201") }
                }
            assertThat(refusal.code).isEqualTo(ClosedErrorCode.NOT_FOUND)
        }
}
