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

/**
 * INV-AC-8224: `acceptFirstAdministratorInvitation` (INV-FLOW-227 step 3) through the real context over a real
 * PostgreSQL with forced row-level security, the generated caller check and the request filters in front of the
 * controller. The clock is the service clock port; the doubles are those of ServedDoubles.
 */
class AcceptFirstAdministratorInvitationTest {
    private fun ServedContext.events(): Int = application.getBean(CapturedEvents::class.java).envelopes.size

    /** The AuthorityChanged rows of the outbox of [tenant], read as the superuser. */
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

    private fun grantRow(
        invitation: Invitation,
        column: String,
    ): String? =
        rows("access.role_grant", invitation.tenant)
            .map { json(it) }
            .single { it["grant_ref"].asString() == invitation.grantRef }[column]
            ?.takeUnless { it.isNull }
            ?.asString()

    private fun digestOf(invitation: Invitation): String? =
        rows("access.first_administrator_invitation", invitation.tenant)
            .map { json(it) }
            .single()["code_digest"]
            ?.takeUnless { it.isNull }
            ?.asString()

    private fun expiresIn(
        seconds: Long,
        clock: MovableClock,
    ): String = clock.instant().plusSeconds(seconds).toString()

    // ---- acceptance ----

    @Test
    fun `INV-AC-8224 the right code before expiry makes the grant ACTIVE bound to the sub, with AuthorityChanged`() =
        withService { served ->
            val invitation = served.provisioned()
            // one entity in the tenant, so that the tenant scope reaches something (AuthorityChanged is per entity)
            served.application
                .getBean(StructureReplica::class.java)
                .accept(
                    OrganisationStructureChanged(
                        invitation.tenant,
                        "ENT-1",
                        StructureChange.CREATED,
                        BoundaryFixture.entityNode(),
                    ),
                )
            assertThat(grantRow(invitation, "identity_ref")).isEqualTo("invitation:${invitation.ref}")
            val raised = authorityChanged(invitation.tenant)
            val response = served.accept(invitation.ref, invitation.code, token("invitee-1"))
            assertThat(response.status).describedAs(response.contentAsString).isEqualTo(200)
            val body = json(response)
            assertThat(body["grantRef"].asString()).isEqualTo(invitation.grantRef)
            assertThat(body["identityRef"].asString()).isEqualTo("invitee-1")
            assertThat(body["role"].asString()).isEqualTo("tenant.admin")
            assertThat(body["status"].asString()).isEqualTo("ACTIVE")
            assertThat(grantRow(invitation, "status")).isEqualTo("ACTIVE")
            assertThat(grantRow(invitation, "identity_ref")).isEqualTo("invitee-1")
            assertThat(digestOf(invitation)).isNull()
            assertThat(rows("access.identity", invitation.tenant).map { json(it)["identity_ref"].asString() })
                .contains("invitee-1")
            assertThat(authorityChanged(invitation.tenant)).isGreaterThan(raised)
            val audit =
                served.application
                    .getBean(CapturedEvents::class.java)
                    .envelopes
                    .filter {
                        it.type.contains("AuditEntryRecorded") && it.dataJson.contains("acceptFirstAdministrator")
                    }
            assertThat(audit.single().dataJson).contains("invitee-1").contains(invitation.grantRef)
        }

    @Test
    fun `INV-AC-8224 a second acceptance after success is 409 and changes nothing`() =
        withService { served ->
            val invitation = served.provisioned()
            assertThat(served.accept(invitation.ref, invitation.code, token("invitee-1")).status).isEqualTo(200)
            val before = snapshot(invitation.tenant)
            val events = served.events()
            assertThat(served.accept(invitation.ref, invitation.code, token("invitee-1")).status).isEqualTo(409)
            assertThat(snapshot(invitation.tenant)).isEqualTo(before)
            assertThat(served.events()).isEqualTo(events)
        }

    @Test
    fun `INV-AC-8224 a second identity after success is 409 and changes nothing`() =
        withService { served ->
            val invitation = served.provisioned()
            served.accept(invitation.ref, invitation.code, token("invitee-1"))
            val before = snapshot(invitation.tenant)
            val events = served.events()
            assertThat(served.accept(invitation.ref, invitation.code, token("invitee-2")).status).isEqualTo(409)
            assertThat(snapshot(invitation.tenant)).isEqualTo(before)
            assertThat(served.events()).isEqualTo(events)
            assertThat(grantRow(invitation, "identity_ref")).isEqualTo("invitee-1")
        }

