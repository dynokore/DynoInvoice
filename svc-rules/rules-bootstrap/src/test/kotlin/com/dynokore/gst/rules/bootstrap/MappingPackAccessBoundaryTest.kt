package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.application.HolderCopyPort
import com.dynokore.gst.rules.application.HolderCopyResult
import com.dynokore.gst.rules.application.PackActionAuthority
import com.dynokore.gst.rules.application.RateSourcePort
import com.dynokore.gst.rules.served.USER_A_TOKEN
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.http.HttpResponse
import java.util.UUID

private const val CONSOLE_CALLER = "console-bff"
private const val ENTITY_REF = "E-1"
private const val PACKS_PATH = "/internal/v1/rules/mapping-packs"
private const val USER_B = "Bearer token-user-b"
private const val HELD_DETAIL = "the client's acknowledgement is outstanding"

/** The stub of access-svc of the test classes below; the URL reaches the service as `rules.access-svc-url`. */
private val accessStub = AccessStubServer()

/** The permission, rate-source and holder-copy ports allow; the three ports of access-svc are the production ones. */
@TestConfiguration(proxyBeanMethods = false)
@Import(VersionedBuild::class)
class AccessStubbedPorts {
    @Bean
    fun packActionAuthority(): PackActionAuthority = PackActionAuthority { _, _, _ -> true }

    @Bean
    fun rateSourcePort(): RateSourcePort = RateSourcePort { _, _ -> emptyList() }

    @Bean
    fun holderCopyPort(): HolderCopyPort = HolderCopyPort { HolderCopyResult.Written }
}

private fun allow(
    soleOperator: Boolean = false,
    ackRef: String? = null,
    option: String = "STAFF_APPROVER",
    outstanding: List<String> = emptyList(),
): String =
    """{"decision":"ALLOW","soleOperator":$soleOperator,""" +
        """"outstanding":[${outstanding.joinToString(",") { "\"$it\"" }}],""" +
        """"option":"$option"${ackRef?.let { ""","soleOperatorAckRef":"$it"""" }.orEmpty()}}"""

private fun deny(reason: String): String =
    """{"decision":"DENY","soleOperator":false,"outstanding":[],"reasonCode":"$reason"}"""

private fun fact(distinct: String) = """{"distinctTaxApprover":$distinct,"recordedAt":"2026-10-25T08:00:00Z"}"""

private fun acknowledgement(
    version: String,
    client: String = ENTITY_REF,
) = """{"packVersion":"$version","clientRef":"$client","acknowledgedAt":"2026-09-27T02:00:00Z"}"""

/**
 * rules-svc against the stub of access-svc, on real PostgreSQL, in the private form: the approval decision, the
 * installation fact at activation and the hold for the client's acknowledgement (INV-T-10700; INV-Q-10695 R3, R5, R8).
 */
