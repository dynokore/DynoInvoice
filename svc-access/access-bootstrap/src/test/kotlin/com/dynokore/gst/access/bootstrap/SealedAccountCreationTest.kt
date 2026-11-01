package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.breakglass.SealingCredentials
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.accept
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.movableClock
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.provisioned
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.token
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.INSTALLATION
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.INSTALLER
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.adminGrants
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.provision
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.rows
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.gst.access.bootstrap.SealFixture.accountRows
import com.dynokore.gst.access.bootstrap.SealFixture.activated
import com.dynokore.gst.access.bootstrap.served.ServedContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * INV-AC-8898: the sealed break-glass account and its initial sealing credential are created when the first
 * tenant.admin grant becomes ACTIVE, in the same transaction, and not otherwise (INV-Q-8837 A, INV-Q-8893 A).
 */
class SealedAccountCreationTest {
    private fun ServedContext.revoke(invitation: Invitation) =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use {
                it.executeUpdate(
                    "update access.role_grant set status = 'REVOKED' where grant_ref = '${invitation.grantRef}'",
                )
            }
        }

    @Test
    fun `INV-AC-8898 the acceptance activates the grant, creates one row and the 200 carries the credential`() =
        withService { served ->
            val invitation = served.provisioned()
            assertThat(accountRows(invitation.tenant)).isEmpty()
            assertThat(adminGrants(invitation.tenant).single()).contains("\"status\": \"PENDING\"")
            val response = served.accept(invitation.ref, invitation.code, token("invitee-1"))
            assertThat(response.status).isEqualTo(200)
            assertThat(json(response)["status"].asString()).isEqualTo("ACTIVE")
            assertThat(json(response)["sealingCredential"].asString()).isNotBlank()
            assertThat(adminGrants(invitation.tenant).single()).contains("\"status\": \"ACTIVE\"")
            assertThat(accountRows(invitation.tenant)).hasSize(1)
        }

    @Test
    fun `INV-AC-8898 the provisioning calls create no row`() =
        withService { served ->
            val first = served.provisioned()
            val installation = FirstAdministratorFixture.newTenant()
            val answered = served.provision(INSTALLATION, INSTALLER, installation.toString())
            assertThat(answered.status).describedAs(answered.contentAsString).isEqualTo(201)
            assertThat(accountRows(first.tenant)).isEmpty()
            assertThat(accountRows(installation)).isEmpty()
        }

    @Test
    fun `INV-AC-8898 a wrong code leaves zero rows`() =
        withService { served ->
            val invitation = served.provisioned()
            val response = served.accept(invitation.ref, invitation.code + "x", token("invitee-1"))
            assertThat(response.status).isEqualTo(404)
            assertThat(accountRows(invitation.tenant)).isEmpty()
            assertThat(json(response).has("sealingCredential")).isFalse()
        }

    @Test
    fun `INV-AC-8898 an expired invitation leaves zero rows`() =
        withService { served ->
            val invitation = served.provisioned()
            served.movableClock().advance(Duration.ofDays(3650))
            val response = served.accept(invitation.ref, invitation.code, token("invitee-1"))
            assertThat(response.status).isEqualTo(409)
            assertThat(accountRows(invitation.tenant)).isEmpty()
        }

    @Test
    fun `INV-AC-8898 a withdrawn grant leaves zero rows`() =
        withService { served ->
            val invitation = served.provisioned()
            served.revoke(invitation)
            val response = served.accept(invitation.ref, invitation.code, token("invitee-1"))
            assertThat(response.status).isEqualTo(409)
            assertThat(accountRows(invitation.tenant)).isEmpty()
        }

    @Test
    fun `INV-AC-8898 no token leaves zero rows`() =
        withService { served ->
            val invitation = served.provisioned()
            val response = served.accept(invitation.ref, invitation.code, null)
            assertThat(response.status).isEqualTo(401)
            assertThat(accountRows(invitation.tenant)).isEmpty()
        }

    @Test
    fun `INV-AC-8898 the second acceptance is 409 and leaves one row and another tenant's row is not counted`() =
        withService { served ->
            val other = served.activated()
            val untouched = served.provisioned(newTenant())
            val first = served.activated()
            assertThat(accountRows(first.tenant)).hasSize(1)
            val second = served.accept(first.invitation.ref, first.invitation.code, token("invitee-2"))
            assertThat(second.status).isEqualTo(409)
            assertThat(json(second).has("sealingCredential")).isFalse()
            assertThat(accountRows(first.tenant)).hasSize(1)
            // the row of the other tenant is not counted for a tenant that has not accepted yet
            assertThat(accountRows(other.tenant)).hasSize(1)
            assertThat(accountRows(untouched.tenant)).isEmpty()
        }

    @Test
    fun `INV-AC-8898 a failure after the activation write leaves the grant PENDING and zero rows`() =
        withService(
            configure = { it.withBean(SealingCredentials::class.java, { SealingCredentials { error("injected") } }) },
        ) { served ->
            val invitation = served.provisioned()
            // the account is created after the grant was written active: the failure must undo the activation
            runCatching { served.accept(invitation.ref, invitation.code, token("invitee-1"), UUID.randomUUID()) }
            assertThat(adminGrants(invitation.tenant).single()).contains("\"status\": \"PENDING\"")
            assertThat(accountRows(invitation.tenant)).isEmpty()
            val invitationRow = rows("access.first_administrator_invitation", invitation.tenant)
            assertThat(invitationRow.single()).contains("\"accept_key\": null")
        }
}
