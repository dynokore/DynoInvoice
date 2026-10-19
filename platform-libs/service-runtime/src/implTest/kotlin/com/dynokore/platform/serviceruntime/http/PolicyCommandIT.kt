package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.ScopeLevel
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * setPolicy and acknowledgeControlRelaxation through HTTP over config-resolver's real writer on PostgreSQL
 * (INV-T-5180): routing, the `Idempotency-Key` (API-15), the closed and POL refusals, the responses against the
 * pinned contract, and the behaviour of the acceptance criteria of the task (each test named by its criterion key).
 */
class PolicyCommandIT : PolicyHttpBase() {
    private val setting = setOf("tenant.admin", "tax.approver", "security.officer", "platform.engineer")

    private fun Service.tenantUser(
        name: String = "u1",
        roleSet: Set<String> = setting,
        vararg entities: String = arrayOf("E-1"),
    ) = user(name, *entities, roleSet = roleSet)

    @OnPostgres
    fun `the two paths are served, another path is 404 SRT-005 and another method 405 SRT-006`(image: String) {
        val s = Service(image)
        val token = s.tenantUser()
        for (op in PolicyOp.entries) {
            val body = request(op, ScopeLevel.TENANT, s.tenant.toString())
            s.post(op.path("VP-12"), body, token, method = "GET").closed(ClosedErrorCode.METHOD_NOT_ALLOWED)
            s.post(op.path("VP-12"), null, token, method = "PUT").closed(ClosedErrorCode.METHOD_NOT_ALLOWED)
        }
        s.post("/internal/v1/policies/VP-12/other", "{}", token).closed(ClosedErrorCode.NOT_FOUND, declared = false)
        s
            .post(
                "/internal/v1/policies/VP-12/relaxations/x",
                "{}",
                token,
            ).closed(ClosedErrorCode.NOT_FOUND, declared = false)
        s.writer.calls shouldBe 0
    }