    // ---- refusals: each leaves the rows and the events as they were ----

    private fun ServedContext.refused(
        invitation: Invitation,
        ref: String,
        code: String,
        auth: String?,
        status: Int,
    ) {
        val before = snapshot(invitation.tenant)
        val events = events()
        val response = accept(ref, code, auth)
        assertThat(response.status).describedAs(response.contentAsString).isEqualTo(status)
        assertThat(snapshot(invitation.tenant)).isEqualTo(before)
        assertThat(events()).isEqualTo(events)
        assertThat(grantRow(invitation, "status")).isEqualTo("PENDING")
    }

    @Test
    fun `INV-AC-8224 a code that differs in one character is 404 and the grant stays PENDING`() =
        withService { served ->
            val invitation = served.provisioned()
            val last = invitation.code.last()
            val changed = invitation.code.dropLast(1) + if (last == 'A') 'B' else 'A'
            served.refused(invitation, invitation.ref, changed, token("invitee-1"), 404)
        }

    @Test
    fun `INV-AC-8224 a wrong code and an unknown reference answer the same 404`() =
        withService { served ->
            val invitation = served.provisioned()
            val wrong = served.accept(invitation.ref, "not-the-code", token("invitee-1"))
            val unknown = served.accept("INVN-unknown", invitation.code, token("invitee-1"))
            assertThat(wrong.status).isEqualTo(404)
            assertThat(unknown.status).isEqualTo(404)
            assertThat(json(wrong)["title"]).isEqualTo(json(unknown)["title"])
            assertThat(json(wrong)["detail"]).isEqualTo(json(unknown)["detail"])
        }

    @Test
    fun `INV-AC-8224 the code of another tenant's invitation does not accept this grant`() =
        withService { served ->
            val one = served.provisioned()
            val two = served.provisioned()
            served.refused(one, one.ref, two.code, token("invitee-1"), 404)
            served.refused(two, two.ref, one.code, token("invitee-1"), 404)
        }

    @Test
    fun `INV-AC-8224 an expiry one second ago is 409 and the grant stays PENDING`() =
        withService { served ->
            val clock = served.movableClock()
            val invitation = served.provisioned(expires = expiresIn(1, clock))
            clock.advance(Duration.ofSeconds(2))
            served.refused(invitation, invitation.ref, invitation.code, token("invitee-1"), 409)
        }

    @Test
    fun `INV-AC-8224 an expiry one second ahead is admitted`() =
        withService { served ->
            val clock = served.movableClock()
            val invitation = served.provisioned(expires = clock.freeze().plusSeconds(1).toString())
            assertThat(served.accept(invitation.ref, invitation.code, token("invitee-1")).status).isEqualTo(200)
        }

    @Test
    fun `INV-AC-8224 at the exact stored expiry instant the call succeeds and one tick after it is 409`() =
        withService { served ->
            val clock = served.movableClock()
            val atExpiry = clock.freeze().plusSeconds(30)
            val exact = served.provisioned(expires = atExpiry.toString())
            val late = served.provisioned(expires = atExpiry.toString())
            clock.advance(Duration.ofSeconds(30))
            assertThat(clock.instant()).isEqualTo(atExpiry)
            assertThat(served.accept(exact.ref, exact.code, token("invitee-1")).status).isEqualTo(200)
            clock.advance(Duration.ofNanos(1_000))
            assertThat(clock.instant()).isEqualTo(atExpiry.plus(Duration.ofNanos(1_000)))
            served.refused(late, late.ref, late.code, token("invitee-2"), 409)
        }

    @Test
    fun `INV-AC-8224 an unknown invitation reference is 404 and nothing is stored`() =
        withService { served ->
            val invitation = served.provisioned()
            served.refused(invitation, "INVN-0000", invitation.code, token("invitee-1"), 404)
        }

