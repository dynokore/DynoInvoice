package com.dynokore.gst.controlPlane.application.commercial

import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.FirstAdministratorOutcome
import com.dynokore.gst.controlPlane.domain.commercial.PlanRef
import com.dynokore.gst.controlPlane.domain.commercial.ProvisioningStatus
import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.dynokore.gst.controlPlane.domain.commercial.TenantProvisioning
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** provisionTenant against fakes of its ports: the region list, the repository, tenancy-svc and the outbox. */
class ProvisionTenantTest {
    private companion object {
        const val SEEDED = "Tenant data is stored and processed in Singapore."
    }

    private val now = Instant.parse("2026-10-27T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val records = ArrayList<ProvisioningRecord>()
    private val calls = ArrayList<CreateTenantCall>()
    private val messages = ArrayList<String>()
    private val accessCalls = ArrayList<ProvisionFirstAdministratorCall>()
    private var creation: TenantCreation = TenantCreation.Created
    private var access: FirstAdministratorAnswer =
        FirstAdministratorAnswer.Issued("INVN-1", InvitationCode("code-secret-1"))
    private var counter = 0

    private val repository =
        object : ProvisioningRepository {
            override fun findByContract(contractReference: CommercialRef) =
                records.firstOrNull { it.provisioning.contractReference == contractReference }

            override fun findByKey(
                idempotencyKey: UUID,
                recordedBy: String,
            ) = records.firstOrNull { it.idempotencyKey == idempotencyKey }

            override fun save(
                record: ProvisioningRecord,
                recordedBy: String,
            ) {
                records += record
            }

            override fun markInvited(
                contractReference: CommercialRef,
                invitationRef: String,
            ) = update(contractReference) {
                it.copy(
                    status = ProvisioningStatus.CREATED,
                    invitationRef = invitationRef,
                    firstAdministratorOutcome = FirstAdministratorOutcome.INVITED,
                )
            }

            override fun markRefused(contractReference: CommercialRef) =
                update(contractReference) {
                    it.copy(
                        status = ProvisioningStatus.CREATED,
                        firstAdministratorOutcome = FirstAdministratorOutcome.TENANT_ALREADY_HAS_ADMINISTRATOR,
                    )
                }

            private fun update(
                contractReference: CommercialRef,
                change: (TenantProvisioning) -> TenantProvisioning,
            ) {
                val i = records.indexOfFirst { it.provisioning.contractReference == contractReference }
                records[i] = records[i].copy(provisioning = change(records[i].provisioning))
            }
        }
    private val regions =
        object : RegionList {
            override fun contains(key: RegionKey) = key.value == "SG"

            override fun statement(key: RegionKey) = SEEDED.takeIf { contains(key) }
        }
    private val roles = OperatorRoles { actor, role -> actor == "carol" && role == OperatorRole.COMMERCIAL_ADMIN }
    private val provision =
        ProvisionTenant(
            repository,
            regions,
            { call ->
                calls += call
                creation
            },
            InvitationDelivery(
                { contract, tenant, issued, issuedAt, expiresAt ->
                    val (ref, code) = issued.invitationRef to issued.invitationCode.value
                    "signed:$contract:$tenant:$ref:$code:$issuedAt:$expiresAt"
                },
                { _, email, ref, _ -> messages += "$email|$ref" },
                object : FirstAdministratorAccess {
                    override fun provision(call: ProvisionFirstAdministratorCall): FirstAdministratorAnswer {
                        accessCalls += call
                        return access
                    }

                    override fun reissue(call: ReissueFirstAdministratorCall) = FirstAdministratorAnswer.Unavailable
                },
                clock,
                Duration.ofDays(7),
            ),
            roles,
        ) { CommercialRef("TEN-${++counter}") }

    private val key = UUID.fromString("7d444840-9dc0-41a0-b0e8-6b4a0e9c9a11")

    private fun command(
        region: String = "SG",
        contract: String = "CTR-2026-0091",
        idempotencyKey: UUID = key,
        planRef: String = "PLAN-STANDARD",
    ) = ProvisionTenantCommand(
        ActorId("carol"),
        CommercialRef(contract),
        "Acme Holdings Pte Ltd",
        PlanRef(planRef),
        "admin@acme.example",
        RegionKey(region),
        idempotencyKey,
        "fp:$contract:$region:$planRef",
        CallerCredential("Bearer t"),
        RequestContext("corr", idempotencyKey.toString()),
    )

    private fun run(command: ProvisionTenantCommand): Pair<String?, TenantProvisioning?> {
        val state = provision.load(command)
        val prior = provision.priorResult(command, state)
        val refusal = if (prior == null) provision.unmetPrecondition(command, state) else null
        return when {
            prior != null -> null to prior
            refusal != null -> refusal to null
            else -> null to provision.perform(command, state)
        }
    }

    @Test
    fun `only commercial admin acting as herself provisions a tenant`() {
        provision.permits("carol", command()) shouldBe true
        provision.permits("mallory", command()) shouldBe false
        provision.permits("carol", command().copy(actor = ActorId("mallory"))) shouldBe false
    }

    @Test
    fun `INV-AC-8570 INV-AC-2293 a region not on the list is refused with CPL-003, nothing recorded`() {
        listOf("XX", "sg", "SG ", "Singapore").forEach { region ->
            run(command(region)).first shouldBe "CPL-003"
        }
        records.size shouldBe 0
        calls.size shouldBe 0
        messages.size shouldBe 0
    }

    @Test
    fun `INV-AC-8570 INV-AC-8572 INV-AC-1331 a Singapore request is recorded and every resource carries SG`() {
        val (refusal, provisioned) = run(command())
        refusal shouldBe null
        checkNotNull(provisioned)
        provisioned.status shouldBe ProvisioningStatus.CREATED
        provisioned.dataRegion.value shouldBe "SG"
        records
            .single()
            .provisioning.dataRegion.value shouldBe "SG"
        calls.single().dataRegion.value shouldBe "SG"
        calls.single().tenantRef shouldBe provisioned.tenantRef
        regions.statement(provisioned.dataRegion) shouldBe "Tenant data is stored and processed in Singapore."
    }

    @Test
    fun `INV-Q-8691 the planRef reaches createTenant character by character and a 65 character one is refused`() {
        val sixtyFour = "P".repeat(64)
        run(command(planRef = sixtyFour))
        calls.single().planRef.value shouldBe sixtyFour
        shouldThrow<IllegalArgumentException> { PlanRef("P".repeat(65)) }
        shouldThrow<IllegalArgumentException> { PlanRef("") }
    }

    @Test
    fun `the request is PENDING when createTenant outlasts its deadline and CREATED otherwise`() {
        creation = TenantCreation.Pending
        val pending = checkNotNull(run(command()).second)
        pending.status shouldBe ProvisioningStatus.PENDING
        records.single().provisioning.status shouldBe ProvisioningStatus.PENDING
        creation = TenantCreation.Created
        val created = checkNotNull(run(command(contract = "CTR-2", idempotencyKey = UUID.randomUUID())).second)
        created.status shouldBe ProvisioningStatus.CREATED
    }

    @Test
    fun `INV-AC-935 the first invitation is signed, expires after the call and is messaged`() {
        val provisioned = checkNotNull(run(command()).second)
        val expiry = now.plus(Duration.ofDays(7))
        provisioned.invitationRef shouldBe "INVN-1"
        provisioned.invitationExpiresAt shouldBe expiry
        provisioned.invitationExpiresAt shouldBeGreaterThan now
        messages.single() shouldBe
            "admin@acme.example|signed:CTR-2026-0091:${provisioned.tenantRef}:INVN-1:code-secret-1:$now:$expiry"
    }

    private fun accessKey() = ProvisionTenant.accessKeyOf(CommercialRef("CTR-2026-0091"))

    @Test
    fun `INV-AC-8224 provisionFirstAdministrator is called once with the minted tenantRef the email and the expiry`() {
        val provisioned = checkNotNull(run(command()).second)
        val call = accessCalls.single()
        call.tenantRef shouldBe provisioned.tenantRef
        call.administratorEmail shouldBe "admin@acme.example"
        call.invitationExpiresAt shouldBe now.plus(Duration.ofDays(7))
        call.idempotencyKey shouldBe accessKey()
        records.single().provisioning.firstAdministratorOutcome shouldBe FirstAdministratorOutcome.INVITED
    }

    @Test
    fun `INV-AC-8224 no access call while PENDING, the retry that reads CREATED sends it, same key`() {
        creation = TenantCreation.Pending
        run(command())
        accessCalls.size shouldBe 0
        messages.size shouldBe 0
        creation = TenantCreation.Created
        val retried = checkNotNull(run(command()).second)
        retried.status shouldBe ProvisioningStatus.CREATED
        records.size shouldBe 1
        accessCalls.single().idempotencyKey shouldBe accessKey()
        accessCalls.single().tenantRef shouldBe retried.tenantRef
        messages.size shouldBe 1
    }

    @Test
    fun `INV-AC-8224 a 409 of access-svc sends no invitation and the request records the outcome`() {
        access = FirstAdministratorAnswer.Refused
        val refused = checkNotNull(run(command()).second)
        messages.size shouldBe 0
        refused.firstAdministratorOutcome shouldBe FirstAdministratorOutcome.TENANT_ALREADY_HAS_ADMINISTRATOR
        records.single().provisioning.firstAdministratorOutcome shouldBe
            FirstAdministratorOutcome.TENANT_ALREADY_HAS_ADMINISTRATOR
        run(command()) shouldBe (null to refused)
        accessCalls.size shouldBe 1
    }

    @Test
    fun `INV-AC-8224 an unavailable access-svc keeps the request PENDING, the retry reuses the key`() {
        access = FirstAdministratorAnswer.Unavailable
        val pending = checkNotNull(run(command()).second)
        pending.status shouldBe ProvisioningStatus.PENDING
        records.single().provisioning.status shouldBe ProvisioningStatus.PENDING
        messages.size shouldBe 0
        access = FirstAdministratorAnswer.Issued("INVN-2", InvitationCode("code-secret-2"))
        val done = checkNotNull(run(command()).second)
        done.status shouldBe ProvisioningStatus.CREATED
        done.tenantRef shouldBe pending.tenantRef
        accessCalls.map { it.idempotencyKey }.toSet() shouldBe setOf(accessKey())
        accessCalls.map { it.invitationExpiresAt }.toSet() shouldBe setOf(pending.invitationExpiresAt)
        messages.single() shouldContain "code-secret-2"
    }

    @Test
    fun `INV-AC-8224 the stored request and its text never hold the invitation code`() {
        val provisioned = checkNotNull(run(command()).second)
        records.single().toString() shouldNotContain "code-secret-1"
        provisioned.toString() shouldNotContain "code-secret-1"
        InvitationCode("code-secret-1").toString() shouldNotContain "code-secret-1"
    }

    @Test
    fun `INV-AC-2293 INV-AC-8571 the same key with another region is refused with SRT-009, region stays SG`() {
        run(command())
        val changed = command(region = "XX").copy(fingerprint = "fp-other")
        run(changed).first shouldBe "SRT-009"
        records
            .single()
            .provisioning.dataRegion.value shouldBe "SG"
        calls.single().dataRegion.value shouldBe "SG"
    }

    @Test
    fun `INV-AC-2293 INV-AC-8571 a new key for the same contract returns the original body and changes nothing`() {
        val original = checkNotNull(run(command()).second)
        val again = run(command(region = "XX", idempotencyKey = UUID.randomUUID()))
        again.first shouldBe null
        again.second shouldBe original
        again.second?.dataRegion?.value shouldBe "SG"
        records.size shouldBe 1
        records
            .single()
            .provisioning.dataRegion.value shouldBe "SG"
        calls.size shouldBe 1
        messages.size shouldBe 1
    }

    @Test
    fun `a replay with the same key and request returns the original`() {
        val original = checkNotNull(run(command()).second)
        run(command()) shouldBe (null to original)
        records.size shouldBe 1
    }

    @Test
    fun `provisionTenant is audited and compensable`() {
        provision.audited shouldBe true
        provision.reversibility.name shouldBe "COMPENSABLE"
    }
}