    @OnPostgres
    fun `a variation point that is not registered is 404 POL-001 and a malformed path key is 400 SRT-001`(
        image: String,
    ) {
        val s = Service(image)
        val token = s.tenantUser()
        for (op in PolicyOp.entries) {
            val body = request(op, ScopeLevel.TENANT, s.tenant.toString())
            s.post(op.path("VP-99"), body, token).refused(PolicyErrorCode.UNKNOWN_VARIATION_POINT)
            s.post(op.path("VP-1"), body, token).refused(PolicyErrorCode.UNKNOWN_VARIATION_POINT)
            s.post(op.path("XYZ"), body, token).closed(ClosedErrorCode.CONTRACT_VIOLATION)
        }
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `the Idempotency-Key is required, a replay is the stored 200, another body 422, a malformed key 400`(
        image: String,
    ) {
        val s = Service(image)
        val token = s.tenantUser()
        val body = request(PolicyOp.SET, ScopeLevel.TENANT, s.tenant.toString())
        val path = PolicyOp.SET.path("VP-12")
        s.post(path, body, token, key = null).closed(ClosedErrorCode.IDEMPOTENCY_KEY_INVALID)
        s.post(path, body, token, key = "not-a-uuid").closed(ClosedErrorCode.IDEMPOTENCY_KEY_INVALID)
        s.writer.calls shouldBe 0

        val key = UUID.randomUUID().toString()
        val first = s.post(path, body, token, key).ok()
        val replay = s.post(path, body, token, key)
        replay.status shouldBe 200
        replay.body shouldBe first.body
        s.writer.calls shouldBe 1
        s.stored() shouldBe 1

        s
            .post(
                path,
                request(PolicyOp.SET, ScopeLevel.TENANT, s.tenant.toString(), selection = RequestFields(option = "A")),
                token,
                key,
            ).closed(ClosedErrorCode.DIFFERENT_REQUEST)
        s.writer.calls shouldBe 1

        // a refusal is stored too: its replay is the same refusal
        val refusedKey = UUID.randomUUID().toString()
        val refusing =
            request(PolicyOp.SET, ScopeLevel.TENANT, s.tenant.toString(), selection = RequestFields(reason = null))
        val refused = s.post(path, refusing, token, refusedKey).refused(PolicyErrorCode.REASON_REQUIRED)
        s.post(path, refusing, token, refusedKey).body shouldBe refused.body
        s.writer.calls shouldBe 2
    }

    @OnPostgres
    fun `a request with the same key while the first runs is 409 SRT-007`(image: String) {
        val s = Service(image)
        val token = s.tenantUser()
        val body = request(PolicyOp.SET, ScopeLevel.TENANT, s.tenant.toString())
        val path = PolicyOp.SET.path("VP-12")
        val key = UUID.randomUUID().toString()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        s.writer.before = {
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val running = pool.submit<Answer> { s.post(path, body, token, key) }
            entered.await(30, TimeUnit.SECONDS) shouldBe true
            s.post(path, body, token, key).closed(ClosedErrorCode.IN_FLIGHT)
            release.countDown()
            running.get(30, TimeUnit.SECONDS).ok()
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
        s.stored() shouldBe 1
    }

    @OnPostgres
    fun `an exception that is no refusal is 500 SRT-010 with no detail, nothing stored or recorded`(image: String) {
        val s = Service(image)
        val token = s.tenantUser()
        val body = request(PolicyOp.SET, ScopeLevel.TENANT, s.tenant.toString())
        val key = UUID.randomUUID().toString()
        s.writer.before = { throw IllegalStateException("database exploded") }
        val failed = s.post(PolicyOp.SET.path("VP-12"), body, token, key).closed(ClosedErrorCode.INTERNAL_ERROR)
        failed.body.toString().contains("exploded") shouldBe false
        s.writer.before = {}
        // the failure was not recorded: the same key runs again
        s.post(PolicyOp.SET.path("VP-12"), body, token, key).ok()
        s.stored() shouldBe 1
    }

    @OnPostgres
    fun `INV-AC-2224 a role the row does not name is 403 POL-004 and a value outside its bounds is 422 POL-013`(
        image: String,
    ) {
        val s = Service(image)
        val path = PolicyOp.SET.path("VP-42")
        val tenantRef = s.tenant.toString()

        fun set(value: String) =
            request(
                PolicyOp.SET,
                ScopeLevel.TENANT,
                tenantRef,
                selection = RequestFields(option = "LAST_N", value = value),
            )
        // the setting role is security.officer; a user without it, with a value within the bounds
        val other = s.user("other", roleSet = setOf("tenant.admin"))
        s.post(path, set("3"), other).refused(PolicyErrorCode.ROLE_NOT_ALLOWED)
        s.stored() shouldBe 0
        // the setting role, a value outside the bounds
        val officer = s.user("officer", roleSet = setOf("security.officer"))
        s.post(path, set("9"), officer).refused(PolicyErrorCode.OUT_OF_BOUNDS)
        s.post(path, set("0"), officer).refused(PolicyErrorCode.OUT_OF_BOUNDS)
        s.stored() shouldBe 0
        // within the bounds from the setting role: accepted and shown with its source
        val accepted = s.post(path, set("3"), officer).ok()
        accepted.body.get("source").asString() shouldBe "OVERRIDE"
        accepted.body
            .get("selected")
            .get(0)
            .get("value")
            .asInt() shouldBe 3
        s.stored() shouldBe 1
    }

    @OnPostgres
    fun `INV-AC-2227 a scope level the row does not allow is 422 POL-003`(image: String) {
        val s = Service(image)
        val token = s.tenantUser()
        // VP-20 allows entity scope only
        val tenantBody =
            request(
                PolicyOp.SET,
                ScopeLevel.TENANT,
                s.tenant.toString(),
                selection = RequestFields(option = "DAILY_WINDOW"),
            )
        s
            .post(PolicyOp.SET.path("VP-20"), tenantBody, token)
            .refused(PolicyErrorCode.SCOPE_NOT_ALLOWED)
        s.stored() shouldBe 0
        val entityBody = request(PolicyOp.SET, ScopeLevel.ENTITY, "E-1", "E-1", RequestFields(option = "DAILY_WINDOW"))
        s.post(PolicyOp.SET.path("VP-20"), entityBody, token).ok()
        s.stored() shouldBe 1
    }

    private fun assertAcceptedChange(accepted: Answer) {
        accepted.body
            .get("prior")
            .get("selected")
            .get(0)
            .get("option")
            .asString() shouldBe "CONTINUOUS"
        accepted.body
            .get("prior")
            .get("source")
            .asString() shouldBe "OVERRIDE"
        accepted.body
            .get("auditEventId")
            .asString()
            .isNotEmpty() shouldBe true
        accepted.body
            .get("policyChangedEventId")
            .asString()
            .isNotEmpty() shouldBe true
        accepted.body.get("effectiveDate").asString() shouldBe NEXT_MONTH
        accepted.body.get("reason").asString() shouldBe "because"
    }

    @OnPostgres
    fun `INV-AC-2229 no reason is 400 POL-005, an accepted change returns the prior value and an audit event`(
        image: String,
    ) {
        val s = Service(image)
        val token = s.tenantUser()
        val path = PolicyOp.SET.path("VP-20")
        // continuous is set first, from today
        val first =
            request(
                PolicyOp.SET,
                ScopeLevel.ENTITY,
                "E-1",
                "E-1",
                RequestFields(option = "CONTINUOUS", effectiveDate = TODAY),
            )
        s.post(path, first, token).ok()
        // the change to a daily window effective next month: without a reason it is refused, with one it is accepted
        val change = request(PolicyOp.SET, ScopeLevel.ENTITY, "E-1", "E-1", RequestFields(option = "DAILY_WINDOW"))
        val noReason =
            request(
                PolicyOp.SET,
                ScopeLevel.ENTITY,
                "E-1",
                "E-1",
                RequestFields(option = "DAILY_WINDOW", reason = null),
            )
        s.post(path, noReason, token).refused(PolicyErrorCode.REASON_REQUIRED)
        val emptyReason =
            request(PolicyOp.SET, ScopeLevel.ENTITY, "E-1", "E-1", RequestFields(option = "DAILY_WINDOW", reason = ""))
        s
            .post(path, emptyReason, token)
            .refused(PolicyErrorCode.REASON_REQUIRED)
        s.stored() shouldBe 1
        val accepted = s.post(path, change, token).ok()
        assertAcceptedChange(accepted)
        s.stored() shouldBe 2
    }

    @OnPostgres
    fun `INV-AC-2230 a looser option of a non-waivable control is 422 POL-015`(image: String) {
        val s = Service(image)
        val token = s.tenantUser()
        val soleOperator =
            request(PolicyOp.SET, ScopeLevel.ENTITY, "E-1", "E-1", RequestFields(option = "SOLE_OPERATOR"))
        s
            .post(PolicyOp.SET.path("VP-05"), soleOperator, token)
            .refused(PolicyErrorCode.LOOSER_NOT_OFFERED)
        val soleAck =
            request(
                PolicyOp.ACK,
                ScopeLevel.ENTITY,
                "E-1",
                "E-1",
                RequestFields(option = "SOLE_OPERATOR", asRole = "tenant.admin"),
            )
        s.post(PolicyOp.ACK.path("VP-05"), soleAck, token).refused(PolicyErrorCode.LOOSER_NOT_OFFERED)
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `INV-AC-205 a looser option without a complete relaxation is 409 POL-018, a relaxation is used once`(
        image: String,
    ) {
        val s = Service(image)
        val approver = s.user("approver", "E-1", roleSet = setOf("tax.approver"))
        val signatory = s.user("signatory", "E-1", roleSet = setOf("client.signatory"))
        val set = PolicyOp.SET.path("VP-62")
        val ack = PolicyOp.ACK.path("VP-62")

        // an entity without an agent: refused without the acknowledgement, accepted with the tax.approver's
        fun looser(relaxation: String? = null) =
            request(
                PolicyOp.SET,
                ScopeLevel.ENTITY,
                "E-1",
                "E-1",
                RequestFields(
                    option = "NEXT_WORKING_DAY",
                    extra = relaxation?.let { "\"relaxationId\":\"$it\"" }.orEmpty(),
                ),
            )
        s.post(set, looser(), approver).refused(PolicyErrorCode.RELAXATION_REQUIRED)
        val looserOption = "NEXT_WORKING_DAY"
        val firstAck =
            request(
                PolicyOp.ACK,
                ScopeLevel.ENTITY,
                "E-1",
                "E-1",
                RequestFields(option = looserOption, asRole = "tax.approver"),
            )
        val acknowledged = s.post(ack, firstAck, approver).ok()
        acknowledged.body.get("complete").asBoolean() shouldBe true
        acknowledged.body.get("outstandingRoles").size() shouldBe 0
        val relaxation = acknowledged.body.get("relaxationId").asString()
        val accepted = s.post(set, looser(relaxation), approver).ok()
        accepted.body.get("relaxationId").asString() shouldBe relaxation
        // a relaxation used once is refused for a second setting
        s.post(set, looser(relaxation), approver).refused(PolicyErrorCode.RELAXATION_REQUIRED)

        // an agent-managed client: the tax.approver's acknowledgement alone is not enough
        fun client(
            op: PolicyOp,
            relaxation: String?,
            asRole: String = "tax.approver",
        ) = request(
            op,
            ScopeLevel.CLIENT_ENTITY,
            "C-1",
            "E-1",
            RequestFields(
                option = "NEXT_WORKING_DAY",
                asRole = asRole,
                extra = relaxation?.let { "\"relaxationId\":\"$it\"" }.orEmpty(),
            ),
        )
        val partial = s.post(ack, client(PolicyOp.ACK, null), approver).ok()
        partial.body.get("complete").asBoolean() shouldBe false
        partial.body
            .get("outstandingRoles")
            .get(0)
            .asString() shouldBe "client.signatory"
        val clientRelaxation = partial.body.get("relaxationId").asString()
        s.post(set, client(PolicyOp.SET, clientRelaxation), approver).refused(PolicyErrorCode.RELAXATION_REQUIRED)
        s.post(set, client(PolicyOp.SET, null), approver).refused(PolicyErrorCode.RELAXATION_REQUIRED)
        val complete = s.post(ack, client(PolicyOp.ACK, clientRelaxation, "client.signatory"), signatory).ok()
        complete.body.get("complete").asBoolean() shouldBe true
        complete.body.get("acknowledgements").size() shouldBe 2
        s.post(set, client(PolicyOp.SET, clientRelaxation), approver).ok()
    }
}