    @Test
    fun `INV-AC-8224 a revoked grant is refused with 409 and stays revoked`() =
        withService { served ->
            val invitation = served.provisioned()
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use {
                    it.executeUpdate(
                        "update access.role_grant set status = 'REVOKED' where grant_ref = '${invitation.grantRef}'",
                    )
                }
            }
            val before = snapshot(invitation.tenant)
            val events = served.events()
            assertThat(served.accept(invitation.ref, invitation.code, token("invitee-1")).status).isEqualTo(409)
            assertThat(snapshot(invitation.tenant)).isEqualTo(before)
            assertThat(served.events()).isEqualTo(events)
        }

    @Test
    fun `INV-AC-8224 a call with no verified token is 401 SRT-003 and nothing is stored`() =
        withService { served ->
            val invitation = served.provisioned()
            val before = snapshot(invitation.tenant)
            val response = served.accept(invitation.ref, invitation.code, null)
            assertThat(response.status).isEqualTo(401)
            assertThat(response.contentAsString).contains("SRT-003")
            assertThat(snapshot(invitation.tenant)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8224 a sub in the reserved invitation space is 404 and changes nothing`() =
        withService { served ->
            val invitation = served.provisioned()
            served.refused(invitation, invitation.ref, invitation.code, token("invitation:${invitation.ref}"), 404)
        }

    @Test
    fun `INV-AC-8224 a token that carries another tenant is answered as a wrong code`() =
        withService { served ->
            val invitation = served.provisioned()
            served.refused(invitation, invitation.ref, invitation.code, token("invitee-1", newTenant()), 404)
        }

    // ---- the flow, end to end ----

    private fun ServedContext.grantRole(
        tenant: TenantId,
        auth: String,
        identity: String,
    ) = post(
        FirstAdministratorFixture.operation("grantRole"),
        viaConsole(auth),
        """{"granteeKind":"STAFF","identityRef":"$identity","role":"finance.viewer","scope":"tenant:$tenant",""" +
            """"validFrom":"${Instant.now().minusSeconds(3600)}"}""",
        UUID.randomUUID(),
    )

    @Test
    fun `INV-AC-8224 the invited person accepts and then grantRole is admitted, and before acceptance it is refused`() =
        withService { served ->
            val invitation = served.provisioned()
            val admin = token("invitee-1", invitation.tenant)
            assertThat(served.grantRole(invitation.tenant, admin, "IDN-OTHER").status).isEqualTo(403)
            assertThat(served.accept(invitation.ref, invitation.code, token("invitee-1")).status).isEqualTo(200)
            val admitted = served.grantRole(invitation.tenant, admin, "IDN-OTHER")
            assertThat(admitted.status).describedAs(admitted.contentAsString).isEqualTo(201)
        }

    @Test
    fun `INV-AC-8224 a second tenant's administrator code does not accept the first tenant's grant`() =
        withService { served ->
            val first = served.provisioned()
            val second = served.provisioned()
            assertThat(served.accept(first.ref, second.code, token("invitee-1")).status).isEqualTo(404)
            assertThat(grantRow(first, "status")).isEqualTo("PENDING")
            assertThat(served.accept(second.ref, second.code, token("invitee-2")).status).isEqualTo(200)
            assertThat(grantRow(first, "status")).isEqualTo("PENDING")
        }

    @Test
    fun `INV-AC-8224 a commercial admin with no grant cannot accept with a wrong code and holds no grant`() =
        withService { served ->
            val invitation = served.provisioned()
            val operator = token("operator-1")
            assertThat(served.accept(invitation.ref, "wrong", operator).status).isEqualTo(404)
            assertThat(served.grantRole(invitation.tenant, operator, "IDN-OTHER").status).isIn(401, 403)
            assertThat(adminGrants(invitation.tenant)).hasSize(1)
            val holders = rows("access.role_grant", invitation.tenant).map { json(it)["identity_ref"].asString() }
            assertThat(holders).containsExactly("invitation:${invitation.ref}")
            val identities = rows("access.identity", invitation.tenant).map { json(it)["identity_ref"].asString() }
            assertThat(identities).containsExactly("invitation:${invitation.ref}")
        }

    @Test
    fun `INV-AC-8224 the tenant lookup is a definer function owned by access_sys that only access_runtime executes`() =
        withService {
            val facts =
                BoundaryFixture.asSuperuser { c ->
                    c.createStatement().use { st ->
                        st
                            .executeQuery(
                                "select pg_get_userbyid(p.proowner) || '|' || p.prosecdef || '|' || " +
                                    "coalesce(p.proconfig::text, '') || '|' || " +
                                    "has_function_privilege('access_runtime', p.oid, 'EXECUTE') || '|' || " +
                                    "has_function_privilege('public', p.oid, 'EXECUTE') " +
                                    "from pg_proc p where p.pronamespace = 'access'::regnamespace " +
                                    "and p.proname = 'resolve_invitation_tenant'",
                            ).use { rs ->
                                rs.next()
                                rs.getString(1)
                            }
                    }
                }
            assertThat(facts).isEqualTo("access_sys|true|{\"search_path=pg_catalog, access, pg_temp\"}|true|false")
        }
}
