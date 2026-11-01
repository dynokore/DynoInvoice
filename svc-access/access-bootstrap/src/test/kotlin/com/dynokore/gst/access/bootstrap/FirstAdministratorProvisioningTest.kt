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
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.provision
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
 * INV-AC-8224, the provisioning half (INV-T-8226): `provisionFirstAdministrator` and
 * `provisionInstallationFirstAdministrator` through the real context over a real PostgreSQL with forced row-level
 * security, the generated caller check and the request filters in front of the controller. Test doubles: the token
 * verifier and the end-user resolver of [com.dynokore.gst.access.bootstrap.served.ServedDoubles].
 */
private val SHAPE_COLUMNS =
    setOf("role", "scope_level", "status", "grant_source", "invited_email", "code_key_version", "grantee_kind")

class FirstAdministratorProvisioningTest {
    private fun ServedContext.first(
        tenant: TenantId,
        key: UUID = UUID.randomUUID(),
        auth: String? = OPERATOR_TOKEN,
    ) = provision(FIRST, CONTROL_PLANE, tenant.toString(), key, auth)

    @Test
    fun `INV-AC-8224 the first call stores one pending tenant admin grant, its identity and its invitation`() =
        withService { served ->
            val tenant = newTenant()
            val response = served.first(tenant)
            assertThat(response.status).isEqualTo(201)
            val body = json(response)
            assertThat(body["status"].asString()).isEqualTo("PENDING")
            val ref = body["invitationRef"].asString()
            val code = body["invitationCode"].asString()
            assertThat(ref).startsWith("INVN-").hasSizeLessThanOrEqualTo(48)
            assertThat(code).isEqualTo(expectedCode(tenant, ref))
            val grant = rows("access.role_grant", tenant).single()
            val identity = rows("access.identity", tenant).single()
            val invitation = rows("access.first_administrator_invitation", tenant).single()
            val g = FirstAdministratorFixture.JSON.readTree(grant)
            assertThat(g["grant_ref"].asString()).isEqualTo(body["grantRef"].asString())
            assertThat(g["role"].asString()).isEqualTo("tenant.admin")
            assertThat(g["scope_level"].asString()).isEqualTo("TENANT")
            assertThat(g["scope_ref"].asString()).isEqualTo(tenant.toString())
            assertThat(g["status"].asString()).isEqualTo("PENDING")
            assertThat(g["grant_source"].asString()).isEqualTo("BY_HAND")
            assertThat(g["identity_ref"].asString()).isEqualTo("invitation:$ref")
            val i = FirstAdministratorFixture.JSON.readTree(identity)
            assertThat(i["identity_ref"].asString()).isEqualTo("invitation:$ref")
            assertThat(i["grantee_kind"].asString()).isEqualTo("EXTERNAL")
            val v = FirstAdministratorFixture.JSON.readTree(invitation)
            assertThat(v["invitation_ref"].asString()).isEqualTo(ref)
            assertThat(v["grant_ref"].asString()).isEqualTo(g["grant_ref"].asString())
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
                served.provision(FIRST, CONTROL_PLANE, tenant.toString(), key, OPERATOR_TOKEN, "other@example.sg")
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

    // ---- the 409 rule ----

    private fun refusedFor(
        seed: (TenantId, ServedContext) -> Unit,
        expected: Int,
    ) = withService { served ->
        val tenant = newTenant()
        seed(tenant, served)
        val before = snapshot(tenant)
        val response = served.first(tenant)
        assertThat(response.status).isEqualTo(expected)
        if (expected == 409) assertThat(snapshot(tenant)).isEqualTo(before)
    }

    @Test
    fun `INV-AC-8224 an ACTIVE tenant admin row refuses the call with 409`() =
        refusedFor({ t, _ -> seedAdminRow(t, "ACTIVE") }, 409)

    @Test
    fun `INV-AC-8224 a PENDING tenant admin row refuses the call with 409`() =
        refusedFor({ t, _ -> seedAdminRow(t, "PENDING") }, 409)

    @Test
    fun `INV-AC-8224 a tenant admin row created through grantRole refuses the call with 409`() =
        refusedFor({ t, served ->
            val context = served.application
            val tenants = context.getBean(TenantContext::class.java)
            tenants.bind(t, "IDN-GRANTER") {
                context.getBean(GrantRoleUseCase::class.java).handle(
                    GrantRoleCommand(
                        tenant = TenantRef(t.toString()),
                        actor = "IDN-GRANTER",
                        identity = IdentityRef("IDN-VIA-GRANT-ROLE"),
                        granteeKind = GranteeKind.STAFF,
                        roleWire = Role.TENANT_ADMIN.wire,
                        scope = Scope.Tenant(TenantRef(t.toString())),
                        window = ValidityWindow(context.getBean(Clock::class.java).instant(), null),
                    ),
                )
            }
            assertThat(adminGrants(t)).hasSize(1)
        }, 409)

    @Test
    fun `INV-AC-8224 a tenant admin row from a directory group mapping refuses the call with 409`() =
        refusedFor({ t, _ -> seedAdminRow(t, "ACTIVE", source = "DIRECTORY_GROUPS") }, 409)

    @Test
    fun `INV-AC-8224 a tenant admin row of any scope level refuses the call with 409`() =
        refusedFor({ t, _ -> seedAdminRow(t, "ACTIVE", scopeLevel = "ENTITY", scopeRef = "ENT-1") }, 409)

    @Test
    fun `INV-AC-8224 a tenant whose only tenant admin rows are REVOKED is admitted`() =
        refusedFor({ t, _ -> seedAdminRow(t, "REVOKED") }, 201)

    @Test
    fun `INV-AC-8224 a tenant admin row of another tenant does not block the call`() =
        withService { served ->
            val other = newTenant()
            val tenant = newTenant()
            seedAdminRow(other, "ACTIVE")
            seedAdminRow(other, "PENDING")
            assertThat(served.first(tenant).status).isEqualTo(201)
            assertThat(adminGrants(other)).hasSize(2)
            assertThat(adminGrants(tenant)).hasSize(1)
        }

    @Test
    fun `INV-AC-8224 two concurrent calls for one tenant with two keys store one grant and one is 409`() =
        withService { served ->
            val tenant = newTenant()
            val statuses = concurrently(2) { served.first(tenant, UUID.randomUUID()).status }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409)
            assertThat(adminGrants(tenant)).hasSize(1)
            assertThat(rows("access.first_administrator_invitation", tenant)).hasSize(1)
        }

