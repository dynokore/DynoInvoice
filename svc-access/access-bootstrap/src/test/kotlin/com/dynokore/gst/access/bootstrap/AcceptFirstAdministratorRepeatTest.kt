package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.accept
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.movableClock
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.provisioned
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.token
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.viaConsole
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.adminGrants
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.rows
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.snapshot
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.gst.access.bootstrap.served.ServedContext
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * INV-AC-8224: the repeat of `acceptFirstAdministratorInvitation` with the same Idempotency-Key (INV-Q-15347 A) and
 * the one-time `sealingCredential` (INV-Q-15345 A, INV-T-8853), through the real context over a real PostgreSQL.
 */
class AcceptFirstAdministratorRepeatTest {
    private fun ServedContext.events(): Int = application.getBean(CapturedEvents::class.java).envelopes.size

    private fun authorityChanged(tenant: TenantId): Int =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select count(*) from access.outbox where tenant_id = ? and type like '%AuthorityChanged%'",
                ).use {
                    it.setObject(1, tenant.value)
                    it.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    private fun ServedContext.revoke(invitation: Invitation) =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "update access.role_grant set status = 'REVOKED' where grant_ref = '${invitation.grantRef}'",
                )
            }
        }

    @Test
    fun `INV-AC-8224 the same key and body twice replay the grant data and write nothing`() =
        withService { served ->
            val invitation = served.provisioned()
            val key = UUID.randomUUID()
            val first = served.accept(invitation.ref, invitation.code, token("invitee-1"), key)
            assertThat(first.status).isEqualTo(200)
            val before = snapshot(invitation.tenant)
            val events = served.events()
            val raised = authorityChanged(invitation.tenant)
            // after the expiry: the first call was in time
            served.movableClock().advance(Duration.ofDays(3650))
            val again = served.accept(invitation.ref, invitation.code, token("invitee-1"), key)
            assertThat(again.status).describedAs(again.contentAsString).isEqualTo(200)
            for (field in listOf("grantRef", "identityRef", "role", "scope", "status", "grantSource")) {
                assertThat(json(again)[field].asString()).isEqualTo(json(first)[field].asString())
            }
            assertThat(snapshot(invitation.tenant)).isEqualTo(before)
            assertThat(served.events()).isEqualTo(events)
            assertThat(authorityChanged(invitation.tenant)).isEqualTo(raised)
        }

    @Test
    fun `INV-AC-8224 the property sealingCredential is present in the first response and absent in the repeated one`() =
        withService { served ->
            val invitation = served.provisioned()
            val key = UUID.randomUUID()
            val first = served.accept(invitation.ref, invitation.code, token("invitee-1"), key)
            val again = served.accept(invitation.ref, invitation.code, token("invitee-1"))
            val repeat = served.accept(invitation.ref, invitation.code, token("invitee-1"), key)
            assertThat(first.status).isEqualTo(200)
            assertThat(again.status).isEqualTo(409)
            assertThat(repeat.status).isEqualTo(200)
            // the first acceptance of a tenant creates its sealed account and returns the credential once (INV-T-8853)
            assertThat(json(first).has("sealingCredential")).isTrue()
            assertThat(repeat.contentAsString).doesNotContain("sealingCredential")
            assertThat(again.contentAsString).doesNotContain("sealingCredential")
            assertThat(json(repeat).has("sealingCredential")).isFalse()
        }

    @Test
    fun `INV-AC-8224 the same key with a code that differs in one character is 422 and changes nothing`() =
        withService { served ->
            val invitation = served.provisioned()
            val key = UUID.randomUUID()
            served.accept(invitation.ref, invitation.code, token("invitee-1"), key)
            val before = snapshot(invitation.tenant)
            val flipped = (if (invitation.code.last() == 'A') 'B' else 'A').let { invitation.code.dropLast(1) + it }
            assertThat(served.accept(invitation.ref, flipped, token("invitee-1"), key).status).isEqualTo(422)
            assertThat(snapshot(invitation.tenant)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8224 the same key by a second sub is 422 and changes nothing`() =
        withService { served ->
            val invitation = served.provisioned()
            val key = UUID.randomUUID()
            served.accept(invitation.ref, invitation.code, token("invitee-1"), key)
            val before = snapshot(invitation.tenant)
            assertThat(served.accept(invitation.ref, invitation.code, token("invitee-2"), key).status).isEqualTo(422)
            assertThat(snapshot(invitation.tenant)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8224 the same key after the grant was revoked is 409`() =
        withService { served ->
            val invitation = served.provisioned()
            val key = UUID.randomUUID()
            served.accept(invitation.ref, invitation.code, token("invitee-1"), key)
            served.revoke(invitation)
            val before = snapshot(invitation.tenant)
            assertThat(served.accept(invitation.ref, invitation.code, token("invitee-1"), key).status).isEqualTo(409)
            assertThat(snapshot(invitation.tenant)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8224 a key that differs from the stored one in one hex digit is 409 and not a replay`() =
        withService { served ->
            val invitation = served.provisioned()
            val key = UUID.randomUUID()
            served.accept(invitation.ref, invitation.code, token("invitee-1"), key)
            val text = key.toString()
            val digit = if (text.last() == '0') '1' else '0'
            val other = UUID.fromString(text.dropLast(1) + digit)
            val before = snapshot(invitation.tenant)
            assertThat(served.accept(invitation.ref, invitation.code, token("invitee-1"), other).status).isEqualTo(409)
            assertThat(snapshot(invitation.tenant)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8224 a wrong code on a revoked grant is 404, not 409`() =
        withService { served ->
            val invitation = served.provisioned()
            served.revoke(invitation)
            val before = snapshot(invitation.tenant)
            val wrong = (if (invitation.code.last() == 'A') 'B' else 'A').let { invitation.code.dropLast(1) + it }
            assertThat(served.accept(invitation.ref, wrong, token("invitee-1")).status).isEqualTo(404)
            assertThat(snapshot(invitation.tenant)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8224 two concurrent first calls with the same key give one acceptance and one replay`() =
        withService { served ->
            val invitation = served.provisioned()
            val key = UUID.randomUUID()
            val auth = token("invitee-1")
            val pool = Executors.newFixedThreadPool(2)
            try {
                val calls =
                    List(2) {
                        Callable { served.accept(invitation.ref, invitation.code, auth, key).status }
                    }
                val statuses = pool.invokeAll(calls).map { it.get() }
                assertThat(statuses).containsExactly(200, 200)
            } finally {
                pool.shutdownNow()
            }
            assertThat(adminGrants(invitation.tenant)).hasSize(1)
            val audits =
                served.application
                    .getBean(CapturedEvents::class.java)
                    .envelopes
                    .filter {
                        it.dataJson.contains("acceptFirstAdministrator") &&
                            it.dataJson.contains(invitation.grantRef)
                    }
            assertThat(audits).hasSize(1)
        }
}
