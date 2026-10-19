package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SettingSource
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * The scope shape, tenant, entity-set and role rules of setPolicy and acknowledgeControlRelaxation, each driven through
 * HTTP on both operations (INV-T-5180; coordinator rulings INV-Q-5217, INV-Q-5233, INV-Q-5235, INV-Q-4115): a refusal
 * stores nothing and the [RecordingWriter] sees no call.
 */
class PolicyCommandScopeIT : PolicyHttpBase() {
    private val admin = setOf("tenant.admin", "tax.approver")

    private fun reaches(
        s: Service,
        op: PolicyOp,
        vp: String,
        body: String,
        token: String,
    ) {
        val before = s.writer.calls
        val answer = s.post(op.path(vp), body, token)
        (answer.status < 500) shouldBe true
        s.writer.calls shouldBe before + 1
    }

    private fun entityMismatchIsRefused(
        image: String,
        entityRef: String,
        scopeRef: String,
    ) {
        val s = Service(image)
        val token = s.user("u1", "e1", "e2", "E1", roleSet = admin)
        for (op in PolicyOp.entries) {
            s
                .post(op.path("VP-20"), request(op, ScopeLevel.ENTITY, scopeRef, entityRef), token)
                .closed(ClosedErrorCode.CONTRACT_VIOLATION)
        }
        s.writer.calls shouldBe 0
        s.portCalls.shouldBeEmpty()
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `ENTITY with entityRef e1 and scopeRef e2 is 400 SRT-001 and the writer is not called`(image: String) =
        entityMismatchIsRefused(image, "e1", "e2")

    @OnPostgres
    fun `ENTITY with entityRef e1 and scopeRef E1 is 400 SRT-001, no case folding`(image: String) =
        entityMismatchIsRefused(image, "e1", "E1")

    @OnPostgres
    fun `ENTITY with a trailing space or an empty scopeRef is 400 SRT-001, no trimming`(image: String) {
        entityMismatchIsRefused(image, "e1", "e1 ")
        entityMismatchIsRefused(image, "e1", "")
    }

    @OnPostgres
    fun `ENTITY with both refs e1 reaches the writer, and FEED with a different scopeRef reaches it too`(
        image: String,
    ) {
        val s = Service(image)
        val token = s.user("u1", "e1", roleSet = admin)
        for (op in PolicyOp.entries) {
            reaches(
                s,
                op,
                "VP-20",
                request(op, ScopeLevel.ENTITY, "e1", "e1", RequestFields(option = "DAILY_WINDOW")),
                token,
            )
            // the equality is ENTITY only
            reaches(s, op, "VP-12", request(op, ScopeLevel.FEED, "feed-9", "e1"), token)
        }
        s.writer.calls shouldBe 4
    }

    @OnPostgres
    fun `each of the seven scope levels has a request that breaks the present-exactly-when rules, 400 SRT-001`(
        image: String,
    ) {
        val s = Service(image)
        val token = s.user("u1", "e1", roleSet = admin)
        val tenantRef = s.tenant.toString()
        val broken =
            listOf(
                Triple(ScopeLevel.INSTALLATION, "x", null), // scopeRef present
                Triple(ScopeLevel.TENANT, null, null), // scopeRef absent
                Triple(ScopeLevel.GROUP, null, null),
                Triple(ScopeLevel.ENTITY, "e1", null), // entityRef absent
                Triple(ScopeLevel.FEED, "f1", null),
                Triple(ScopeLevel.AP_CONNECTION, "a1", null),
                Triple(ScopeLevel.CLIENT_ENTITY, "c1", null),
                Triple(ScopeLevel.TENANT, tenantRef, "e1"), // entityRef present where none belongs
                Triple(ScopeLevel.GROUP, "g1", "e1"),
                Triple(ScopeLevel.INSTALLATION, null, "e1"),
            )
        for (op in PolicyOp.entries) {
            for ((level, scopeRef, entityRef) in broken) {
                s
                    .post(op.path("VP-12"), request(op, level, scopeRef, entityRef), token)
                    .closed(ClosedErrorCode.CONTRACT_VIOLATION)
            }
        }
        s.writer.calls shouldBe 0
    }

    @OnPostgres
    fun `the shape check follows the job servlet order, after 401 and before 403`(image: String) {
        val s = Service(image)
        val operator = s.operator("op", setOf("platform.engineer"))
        for (op in PolicyOp.entries) {
            val broken = request(op, ScopeLevel.ENTITY, "e1", "e2")
            // 401 first: no valid token, even with a broken body
            s.post(op.path("VP-20"), broken).closed(ClosedErrorCode.UNAUTHENTICATED)
            s.post(op.path("VP-20"), broken, "Bearer nobody").closed(ClosedErrorCode.UNAUTHENTICATED)
            // 400 before 403: a tenantless token would be refused for ENTITY, but the shape comes first
            s.post(op.path("VP-20"), broken, operator).closed(ClosedErrorCode.CONTRACT_VIOLATION)
            // the Idempotency-Key and the body are contract checks too, still before 403
            s
                .post(op.path("VP-20"), request(op, ScopeLevel.ENTITY, "e1", "e1"), operator, key = null)
                .closed(ClosedErrorCode.IDEMPOTENCY_KEY_INVALID)
            s
                .post(
                    op.path("VP-20"),
                    request(op, ScopeLevel.ENTITY, "e1", "e1"),
                    operator,
                ).closed(ClosedErrorCode.ACCESS_DENIED)
        }
        s.writer.calls shouldBe 0
        s.portCalls.shouldBeEmpty()
    }

    /**
     * The row and option that make a TENANT request succeed: a POLICY for setPolicy, a CONTROL for the acknowledgement.
     */
    private fun tenantTarget(op: PolicyOp) = if (op == PolicyOp.SET) "VP-12" to "B" else "VP-07" to "TENANT_ADMIN_SOLE"

    private fun tenantRefIsRefused(
        image: String,
        other: (Service) -> String,
    ) {
        val s = Service(image)
        val token = s.user("u1", roleSet = admin)
        for (op in PolicyOp.entries) {
            val (vp, option) = tenantTarget(op)
            s
                .post(
                    op.path(vp),
                    request(op, ScopeLevel.TENANT, other(s), selection = RequestFields(option = option)),
                    token,
                ).closed(ClosedErrorCode.ACCESS_DENIED)
        }
        s.writer.calls shouldBe 0
        s.portCalls.shouldBeEmpty()
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `TENANT with the token's own id reaches the writer with the token tenant, and the response scopeRef is that id`(
        image: String,
    ) {
        val s = Service(image)
        val token = s.user("u1", roleSet = admin)
        val own = s.tenant.toString()
        for (op in PolicyOp.entries) {
            val (vp, option) = tenantTarget(op)
            val answer =
                s
                    .post(
                        op.path(vp),
                        request(op, ScopeLevel.TENANT, own, selection = RequestFields(option = option)),
                        token,
                    ).ok()
            answer.body.get("scopeRef").asString() shouldBe own
            answer.body.get("scopeLevel").asString() shouldBe "TENANT"
        }
        val scopes = s.writer.sets.map { it.scope } + s.writer.acknowledgements.map { it.scope }
        scopes.size shouldBe 2
        scopes.forEach {
            it.target(ScopeLevel.TENANT).tenant shouldBe s.tenant
            it.target(ScopeLevel.TENANT).ref.id shouldBe own
        }
    }

    @OnPostgres
    fun `TENANT with another tenant's UUID is 403 SRT-004 with zero writer calls, whether or not that tenant exists`(
        image: String,
    ) {
        // a tenant nothing knows, and the tenant of another service of this test, which does exist
        tenantRefIsRefused(image) { UUID.randomUUID().toString() }
        val existing = Service(image).tenant.toString()
        tenantRefIsRefused(image) { existing }
    }

    @OnPostgres
    fun `TENANT with not-a-uuid is 403 SRT-004 with zero writer calls`(image: String) =
        tenantRefIsRefused(image) { "not-a-uuid" }

    @OnPostgres
    fun `TENANT with the upper-case form of the token's own id is 403 SRT-004 with zero writer calls`(image: String) =
        tenantRefIsRefused(image) { it.tenant.toString().uppercase() }

    private fun assertScopesRefused(
        s: Service,
        operator: String,
        tenantUser: String,
    ) {
        for (op in PolicyOp.entries) {
            val vp = "VP-72"
            // a tenant-scoped target from a tenantless token, and INSTALLATION from a tenant token: 403, nothing asked
            s
                .post(
                    op.path("VP-12"),
                    request(op, ScopeLevel.TENANT, s.tenant.toString()),
                    operator,
                ).closed(ClosedErrorCode.ACCESS_DENIED)
            s
                .post(
                    op.path("VP-12"),
                    request(op, ScopeLevel.ENTITY, "e1", "e1"),
                    operator,
                ).closed(ClosedErrorCode.ACCESS_DENIED)
            s
                .post(
                    op.path(vp),
                    request(op, ScopeLevel.INSTALLATION, selection = RequestFields(option = "Y")),
                    tenantUser,
                ).closed(ClosedErrorCode.ACCESS_DENIED)
        }
    }

    @OnPostgres
    fun `a tenantless token reaches only INSTALLATION under the platform tenant, a tenant token never`(image: String) {
        val s = Service(image)
        val operator = s.operator("op", setOf("platform.engineer"))
        val tenantUser = s.user("u1", "e1", roleSet = setOf("platform.engineer", "tenant.admin"))
        assertScopesRefused(s, operator, tenantUser)
        s.portCalls.shouldBeEmpty()
        s.writer.calls shouldBe 0
        // INSTALLATION from the tenantless token: the port is asked under the platform tenant, the writer is reached
        val answer =
            s.post(
                PolicyOp.SET.path("VP-72"),
                request(PolicyOp.SET, ScopeLevel.INSTALLATION, selection = RequestFields(option = "Y")),
                operator,
            )
        answer.ok()
        answer.body.has("scopeRef") shouldBe false
        answer.body.get("setBy").asString() shouldBe "op"
        s.portCalls.single() shouldBe listOf("op", TenantId.PLATFORM, ScopeLevel.INSTALLATION, null, null)
        s.writer.sets
            .single()
            .scope
            .ref(ScopeLevel.INSTALLATION)!!
            .level shouldBe ScopeLevel.INSTALLATION
    }

    /** The vp, option and role that make an ENTITY request reach a success on each operation. */
    private fun entityTarget(op: PolicyOp) =
        if (op ==
            PolicyOp.SET
        ) {
            Triple("VP-20", "DAILY_WINDOW", "tax.approver")
        } else {
            Triple("VP-62", "NEXT_WORKING_DAY", "tax.approver")
        }

    private fun entityBody(op: PolicyOp): String {
        val (_, option, role) = entityTarget(op)
        return request(op, ScopeLevel.ENTITY, "E-1", "E-1", RequestFields(option = option, asRole = role))
    }

    @OnPostgres
    fun `stale entity set and the port answering roles for the entity is not refused though the set is empty`(
        image: String,
    ) {
        val s = Service(image)
        for (op in PolicyOp.entries) {
            val token = s.user("stale-empty-$op", current = false, roleSet = admin)
            s.post(op.path(entityTarget(op).first), entityBody(op), token).status shouldBe 200
            s.portCalls.last() shouldBe listOf("stale-empty-$op", s.tenant, ScopeLevel.ENTITY, "E-1", "E-1")
        }
        s.writer.calls shouldBe 2
    }

    @OnPostgres
    fun `stale entity set and the port answering an empty set is 403 SRT-004 though the set holds the entity`(
        image: String,
    ) {
        val s = Service(image)
        for (op in PolicyOp.entries) {
            val token = s.user("stale-holding-$op", "E-1", current = false, roleSet = emptySet())
            s.post(op.path(entityTarget(op).first), entityBody(op), token).closed(ClosedErrorCode.ACCESS_DENIED)
            s.portCalls.size shouldBe op.ordinal + 1
        }
        s.writer.calls shouldBe 0
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `stale entity set and the port throwing is 403 SRT-004`(image: String) {
        val s = Service(image)
        s.portFailure = { throw IllegalStateException("access-svc unreachable") }
        for (op in PolicyOp.entries) {
            val token = s.user("stale-throwing-$op", "E-1", current = false, roleSet = admin)
            s.post(op.path(entityTarget(op).first), entityBody(op), token).closed(ClosedErrorCode.ACCESS_DENIED)
        }
        s.writer.calls shouldBe 0
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `current entity set and the entity outside it is 403 SRT-004 and the port is not called`(image: String) {
        val s = Service(image)
        for (op in PolicyOp.entries) {
            val token = s.user("current-outside-$op", "E-2", roleSet = admin)
            s.post(op.path(entityTarget(op).first), entityBody(op), token).closed(ClosedErrorCode.ACCESS_DENIED)
        }
        s.portCalls.shouldBeEmpty()
        s.writer.calls shouldBe 0
    }

    @OnPostgres
    fun `current entity set and the entity inside it with an empty port answer is 403 SRT-004`(image: String) {
        val s = Service(image)
        for (op in PolicyOp.entries) {
            val token = s.user("current-inside-$op", "E-1", roleSet = emptySet())
            s.post(op.path(entityTarget(op).first), entityBody(op), token).closed(ClosedErrorCode.ACCESS_DENIED)
        }
        s.portCalls.size shouldBe 2
        s.writer.calls shouldBe 0
    }

    @OnPostgres
    fun `an unbound role port is 403 SRT-004 with nothing stored`(image: String) {
        val s = Service(image, bindRoles = false)
        val token = s.user("u1", "e1", roleSet = admin)
        for (op in PolicyOp.entries) {
            s
                .post(
                    op.path("VP-12"),
                    request(op, ScopeLevel.TENANT, s.tenant.toString()),
                    token,
                ).closed(ClosedErrorCode.ACCESS_DENIED)
        }
        s.writer.calls shouldBe 0
        s.stored() shouldBe 0
    }

    private fun assertAckMapped(
        s: Service,
        token: String,
        relaxation: String,
    ) {
        val ack =
            listOf(
                """{"scopeLevel":"GROUP","scopeRef":"g-1","selected":[{"option":"TENANT_ADMIN_SOLE"}],""",
                """"effectiveDate":"2026-11-15","asRole":"tenant.admin","relaxationId":"$relaxation",""",
                """"reason":"accepted"}""",
            ).joinToString("")
        val ackKey = UUID.randomUUID().toString()
        s.post(PolicyOp.ACK.path("VP-07"), ack, token, ackKey)
        val acknowledgement = s.writer.acknowledgements.single()
        acknowledgement.key.value shouldBe "VP-07"
        acknowledgement.level shouldBe ScopeLevel.GROUP
        acknowledgement.scope.target(ScopeLevel.GROUP).let {
            it.tenant shouldBe s.tenant
            it.ref.id shouldBe "g-1"
        }
        acknowledgement.selected.map { it.key } shouldBe listOf("TENANT_ADMIN_SOLE")
        acknowledgement.asRole shouldBe "tenant.admin"
        acknowledgement.relaxation!!.value.toString() shouldBe relaxation
        acknowledgement.reason shouldBe "accepted"
        acknowledgement.actor.principal shouldBe "u1"
        acknowledgement.causation.value shouldBe "idempotency-key:$ackKey"
    }

    @OnPostgres
    fun `the request maps field by field to the command, the tenant from the token and the causation from the key`(
        image: String,
    ) {
        val s = Service(image)
        val token = s.user("u1", "e1", roleSet = admin)
        val key = UUID.randomUUID().toString()
        val relaxation = UUID.randomUUID().toString()
        val body =
            listOf(
                """{"scopeLevel":"AP_CONNECTION","scopeRef":"ap-7","entityRef":"e1",""",
                """"selected":[{"option":"A"},{"option":"B","value":{"n":[1,2]}}],""",
                """"effectiveDate":"2026-11-15","reason":"why","source":"PROFILE",""",
                """"profileRef":"profile-x","profileVersion":3,"relaxationId":"$relaxation"}""",
            ).joinToString("")
        s.post(PolicyOp.SET.path("VP-12"), body, token, key)
        val command = s.writer.sets.single()
        command.key.value shouldBe "VP-12"
        command.level shouldBe ScopeLevel.AP_CONNECTION
        command.scope.target(ScopeLevel.AP_CONNECTION).let {
            it.tenant shouldBe s.tenant
            it.ref.id shouldBe "ap-7"
            it.entity shouldBe "e1"
        }
        command.selected.map { it.key to it.valueJson } shouldBe listOf("A" to null, "B" to """{"n":[1,2]}""")
        command.effectiveFrom.toString() shouldBe "2026-11-15"
        command.reason shouldBe "why"
        command.source shouldBe SettingSource.PROFILE
        command.profileId shouldBe "profile-x"
        command.profileVersion shouldBe "3"
        command.relaxation!!.value.toString() shouldBe relaxation
        command.actor.principal shouldBe "u1"
        command.actor.roles shouldBe admin
        command.causation.value shouldBe "idempotency-key:$key"
        command.correlation.value.startsWith("http:") shouldBe true

        assertAckMapped(s, token, relaxation)
    }

    @OnPostgres
    fun `the profile pair is not shape-checked, PROFILE without it is POL-023, an OVERRIDE with it is 400 SRT-001`(
        image: String,
    ) {
        val s = Service(image)
        val token = s.user("u1", roleSet = admin)
        val tenantRef = s.tenant.toString()

        fun set(extra: String) =
            s.post(
                PolicyOp.SET.path("VP-12"),
                request(PolicyOp.SET, ScopeLevel.TENANT, tenantRef, selection = RequestFields(extra = extra)),
                token,
            )

        val profile = "\"source\":\"PROFILE\""
        set("$profile,\"profileVersion\":2").refused(PolicyErrorCode.PROFILE_INCOMPLETE)
        set("$profile,\"profileRef\":\"p1\"").refused(PolicyErrorCode.PROFILE_INCOMPLETE)
        s.stored() shouldBe 0
        val override = "\"source\":\"OVERRIDE\""
        set("$override,\"profileRef\":\"p1\"").closed(ClosedErrorCode.CONTRACT_VIOLATION)
        set("\"profileVersion\":2").closed(ClosedErrorCode.CONTRACT_VIOLATION)
        s.stored() shouldBe 0
        set("$profile,\"profileRef\":\"p1\",\"profileVersion\":2").ok()
        s.stored() shouldBe 1
    }
}