    @Test
    fun `INV-AC-8224 two concurrent calls with the same key store one grant and answer one invitation reference`() =
        withService { served ->
            val tenant = newTenant()
            val key = UUID.randomUUID()
            val answers = concurrently(2) { served.first(tenant, key) }
            assertThat(answers.map { it.status }).containsOnly(201)
            assertThat(answers.map { json(it)["invitationRef"].asString() }.toSet()).hasSize(1)
            assertThat(answers.map { json(it)["invitationCode"].asString() }.toSet()).hasSize(1)
            assertThat(adminGrants(tenant)).hasSize(1)
            assertThat(rows("access.first_administrator_invitation", tenant)).hasSize(1)
        }

    private fun <T> concurrently(
        count: Int,
        call: () -> T,
    ): List<T> {
        val pool = Executors.newFixedThreadPool(count)
        try {
            val ready = CountDownLatch(count)
            val go = CountDownLatch(1)
            val futures =
                (1..count).map {
                    pool.submit(
                        Callable {
                            ready.countDown()
                            go.await()
                            call()
                        },
                    )
                }
            ready.await()
            go.countDown()
            return futures.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    // ---- the caller ----

    private fun refusedCall(
        caller: String?,
        operation: String,
        status: Int,
        code: String?,
        auth: String? = OPERATOR_TOKEN,
    ) = withService { served ->
        val tenant = newTenant()
        val before = snapshot(tenant)
        val events = served.stored()
        val response = served.provision(operation, caller, tenant.toString(), auth = auth)
        assertThat(response.status).isEqualTo(status)
        if (code != null) assertThat(response.contentAsString).contains(code)
        assertThat(snapshot(tenant)).isEqualTo(before)
        assertThat(served.stored()).isEqualTo(events)
    }

    @Test
    fun `INV-AC-8224 the look-alike service is refused with 403 SRT-004 and stores nothing`() =
        refusedCall(FirstAdministratorFixture.LOOK_ALIKE, FIRST, 403, "SRT-004")

    @Test
    fun `INV-AC-8224 the installer workload is refused on provisionFirstAdministrator`() =
        refusedCall(INSTALLER, FIRST, 403, "SRT-004")

    @Test
    fun `INV-AC-8224 control-plane-svc is refused on provisionInstallationFirstAdministrator`() =
        refusedCall(CONTROL_PLANE, INSTALLATION, 403, "SRT-004", auth = null)

    @Test
    fun `INV-AC-8224 another service is refused on both operations`() {
        refusedCall(OTHER, FIRST, 403, "SRT-004")
        refusedCall(OTHER, INSTALLATION, 403, "SRT-004", auth = null)
    }

    @Test
    fun `INV-AC-8224 a person with a tenant admin token and another service's workload token is refused with 403`() =
        refusedCall(OTHER, FIRST, 403, "SRT-004", auth = USER_A_TOKEN)

    @Test
    fun `INV-AC-8224 a person alone, with no workload token, is refused with 401`() {
        refusedCall(null, FIRST, 401, null, auth = USER_A_TOKEN)
        refusedCall(null, INSTALLATION, 401, null, auth = null)
    }

    // ---- the installer form ----

    @Test
    fun `INV-AC-8224 the installer operation stores the same rows as the first and repeats and refuses alike`() =
        withService { served ->
            val viaPlane = newTenant()
            val viaInstaller = newTenant()
            val a = served.first(viaPlane)
            val key = UUID.randomUUID()
            val b = served.provision(INSTALLATION, INSTALLER, viaInstaller.toString(), key, auth = null)
            assertThat(b.status).isEqualTo(a.status).isEqualTo(201)
            assertThat(json(b)["status"].asString()).isEqualTo("PENDING")
            assertThat(json(b)["invitationCode"].asString())
                .isEqualTo(expectedCode(viaInstaller, json(b)["invitationRef"].asString()))

            fun shape(tenant: TenantId) =
                FirstAdministratorFixture.TABLES.associateWith { table ->
                    rows(table, tenant).map {
                        FirstAdministratorFixture.JSON.readTree(it).let { node ->
                            node
                                .propertyNames()
                                .asSequence()
                                .filter { n -> n in SHAPE_COLUMNS }
                                .associateWith { n -> node[n].asString() }
                        }
                    }
                }
            val planeShape = shape(viaPlane)
            val installerShape = shape(viaInstaller)
            assertThat(installerShape["access.role_grant"]).isEqualTo(planeShape["access.role_grant"])
            assertThat(installerShape["access.identity"]).isEqualTo(planeShape["access.identity"])
            assertThat(installerShape["access.first_administrator_invitation"])
                .isEqualTo(planeShape["access.first_administrator_invitation"])
            val before = snapshot(viaInstaller)
            val repeat = served.provision(INSTALLATION, INSTALLER, viaInstaller.toString(), key, auth = null)
            assertThat(repeat.status).isEqualTo(201)
            assertThat(json(repeat)["invitationRef"].asString()).isEqualTo(json(b)["invitationRef"].asString())
            assertThat(json(repeat)["invitationCode"].asString()).isEqualTo(json(b)["invitationCode"].asString())
            assertThat(snapshot(viaInstaller)).isEqualTo(before)
            val refused = served.provision(INSTALLATION, INSTALLER, viaInstaller.toString(), auth = null)
            assertThat(refused.status).isEqualTo(409)
            assertThat(snapshot(viaInstaller)).isEqualTo(before)
            val held = newTenant()
            seedAdminRow(held, "ACTIVE")
            assertThat(served.provision(INSTALLATION, INSTALLER, held.toString(), auth = null).status).isEqualTo(409)
        }

    // ---- the tenant binding ----

    @Test
    fun `INV-AC-8224 a tenantless operator token is bound to tenantRef and writes a row of that tenant only`() =
        withService { served ->
            val tenant = newTenant()
            val other = newTenant()
            assertThat(served.first(tenant, auth = OPERATOR_TOKEN).status).isEqualTo(201)
            assertThat(adminGrants(tenant)).hasSize(1)
            assertThat(adminGrants(other)).isEmpty()
            val audit =
                served.application
                    .getBean(CapturedEvents::class.java)
                    .envelopes
                    .filter {
                        it.type.contains("AuditEntryRecorded") && it.dataJson.contains("provisionFirstAdministrator")
                    }
            assertThat(audit).isNotEmpty
            assertThat(audit.last().dataJson).contains("operator-1")
        }

    @Test
    fun `INV-AC-8224 a token of another tenant is refused with 403 and no row exists in either tenant`() =
        withService { served ->
            val a = com.dynokore.gst.access.bootstrap.served.TENANT_A
            val b = com.dynokore.gst.access.bootstrap.served.TENANT_B
            val before = snapshot(a) to snapshot(b)
            val response = served.first(a, auth = USER_B_TOKEN)
            assertThat(response.status).isEqualTo(403)
            assertThat(snapshot(a) to snapshot(b)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8224 a token of the same tenant is bound to it`() =
        withService { served ->
            val a = com.dynokore.gst.access.bootstrap.served.TENANT_A
            // the shared tenant A may already hold an admin from another test run of this database: use the status
            val response = served.first(a, auth = USER_A_TOKEN)
            assertThat(response.status).isIn(201, 409)
        }

    @Test
    fun `INV-AC-8224 a tenantRef that is not the exact lower-case tenant id is 400 and stores nothing`() =
        withService { served ->
            val tenant = newTenant()
            val before = served.stored()
            listOf(
                tenant.toString().uppercase(),
                tenant.toString() + " ",
                "not-a-tenant",
                "ffffffff-ffff-ffff-ffff-ffffffffffff",
                "",
            ).forEach { text ->
                val response = served.provision(FIRST, CONTROL_PLANE, text)
                assertThat(response.status).describedAs("tenantRef '$text'").isEqualTo(400)
            }
            assertThat(served.stored()).isEqualTo(before)
        }

    // ---- fail closed on the code key ----

    @Test
    fun `INV-AC-8224 a store holding only the cursor key makes the first call 503 SRT-011 and stores nothing`() =
        withService(mounted = FirstAdministratorFixture.mount(emptyMap())) { served ->
            val tenant = newTenant()
            val response = served.first(tenant)
            assertThat(response.status).isEqualTo(503)
            assertThat(response.contentAsString).contains("SRT-011")
            assertThat(snapshot(tenant).values.flatten()).isEmpty()
        }

    @Test
    fun `INV-AC-8224 a key shorter than 32 bytes is unavailable`() =
        withService(mounted = FirstAdministratorFixture.mount(mapOf("v1" to "too-short"))) { served ->
            val tenant = newTenant()
            assertThat(served.first(tenant).status).isEqualTo(503)
            assertThat(snapshot(tenant).values.flatten()).isEmpty()
        }

    @Test
    fun `INV-AC-8224 a repeat with a missing key version is 503 and a rotation keeps the old code`() {
        val v1 = TestSecrets.INVITATION_KEY
        val v2 = "test-invitation-code-key-v2-0123456789abcdef"
        val tenant = newTenant()
        val key = UUID.randomUUID()
        var firstBody = ""
        var firstRef = ""
        withService(mounted = FirstAdministratorFixture.mount(mapOf("v1" to v1))) { served ->
            val first = served.first(tenant, key)
            assertThat(first.status).isEqualTo(201)
            firstBody = first.contentAsString
            firstRef = json(first)["invitationRef"].asString()
        }
        // the key of the row's version is gone: the repeat fails closed
        val onlyV2 = FirstAdministratorFixture.mount(mapOf("v2" to v2))
        withService(mounted = onlyV2, version = "v2") { served ->
            val before = snapshot(tenant)
            val repeat = served.first(tenant, key)
            assertThat(repeat.status).isEqualTo(503)
            assertThat(repeat.contentAsString)
                .contains("SRT-011")
                .doesNotContain(json(firstBody)["invitationCode"].asString())
            assertThat(snapshot(tenant)).isEqualTo(before)
        }
        // both versions are mounted and the current one is v2
        val both = FirstAdministratorFixture.mount(mapOf("v1" to v1, "v2" to v2))
        withService(mounted = both, version = "v2") { served ->
            val repeat = served.first(tenant, key)
            assertThat(repeat.status).isEqualTo(201)
            assertThat(json(repeat)["invitationCode"].asString()).isEqualTo(expectedCode(tenant, firstRef, v1))
            val fresh = newTenant()
            val created = served.first(fresh)
            assertThat(created.status).isEqualTo(201)
            val stored = rows("access.first_administrator_invitation", fresh).single()
            val row = FirstAdministratorFixture.JSON.readTree(stored)
            assertThat(row["code_key_version"].asString()).isEqualTo("v2")
            assertThat(json(created)["invitationCode"].asString())
                .isEqualTo(expectedCode(fresh, json(created)["invitationRef"].asString(), v2))
        }
    }

    // ---- the pending-grant identity ----

    @Test
    fun `INV-AC-8224 a revoked pending grant keeps its identity row`() =
        withService { served ->
            val tenant = newTenant()
            val grantRef = json(served.first(tenant))["grantRef"].asString()
            val context = served.application
            context.getBean(TenantContext::class.java).bind(tenant, "IDN-REVOKER") {
                context.getBean(RevokeGrantUseCase::class.java).handle("IDN-REVOKER", GrantRef(grantRef))
            }
            assertThat(rows("access.role_grant", tenant).single()).contains("\"status\": \"REVOKED\"")
            assertThat(rows("access.identity", tenant)).hasSize(1)
            // a REVOKED-only tenant is admitted again
            assertThat(served.first(tenant).status).isEqualTo(201)
        }

    @Test
    fun `INV-AC-8224 a pending grant authorises nothing for the invitation identity`() =
        withService { served ->
            val tenant = newTenant()
            val ref = json(served.first(tenant))["invitationRef"].asString()
            val context = served.application
            val grants = context.getBean(GrantRepository::class.java)
            val tx = context.getBean(TransactionRunner::class.java)
            val held =
                context.getBean(TenantContext::class.java).bind(tenant, "IDN-READER") {
                    tx.inTransaction { grants.findByIdentity(IdentityRef("invitation:$ref")) }
                }
            assertThat(held).hasSize(1)
            val now = context.getBean(Clock::class.java).instant()
            val permissions = FixtureMatrix.matrix.permissionsOf(Role.TENANT_ADMIN)
            assertThat(permissions).isNotEmpty
            held.forEach { grant ->
                permissions.forEach { permission ->
                    val target = Target(EntityRef("ENT-1"))
                    val membership = EntityMembership(TenantRef(tenant.toString()), null)
                    assertThat(grant.authorises(permission, target, membership, FixtureMatrix.matrix, now)).isFalse()
                }
            }
        }
}

/** INV-Q-14264 A: the prefix `invitation:` is refused as an identity of grantRole, exactly and case-sensitively. */
class ReservedInvitationIdentityTest {
    private fun grantRole(
        identity: String,
        block: (Result<Int>, TenantId) -> Unit,
    ) = withService { served ->
        val tenant = newTenant()
        val context = served.application
        Seeds.grant(context, tenant, SeedSpec("IDN-MGR", Role.TENANT_ADMIN))
        val api = context.getBean(com.dynokore.gst.access.adapters.http.GrantsController::class.java)
        val result =
            runCatching {
                context.getBean(TenantContext::class.java).bind(tenant, "IDN-MGR") {
                    api
                        .grantRole(
                            UUID.randomUUID(),
                            com.dynokore.gst.access.contract.model.GrantRequest(
                                com.dynokore.gst.access.contract.model.GrantRequest.GranteeKind.STAFF,
                                identity,
                                "auditor",
                                "tenant:$tenant",
                                java.time.LocalDate
                                    .now()
                                    .minusDays(1),
                                java.time.LocalDate
                                    .now()
                                    .plusDays(10),
                            ),
                        ).statusCode
                        .value()
                }
            }
        block(result, tenant)
    }

    @Test
    fun `INV-AC-8224 grantRole with the reserved prefix is 400 and stores nothing`() =
        grantRole("invitation:INVN-X") { result, tenant ->
            val failure = result.exceptionOrNull() as com.dynokore.gst.access.adapters.http.ApiRefusal
            assertThat(failure.code.httpStatus).isEqualTo(400)
            assertThat(failure.detail).isEqualTo("identityRef is invalid")
            assertThat(rows("access.identity", tenant).filter { it.contains("INVN-X") }).isEmpty()
            assertThat(rows("access.role_grant", tenant).filter { it.contains("INVN-X") }).isEmpty()
        }

    @Test
    fun `INV-AC-8224 the look-alike prefixes are not refused by the prefix rule`() {
        listOf("invitations:INVN-X", "Invitation:INVN-X", " invitation:INVN-X").forEach { identity ->
            grantRole(identity) { result, _ -> assertThat(result.getOrNull()).describedAs(identity).isEqualTo(201) }
        }
    }
}