@SpringBootTest(
    classes = [MappingDoubles::class, AccessStubbedPorts::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "service-runtime.idempotency.schema=rules",
        "gst.form=private",
        "service-runtime.workload-identity.callers[0].service=$CONSOLE_CALLER",
        "service-runtime.workload-identity.callers[0].subject=subject-of-$CONSOLE_CALLER",
    ],
)
class MappingPackAccessBoundaryTest(
    @org.springframework.boot.test.web.server.LocalServerPort port: Int,
) : MappingPackSupport(port) {
    private var counter = 0

    @AfterEach
    fun resetStub() = accessStub.reset()

    private fun label() = "2026.${++counter}-${UUID.randomUUID().toString().take(6)}"

    /** A SUBMITTED pack authored by user-a with a completed dry run; returns its id and its version label. */
    private fun submitted(): Pair<String, String> {
        val label = label()
        val packId = draft(label)
        ok(mapCode(packId), 201)
        deltaRow(TENANT_A_ID, label, "COMPLETE")
        ok(submit(packId))
        return packId to label
    }

    private fun approvalRows(label: String) =
        rows(
            TENANT_A_ID,
            "select * from rules.mapping_pack_approval where pack_version = '$label' order by approved_at",
        )

    private fun statusOf(packId: String) =
        value(TENANT_A_ID, "select status from rules.pack_registry where pack_id = ?", packId)

    /** An APPROVED pack (approved by user-b under [body]) ready to be activated. */
    private fun approved(body: String = allow()): Pair<String, String> {
        val (packId, label) = submitted()
        accessStub.replies[AccessStubServer.DECISION] = AccessStubServer.Reply(200, body)
        ok(approve(packId))
        return packId to label
    }

    private fun refusedWithNothingStored(
        answer: HttpResponse<String>,
        status: Int,
        code: String,
        before: Map<String, List<String>>,
    ) {
        withClue(answer.body()) {
            answer.statusCode() shouldBe status
            json(answer).get("code").asString() shouldBe code
        }
        snapshot() shouldBe before
    }

    // ---- approvePack asks access-svc ----

    @Test
    fun `approvePack sends one decidePackApproval request with the facts of the pack and the verified user`() {
        val (packId, label) = submitted()
        value(
            TENANT_A_ID,
            "insert into rules.mapping_pack_approval (tenant_id, pack_type, pack_version, approved_by, " +
                "dry_run_delta_id, sole_operator, option_key, outstanding_client_acknowledgement, " +
                "outstanding_client_approval) select tenant_id, 'MAPPING', pack_version, 'user-z', " +
                "(select id from rules.dry_run_delta where pack_version = ?), false, 'STAFF_APPROVER', false, false " +
                "from rules.pack_registry where pack_id = ? returning approved_by",
            label,
            packId,
        )
        accessStub.replies[AccessStubServer.DECISION] = AccessStubServer.Reply(200, allow())
        ok(approve(packId))
        val sent = accessStub.requestsTo(AccessStubServer.DECISION).single()
        sent.method shouldBe "GET"
        sent.query shouldBe
            mapOf(
                "entityRef" to listOf(ENTITY_REF),
                "packRef" to listOf(packId),
                "packVersion" to listOf(label),
                "authorRef" to listOf("user-a"),
                "approverRef" to listOf("user-b"),
                "priorApproverRef" to listOf("user-z"),
            )
    }

    @Test
    fun `an ALLOW stores one approval entry and the status follows the outstanding steps`() {
        val (packId, label) = submitted()
        accessStub.replies[AccessStubServer.DECISION] =
            AccessStubServer.Reply(
                200,
                allow(outstanding = listOf("CLIENT_APPROVAL"), option = "CLIENT_APPROVER_ALONE"),
            )
        val answer = ok(approve(packId))
        answer.get("status").asString() shouldBe "SUBMITTED"
        val row = approvalRows(label).single()
        row["approved_by"] shouldBe "user-b"
        row["sole_operator"] shouldBe false
        row["option_key"] shouldBe "CLIENT_APPROVER_ALONE"
        row["outstanding_client_approval"] shouldBe true
        row["outstanding_client_acknowledgement"] shouldBe false

        val (second, secondLabel) = submitted()
        accessStub.replies[AccessStubServer.DECISION] =
            AccessStubServer.Reply(
                200,
                allow(outstanding = listOf("CLIENT_ACKNOWLEDGEMENT"), option = "AGENT_SECOND_PERSON_CLIENT_ACK"),
            )
        ok(approve(second)).get("status").asString() shouldBe "APPROVED"
        approvalRows(secondLabel).single()["outstanding_client_acknowledgement"] shouldBe true
    }

    @Test
    fun `INV-AC-9555 the author's approvals under the acknowledgement carry the fact, a distinct one's does not`() {
        val sole = allow(soleOperator = true, ackRef = "ack-1", option = "SOLE_OPERATOR_ACK")
        val (p1, l1) = submitted()
        val (p2, l2) = submitted()
        val l3 = label()
        val p3 = draft(l3, USER_B)
        ok(mapCode(p3), 201)
        deltaRow(TENANT_A_ID, l3, "COMPLETE")
        ok(submit(p3))
        accessStub.replies[AccessStubServer.DECISION] = AccessStubServer.Reply(200, sole)
        ok(approve(p1, USER_A_TOKEN))
        ok(approve(p2, USER_A_TOKEN))
        accessStub.replies[AccessStubServer.DECISION] = AccessStubServer.Reply(200, allow())
        ok(approve(p3, USER_A_TOKEN))
        listOf(l1, l2, l3).map { approvalRows(it).single()["sole_operator"] } shouldBe listOf(true, true, false)
        approvalRows(l1).single()["sole_operator_ack_ref"] shouldBe "ack-1"
        val sent = accessStub.requestsTo(AccessStubServer.DECISION)
        sent.size shouldBe 3
        sent.map { it.query["approverRef"] } shouldBe List(3) { listOf("user-a") }
        sent.map { it.query["authorRef"] } shouldBe listOf(listOf("user-a"), listOf("user-a"), listOf("user-b"))
    }

    @Test
    fun `the author without the acknowledgement reaches the stub and is refused 403 SRT-004 with nothing stored`() {
        val (packId, _) = submitted()
        val before = snapshot()
        accessStub.replies[AccessStubServer.DECISION] = AccessStubServer.Reply(200, deny("NO_SOLE_OPERATOR_ACK"))
        val answer = approve(packId, USER_A_TOKEN)
        refusedWithNothingStored(answer, 403, "SRT-004", before)
        statusOf(packId) shouldBe "SUBMITTED"
        val sent = accessStub.requestsTo(AccessStubServer.DECISION).single()
        sent.query["authorRef"] shouldBe sent.query["approverRef"] // not refused locally: the stub is asked
    }

    @Test
    fun `the same user ID in another case is a different user and is not refused locally`() {
        val (packId, label) = submitted() // drafted by "user-a"
        accessStub.replies[AccessStubServer.DECISION] = AccessStubServer.Reply(200, allow())
        ok(approve(packId, USER_A_UPPER_TOKEN)) // verified token of "USER-A": no case folding, no trimming
        val sent = accessStub.requestsTo(AccessStubServer.DECISION).single()
        sent.query["authorRef"] shouldBe listOf("user-a")
        sent.query["approverRef"] shouldBe listOf("USER-A")
        approvalRows(label).single()["approved_by"] shouldBe "USER-A"
    }

    @Test
    fun `a DENY of each reasonCode is 403 SRT-004 naming no person and nothing is recorded`() {
        val reasons =
            listOf(
                "AUTHOR_IS_APPROVER",
                "NOT_STAFF",
                "NOT_EXTERNAL",
                "NO_SOLE_OPERATOR_ACK",
                "NOT_AGENT_SECOND_PERSON",
                "NOT_CLIENT_APPROVER",
            )
        val (packId, _) = submitted()
        val before = snapshot()
        for (reason in reasons) {
            accessStub.replies[AccessStubServer.DECISION] = AccessStubServer.Reply(200, deny(reason))
            val answer = approve(packId)
            refusedWithNothingStored(answer, 403, "SRT-004", before)
            answer.body() shouldNotContain "user-a"
            answer.body() shouldNotContain "user-b"
            answer.body() shouldNotContain reason
            statusOf(packId) shouldBe "SUBMITTED"
        }
        accessStub.requestsTo(AccessStubServer.DECISION).size shouldBe reasons.size
    }

    @Test
    fun `an answer that does not match the contract refuses the approval 503 and records nothing`() {
        val (packId, _) = submitted()
        val before = snapshot()
        val bodies =
            listOf(
                allow().replace("ALLOW", "allow"),
                allow().replace("\"soleOperator\":false,", ""),
                allow().replace("\"soleOperator\":false", "\"soleOperator\":\"false\""),
                allow().replace("\"outstanding\":[],", ""),
                allow().replace("\"option\":\"STAFF_APPROVER\"", "\"option\":null"),
                "not json",
                "",
            )
        for (body in bodies) {
            accessStub.replies[AccessStubServer.DECISION] = AccessStubServer.Reply(200, body)
            refusedWithNothingStored(approve(packId), 503, "SRT-013", before)
        }
    }

    @Test
    fun `an error status of access-svc refuses the approval and records nothing`() {
        val (packId, _) = submitted()
        val before = snapshot()
        for (status in listOf(400, 403, 404, 500, 503)) {
            accessStub.replies[AccessStubServer.DECISION] = AccessStubServer.Reply(status, "{}")
            refusedWithNothingStored(approve(packId), 503, "SRT-013", before)
        }
    }

    @Test
    fun `a timeout of access-svc refuses the approval and records nothing`() {
        val (packId, _) = submitted()
        val before = snapshot()
        accessStub.replies[AccessStubServer.DECISION] = AccessStubServer.Reply(200, allow(), delayMillis = 2_000)
        refusedWithNothingStored(approve(packId), 503, "SRT-013", before)
        (accessStub.requestsTo(AccessStubServer.DECISION).size >= 1) shouldBe true
    }

    // ---- activateMappingPack states the installation fact ----

    @Test
    fun `INV-AC-9553 the activation records carry the fact only where the installation recorded it`() {
        val answers = mutableListOf<Boolean>()
        // D1 (recorded as having no distinct approver) activates two packs, D2 (recorded as having one) one
        for ((distinct, from) in listOf("false" to "2026-01-01", "false" to "2026-02-01", "true" to "2026-03-01")) {
            val (packId, _) = approved()
            accessStub.replies[AccessStubServer.FACT] = AccessStubServer.Reply(200, fact(distinct))
            val activated = ok(activate(packId, from))
            val stored =
                value(TENANT_A_ID, "select no_distinct_approver from rules.pack_registry where pack_id = ?", packId)
            activated.get("activation").get("noDistinctApprover").asBoolean() shouldBe (stored as Boolean)
            ok(call("GET", "$PACKS_PATH/$packId?entityRef=$ENTITY_REF"))
                .get("activation")
                .get("noDistinctApprover")
                .asBoolean() shouldBe stored
            answers += stored
        }
        answers shouldBe listOf(true, true, false)
        accessStub.requestsTo(AccessStubServer.FACT).size shouldBe 3
    }

    @Test
    fun `INV-AC-1063 a private deployment recorded as having no distinct approver states it at the activation`() {
        val (packId, _) = approved()
        accessStub.replies[AccessStubServer.FACT] = AccessStubServer.Reply(200, fact("false"))
        val activated = ok(activate(packId, "2026-04-01"))
        activated.get("status").asString() shouldBe "ACTIVE"
        activated.get("activation").get("noDistinctApprover").asBoolean() shouldBe true
    }

    @Test
    fun `an installation with nothing recorded (404) states no distinct approver and one request is sent`() {
        val (packId, _) = approved()
        accessStub.replies[AccessStubServer.FACT] = AccessStubServer.Reply(404, "{}")
        ok(activate(packId, "2026-05-01")).get("activation").get("noDistinctApprover").asBoolean() shouldBe true
        accessStub.requestsTo(AccessStubServer.FACT).size shouldBe 1
    }

    @Test
    fun `any other answer for the installation fact refuses the activation and activates nothing`() {
        val (packId, _) = approved()
        val before = snapshot()
        val answers =
            listOf(
                AccessStubServer.Reply(500, "{}"),
                AccessStubServer.Reply(200, fact("\"false\"")),
                AccessStubServer.Reply(200, "{}"),
                AccessStubServer.Reply(200, "not json"),
                AccessStubServer.Reply(204),
            )
        for (reply in answers) {
            accessStub.replies[AccessStubServer.FACT] = reply
            refusedWithNothingStored(activate(packId, "2026-06-01"), 503, "SRT-013", before)
        }
        statusOf(packId) shouldBe "APPROVED"
    }

    // ---- the hold for the client's acknowledgement ----

    private fun heldPack(): Pair<String, String> =
        approved(allow(outstanding = listOf("CLIENT_ACKNOWLEDGEMENT"), option = "AGENT_SECOND_PERSON_CLIENT_ACK"))

    @Test
    fun `a missing client acknowledgement holds the activation 403 until access-svc answers 200`() {
        val (packId, label) = heldPack()
        accessStub.replies[AccessStubServer.FACT] = AccessStubServer.Reply(200, fact("true"))
        accessStub.replies[AccessStubServer.ACKNOWLEDGEMENT] = AccessStubServer.Reply(404, "{}")
        val before = snapshot()
        val held = activate(packId, "2026-07-01")
        refusedWithNothingStored(held, 403, "SRT-004", before)
        json(held).get("detail").asString() shouldBe HELD_DETAIL
        statusOf(packId) shouldBe "APPROVED"
        accessStub.requestsTo(AccessStubServer.ACKNOWLEDGEMENT).single().query shouldBe
            mapOf("packVersion" to listOf(label), "clientRef" to listOf(ENTITY_REF))

        accessStub.replies[AccessStubServer.ACKNOWLEDGEMENT] = AccessStubServer.Reply(200, acknowledgement(label))
        ok(activate(packId, "2026-07-01")).get("status").asString() shouldBe "ACTIVE"
    }

    @Test
    fun `the acknowledgement of another packVersion is a 404 of access-svc and holds the activation`() {
        val (packId, label) = heldPack()
        accessStub.replies[AccessStubServer.FACT] = AccessStubServer.Reply(200, fact("true"))
        // access-svc holds no acknowledgement for this pair (a look-alike version such as 2026.30 for 2026.3 is a 404)
        accessStub.replies[AccessStubServer.ACKNOWLEDGEMENT] = AccessStubServer.Reply(404, "{}")
        activate(packId, "2026-08-01").statusCode() shouldBe 403
        // the request names the pack's own version and client entity, never a look-alike
        accessStub.requestsTo(AccessStubServer.ACKNOWLEDGEMENT).last().let {
            it.query["packVersion"] shouldBe listOf(label)
            it.query["clientRef"] shouldBe listOf(ENTITY_REF)
        }
        accessStub.replies[AccessStubServer.ACKNOWLEDGEMENT] = AccessStubServer.Reply(200, "{}")
        activate(packId, "2026-08-01").statusCode() shouldBe 503
        statusOf(packId) shouldBe "APPROVED"
    }

    @Test
    fun `no acknowledgement request is sent when the latest approval has nothing outstanding`() {
        val (packId, _) = approved()
        accessStub.replies[AccessStubServer.FACT] = AccessStubServer.Reply(200, fact("true"))
        ok(activate(packId, "2026-09-01")).get("status").asString() shouldBe "ACTIVE"
        accessStub.requestsTo(AccessStubServer.ACKNOWLEDGEMENT).size shouldBe 0
    }

    @Test
    fun `an unavailable acknowledgement read refuses the activation 503 and nothing is stored`() {
        val (packId, _) = heldPack()
        val before = snapshot()
        accessStub.replies[AccessStubServer.ACKNOWLEDGEMENT] = AccessStubServer.Reply(500, "{}")
        refusedWithNothingStored(activate(packId, "2026-10-01"), 503, "SRT-013", before)
        accessStub.replies[AccessStubServer.ACKNOWLEDGEMENT] = AccessStubServer.Reply(200, "x")
        activate(packId, "2026-10-01").body() shouldContain "SRT-013"
    }

    companion object {
        private val TENANT_A_ID = com.dynokore.gst.rules.served.TENANT_A

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registerCatalogue(registry)
            registry.add("rules.access-svc-url") { accessStub.url }
        }
    }
}

