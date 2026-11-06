package com.dynokore.gst.controlPlane.application.commercial

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.PlanRef
import com.dynokore.gst.controlPlane.domain.commercial.ProvisioningStatus
import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.dynokore.gst.controlPlane.domain.commercial.TenantProvisioning
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** requestFirstAdministratorInvitationReissue against fakes of its ports (INV-AC-8281). */
class ReissueFirstAdministratorInvitationTest {
    private val now = Instant.parse("2026-11-20T08:00:00Z")
    private val contract = CommercialRef("CTR-2026-0091")
    private val key = UUID.fromString("7d444840-9dc0-41a0-b0e8-6b4a0e9c9a11")
    private var status = ProvisioningStatus.CREATED
    private var known = true
    private val attempts = HashMap<UUID, ReissueAttempt>()
    private val accessCalls = ArrayList<ReissueFirstAdministratorCall>()
    private val messages = ArrayList<String>()
    private var answer: FirstAdministratorAnswer =
        FirstAdministratorAnswer.Issued("INVN-NEW", InvitationCode("code-secret-new"))

    private fun record() =
        ProvisioningRecord(
            TenantProvisioning(
                contract,
                CommercialRef("TEN-1"),
                "Acme",
                PlanRef("P"),
                "admin@acme.example",
                RegionKey("SG"),
                status,
                "INVN-OLD",
                now.minus(Duration.ofDays(1)),
            ),
            UUID.randomUUID(),
            "fp",
        )

    private val provisionings =
        object : ProvisioningRepository {
            override fun findByContract(contractReference: CommercialRef) = record().takeIf { known }

            override fun findByKey(
                idempotencyKey: UUID,
                recordedBy: String,
            ) = null

            override fun save(
                record: ProvisioningRecord,
                recordedBy: String,
            ) = error("no provisioning is saved")

            override fun markInvited(
                contractReference: CommercialRef,
                invitationRef: String,
            ) = error("no provisioning is changed")

            override fun markRefused(contractReference: CommercialRef) = error("no provisioning is changed")
        }
    private val attemptRepository =
        object : ReissueAttemptRepository {
            override fun find(
                idempotencyKey: UUID,
                recordedBy: String,
            ) = attempts[idempotencyKey]

            override fun save(
                attempt: ReissueAttempt,
                recordedBy: String,
            ) {
                attempts[attempt.idempotencyKey] = attempt
            }

