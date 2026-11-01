package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.grant.GrantRoleCommand
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.grant.RevokeGrantUseCase
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.CONTROL_PLANE
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.FIRST
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.INSTALLATION
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.INSTALLER
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.OTHER
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.adminGrants
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.expectedCode
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.first
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.provision
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.provisionBody
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.rows
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.seedAdminRow
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.snapshot
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.gst.access.bootstrap.served.OPERATOR_TOKEN
import com.dynokore.gst.access.bootstrap.served.ServedContext
import com.dynokore.gst.access.bootstrap.served.USER_A_TOKEN
import com.dynokore.gst.access.bootstrap.served.USER_B_TOKEN
import com.dynokore.gst.access.domain.grant.EntityMembership
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * INV-AC-8224, the provisioning half (INV-T-8226): the first call, its rows, the repeat and the key rules.
 * Through the real context over a real PostgreSQL with forced row-level security, the generated caller check and
 * the request filters in front of the controller; the doubles are those of ServedDoubles.
 */
class FirstAdministratorProvisioningTest {
    @Test
    fun `INV-AC-8224 the first call answers 201 with a pending grant and a derived code`() =
        withService { served ->
            val tenant = newTenant()
            val response = served.first(tenant)
            assertThat(response.status).isEqualTo(201)
            val body = json(response)
            assertThat(body["status"].asString()).isEqualTo("PENDING")
            val ref = body["invitationRef"].asString()
            assertThat(ref).startsWith("INVN-").hasSizeLessThanOrEqualTo(48)
            assertThat(body["invitationCode"].asString()).isEqualTo(expectedCode(tenant, ref))
            val g = FirstAdministratorFixture.JSON.readTree(rows("access.role_grant", tenant).single())
            assertThat(g["grant_ref"].asString()).isEqualTo(body["grantRef"].asString())
            assertThat(g["role"].asString()).isEqualTo("tenant.admin")
            assertThat(g["scope_level"].asString()).isEqualTo("TENANT")
            assertThat(g["scope_ref"].asString()).isEqualTo(tenant.toString())
            assertThat(g["status"].asString()).isEqualTo("PENDING")
            assertThat(g["grant_source"].asString()).isEqualTo("BY_HAND")
            assertThat(g["identity_ref"].asString()).isEqualTo("invitation:$ref")
        }

    @Test
    fun `INV-AC-8224 the first call stores the placeholder identity and the invitation row`() =
        withService { served ->
            val tenant = newTenant()
            val body = json(served.first(tenant))
            val ref = body["invitationRef"].asString()
            val code = body["invitationCode"].asString()
            val i = FirstAdministratorFixture.JSON.readTree(rows("access.identity", tenant).single())
            assertThat(i["identity_ref"].asString()).isEqualTo("invitation:$ref")
            assertThat(i["grantee_kind"].asString()).isEqualTo("EXTERNAL")
            val stored = rows("access.first_administrator_invitation", tenant).single()
            val v = FirstAdministratorFixture.JSON.readTree(stored)
            assertThat(v["invitation_ref"].asString()).isEqualTo(ref)
            assertThat(v["grant_ref"].asString()).isEqualTo(body["grantRef"].asString())
            assertThat(v["invited_email"].asString()).isEqualTo(FirstAdministratorFixture.EMAIL)
            assertThat(Instant.parse(v["expires_at"].asString().replace("+00:00", "Z")))
                .isEqualTo(Instant.parse(FirstAdministratorFixture.EXPIRES))
            assertThat(v["code_key_version"].asString()).isEqualTo("v1")
            val digest =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(code.toByteArray())
                    .let {
                        java.util.HexFormat
                            .of()
                            .formatHex(it)
                    }
            assertThat(v["code_digest"].asString()).isEqualTo(digest)
            assertThat(v["created_by"].asString()).isEqualTo("operator-1")
        }

    @Test
    fun `INV-AC-8224 no column of the four tables holds the plaintext code`() =
        withService { served ->
            val tenant = newTenant()
            val code = json(served.first(tenant))["invitationCode"].asString()
            val all = snapshot(tenant)
            // one invitation, one grant, one identity; the platform idempotency store is not used by this operation
            assertThat(all.mapValues { it.value.size }).containsEntry("access.idempotency_key", 0)
            assertThat(all.values.flatten()).hasSize(3)
            all.forEach { (table, tableRows) ->
                tableRows.forEach { assertThat(it).describedAs("a row of $table").doesNotContain(code) }
            }
        }

    @Test
    fun `INV-AC-8224 a repeat with the same key and request returns the original response and stores nothing`() =
        withService { served ->
            val tenant = newTenant()
            val key = UUID.randomUUID()
            val first = served.first(tenant, key)
            val before = snapshot(tenant)
            val events = served.stored()
            val repeat = served.first(tenant, key)
            assertThat(repeat.status).isEqualTo(first.status).isEqualTo(201)
            val a = json(first)
            val b = json(repeat)
            listOf("grantRef", "status", "invitationRef", "invitationCode").forEach {
                assertThat(b[it].asString()).describedAs(it).isEqualTo(a[it].asString())
            }
            assertThat(snapshot(tenant)).isEqualTo(before)
            assertThat(served.stored()).isEqualTo(events)
        }

    @Test
    fun `INV-AC-8224 a new key for a tenant that holds the pending grant is 409 and stores nothing`() =
        withService { served ->
            val tenant = newTenant()
            served.first(tenant)
            val before = snapshot(tenant)
            val events = served.stored()
            val second = served.first(tenant)
            assertThat(second.status).isEqualTo(409)
            assertThat(snapshot(tenant)).isEqualTo(before)
            assertThat(served.stored()).isEqualTo(events)
        }

    @Test
    fun `INV-AC-8224 the same key with another administrator email is 422 and stores nothing`() =
        withService { served ->
            val tenant = newTenant()
            val key = UUID.randomUUID()
            served.first(tenant, key)
            val before = snapshot(tenant)
            val other =
                served.provisionBody(
                    FIRST,
                    CONTROL_PLANE,
                    FirstAdministratorFixture.body(tenant.toString(), "other@example.sg"),
                    key,
                )
            assertThat(other.status).isEqualTo(422)
            assertThat(snapshot(tenant)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8224 the same key in another tenant is a new call`() =
        withService { served ->
            val a = newTenant()
            val b = newTenant()
            val key = UUID.randomUUID()
            val one = served.first(a, key)
            val two = served.first(b, key)
            assertThat(one.status).isEqualTo(201)
            assertThat(two.status).isEqualTo(201)
            assertThat(json(two)["invitationRef"].asString()).isNotEqualTo(json(one)["invitationRef"].asString())
            assertThat(rows("access.first_administrator_invitation", a)).hasSize(1)
            assertThat(rows("access.first_administrator_invitation", b)).hasSize(1)
        }

    @Test
    fun `INV-AC-8224 the codes of two tenants for the same invitation reference differ`() =
        withService { served ->
            val codes = FirstAdministratorFixture.codes(served)
            val one = codes.codeOf(TenantRef(newTenant().toString()), "INVN-same", "v1")
            val two = codes.codeOf(TenantRef(newTenant().toString()), "INVN-same", "v1")
            assertThat(one).isNotEqualTo(two)
        }
}