/** The SaaS form (the default): no installation fact is asked and the field is false (INV-Q-10695 R5). */
@SpringBootTest(
    classes = [MappingDoubles::class, AccessStubbedPorts::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "service-runtime.idempotency.schema=rules",
        "service-runtime.workload-identity.callers[0].service=$CONSOLE_CALLER",
        "service-runtime.workload-identity.callers[0].subject=subject-of-$CONSOLE_CALLER",
    ],
)
class MappingPackAccessSaasFormTest(
    @org.springframework.boot.test.web.server.LocalServerPort port: Int,
) : MappingPackSupport(port) {
    @AfterEach
    fun resetStub() = accessStub.reset()

    @Test
    fun `INV-AC-9553 in the SaaS form no installation fact is asked and the activation states false`() {
        val label = "2026.saas-${UUID.randomUUID().toString().take(6)}"
        val packId = draft(label)
        ok(mapCode(packId), 201)
        deltaRow(com.dynokore.gst.rules.served.TENANT_A, label, "COMPLETE")
        ok(submit(packId))
        accessStub.replies[AccessStubServer.DECISION] = AccessStubServer.Reply(200, allow())
        accessStub.replies[AccessStubServer.FACT] = AccessStubServer.Reply(200, fact("false"))
        ok(approve(packId))
        val activated = ok(activate(packId, "2026-01-01"))
        activated.get("activation").get("noDistinctApprover").asBoolean() shouldBe false
        accessStub.requestsTo(AccessStubServer.FACT).size shouldBe 0
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registerCatalogue(registry)
            registry.add("rules.access-svc-url") { accessStub.url }
        }
    }
}