            override fun complete(
                idempotencyKey: UUID,
                recordedBy: String,
                invitationRef: String,
            ) {
                attempts[idempotencyKey] = attempts.getValue(idempotencyKey).copy(invitationRef = invitationRef)
            }
        }
    private var clockNow = now
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = clockNow
        }
    private val operation =
        ReissueFirstAdministratorInvitation(
            provisionings,
            attemptRepository,
            InvitationDelivery(
                { c, t, issued, issuedAt, expiresAt ->
                    "signed:$c:$t:${issued.invitationRef}:${issued.invitationCode.value}:$issuedAt:$expiresAt"
                },
                { _, email, token, _ -> messages += "$email|$token" },
                object : FirstAdministratorAccess {
                    override fun provision(call: ProvisionFirstAdministratorCall) = error("not used")

                    override fun reissue(call: ReissueFirstAdministratorCall): FirstAdministratorAnswer {
                        accessCalls += call
                        return answer
                    }
                },
                clock,
                Duration.ofDays(14),
            ),
            // carol holds the scoped key; dave holds the role commercial.admin in the user store but is denied the key.
            OperatorPermissions { actor, key ->
                actor == "carol" && key == ReissueFirstAdministratorInvitation.PERMISSION_KEY
            },
        )

    private fun command(
        actor: String = "carol",
        idempotencyKey: UUID = key,
    ) = ReissueInvitationCommand(ActorId(actor), contract, idempotencyKey, RequestContext("corr", "cause"))

    private fun run(command: ReissueInvitationCommand): Pair<String?, ReissueResult?> {
        val state = operation.load(command)
        val prior = operation.priorResult(command, state)
        val refusal = if (prior == null) operation.unmetPrecondition(command, state) else null
        return when {
            prior != null -> null to prior
            refusal != null -> refusal to null
            else -> null to operation.perform(command, state)
        }
    }

    @Test
    fun `INV-AC-8281 a CREATED request sends one reissue and one new signed invitation carrying the ref and code`() {
        val (refusal, result) = run(command())
        refusal shouldBe null
        val expiry = now.plus(Duration.ofDays(14))
        result shouldBe ReissueResult.Issued(contract, CommercialRef("TEN-1"), "INVN-NEW", expiry)
        accessCalls.single() shouldBe ReissueFirstAdministratorCall(CommercialRef("TEN-1"), expiry, key)
        messages.single() shouldBe "admin@acme.example|signed:$contract:TEN-1:INVN-NEW:code-secret-new:$now:$expiry"
        result.toString() shouldNotContain "code-secret-new"
        attempts.values.toString() shouldNotContain "code-secret-new"
    }

    @Test
    fun `INV-AC-8281 a repeat with the same key returns the same response and sends nothing more`() {
        val first = run(command()).second
        clockNow = now.plusSeconds(60)
        run(command()) shouldBe (null to first)
        accessCalls.size shouldBe 1
        messages.size shouldBe 1
    }

    @Test
    fun `INV-AC-8281 a request that does not read CREATED or is unknown is refused and nothing is sent`() {
        status = ProvisioningStatus.PENDING
        run(command()).first shouldBe ReissueFirstAdministratorInvitation.NOT_CREATED
        known = false
        run(command()).first shouldBe ReissueFirstAdministratorInvitation.NOT_FOUND
        accessCalls.size shouldBe 0
        messages.size shouldBe 0
    }

    @Test
    fun `INV-AC-8281 a 409 of access-svc sends no invitation`() {
        answer = FirstAdministratorAnswer.Refused
        run(command()).second shouldBe ReissueResult.Refused
        messages.size shouldBe 0
    }

    @Test
    fun `INV-AC-8281 any other failure of access-svc sends no invitation and is the failed result`() {
        answer = FirstAdministratorAnswer.Failed
        run(command()).second shouldBe ReissueResult.Failed
        messages.size shouldBe 0
    }

    @Test
    fun `INV-AC-8281 an unreachable access-svc sends no invitation and the retry sends the same access request`() {
        answer = FirstAdministratorAnswer.Unavailable
        run(command()).second shouldBe ReissueResult.Unavailable
        messages.size shouldBe 0
        clockNow = now.plusSeconds(90)
        answer = FirstAdministratorAnswer.Issued("INVN-NEW", InvitationCode("code-secret-new"))
        run(command()).second shouldBe
            ReissueResult.Issued(contract, CommercialRef("TEN-1"), "INVN-NEW", now.plus(Duration.ofDays(14)))
        accessCalls.size shouldBe 2
        accessCalls[1] shouldBe accessCalls[0]
        messages.single() shouldContain "code-secret-new"
    }

    @Test
    fun `INV-AC-8281 only a caller permitted the scoped key acting as herself is admitted`() {
        operation.permits("carol", command()) shouldBe true
        operation.permits("mallory", command("mallory")) shouldBe false
        operation.permits("dave", command("dave")) shouldBe false // holds commercial.admin, denied the key
        operation.permits("carol", command("mallory")) shouldBe false
        operation.operationId shouldBe "requestFirstAdministratorInvitationReissue"
    }

    @Test
    fun `INV-AC-8281 the key of another contract is a different request`() {
        run(command())
        val other = command().copy(contractReference = CommercialRef("CTR-OTHER"))
        run(other).first shouldBe ReissueFirstAdministratorInvitation.DIFFERENT_REQUEST
    }
}
