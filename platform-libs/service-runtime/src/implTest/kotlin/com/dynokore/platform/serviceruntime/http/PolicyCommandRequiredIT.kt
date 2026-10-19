package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Required fields and the `prior` of the response (INV-T-5180; coordinator rulings INV-Q-5263 and INV-Q-5261): a
 * missing or empty value the catalogue names a POL code for goes to the writer; a wrong type or shape is 400 SRT-001
 * before the access checks; the writer runs last.
 */
class PolicyCommandRequiredIT : PolicyHttpBase() {
    private val admin = setOf("tenant.admin", "tax.approver")

    /** A body with the given raw `reason` and `selected` JSON (null leaves the field out). */
    private fun body(
        op: PolicyOp,
        reason: String? = "\"because\"",
        selected: String? = "[{\"option\":\"B\"}]",
        asRole: String? = "\"tenant.admin\"",
        scopeRef: String,
    ): String {
        val fields =
            mutableListOf(
                "\"scopeLevel\":\"TENANT\"",
                "\"scopeRef\":\"$scopeRef\"",
                "\"effectiveDate\":\"$NEXT_MONTH\"",
            )
        selected?.let { fields += "\"selected\":$it" }
        if (op == PolicyOp.SET) reason?.let { fields += "\"reason\":$it" }
        if (op == PolicyOp.ACK) {
            asRole?.let { fields += "\"asRole\":$it" }
            reason?.let { fields += "\"reason\":$it" }
        }
        return "{" + fields.joinToString(",") + "}"
    }

    private fun target(op: PolicyOp) = if (op == PolicyOp.SET) "VP-12" else "VP-07"

    @OnPostgres
    fun `reason absent, null, empty and blank are each 400 POL-005 from the writer with nothing stored`(image: String) {
        val s = Service(image)
        val token = s.user("u1", roleSet = admin)
        val path = PolicyOp.SET.path("VP-12")
        for (reason in listOf(null, "null", "\"\"", "\"   \"")) {
            val before = s.writer.calls
            s
                .post(path, body(PolicyOp.SET, reason, scopeRef = s.tenant.toString()), token)
                .refused(PolicyErrorCode.REASON_REQUIRED)
            s.writer.calls shouldBe before + 1
        }
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `reason that is no string or longer than 500 characters is 400 SRT-001 with zero writer calls`(image: String) {
        val s = Service(image)
        val token = s.user("u1", roleSet = admin)
        for (op in PolicyOp.entries) {
            for (reason in listOf("123", "\"" + "x".repeat(501) + "\"")) {
                s
                    .post(op.path(target(op)), body(op, reason, scopeRef = s.tenant.toString()), token)
                    .closed(ClosedErrorCode.CONTRACT_VIOLATION)
            }
        }
        s.writer.calls shouldBe 0
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `selected as an empty array is 400 POL-012 from the writer on both operations`(image: String) {
        val s = Service(image)
        val token = s.user("u1", roleSet = admin)
        for (op in PolicyOp.entries) {
            val before = s.writer.calls
            s
                .post(op.path(target(op)), body(op, selected = "[]", scopeRef = s.tenant.toString()), token)
                .refused(PolicyErrorCode.SELECTION_INVALID)
            s.writer.calls shouldBe before + 1
        }
        s.stored() shouldBe 0
    }

    @OnPostgres
    fun `selected absent, null, an object or a non-object item is 400 SRT-001 with zero writer calls`(image: String) {
        val s = Service(image)
        val token = s.user("u1", roleSet = admin)
        for (op in PolicyOp.entries) {
            for (selected in listOf(null, "null", "{}", "[1]", "[{}]", "[{\"option\":\"\"}]", "[{\"option\":5}]")) {
                s
                    .post(op.path(target(op)), body(op, selected = selected, scopeRef = s.tenant.toString()), token)
                    .closed(ClosedErrorCode.CONTRACT_VIOLATION)
            }
        }
        s.writer.calls shouldBe 0
    }

    @OnPostgres
    fun `asRole absent or empty is 400 SRT-001 and reason stays optional on the acknowledgement`(image: String) {
        val s = Service(image)
        val token = s.user("u1", roleSet = admin)
        val path = PolicyOp.ACK.path("VP-07")
        for (asRole in listOf(null, "null", "\"\"")) {
            s
                .post(path, body(PolicyOp.ACK, asRole = asRole, scopeRef = s.tenant.toString()), token)
                .closed(ClosedErrorCode.CONTRACT_VIOLATION)
        }
        s.writer.calls shouldBe 0
        // no reason on the acknowledgement is not a shape error: it reaches the writer
        s.post(path, body(PolicyOp.ACK, reason = null, scopeRef = s.tenant.toString()), token)
        s.writer.calls shouldBe 1
    }

    @OnPostgres
    fun `reason absent from a caller outside the entity set is 403 SRT-004 with zero writer calls`(image: String) {
        val s = Service(image)
        val token = s.user("u1", "E-1", roleSet = admin)
        val noReason =
            request(
                PolicyOp.SET,
                ScopeLevel.ENTITY,
                "E-9",
                "E-9",
                RequestFields(option = "DAILY_WINDOW", reason = null),
            )
        s.post(PolicyOp.SET.path("VP-20"), noReason, token).closed(ClosedErrorCode.ACCESS_DENIED)
        val emptySelected = noReason.replace(Regex("\"selected\":\\[[^\\]]*\\]"), "\"selected\":[]")
        s.post(PolicyOp.SET.path("VP-20"), emptySelected, token).closed(ClosedErrorCode.ACCESS_DENIED)
        s.writer.calls shouldBe 0
    }

    private fun prior(source: PolicySource) =
        ResolvedSetting(
            com.dynokore.platform.configresolver
                .VariationPointKey("VP-12"),
            java.time.LocalDate.parse(NEXT_MONTH),
            listOf(SelectedOption("A")),
            source,
        )

    private fun priorOf(
        image: String,
        pin: (
            (
                com.dynokore.platform.configresolver.PolicyChange,
            ) -> com.dynokore.platform.configresolver.PolicyChange
        )?,
    ): tools.jackson.databind.JsonNode {
        val s = Service(image)
        val token = s.user("u1", roleSet = admin)
        pin?.let { s.writer.change = it }
        val request =
            request(
                PolicyOp.SET,
                ScopeLevel.TENANT,
                s.tenant.toString(),
                selection = RequestFields(effectiveDate = NEXT_MONTH),
            )
        return s.post(PolicyOp.SET.path("VP-12"), request, token).ok().body
    }

    @OnPostgres
    fun `an Override prior is sent with source OVERRIDE and the request's effectiveDate`(image: String) {
        val source = PolicySource.Override(ScopeLevel.TENANT, "t", UUID.randomUUID())
        val body = priorOf(image) { it.copy(prior = prior(source)) }
        body.get("prior").get("source").asString() shouldBe "OVERRIDE"
        body.get("prior").get("effectiveDate").asString() shouldBe NEXT_MONTH
        body
            .get("prior")
            .get("selected")
            .get(0)
            .get("option")
            .asString() shouldBe "A"
    }

    @OnPostgres
    fun `a Profile prior is sent with source PROFILE`(image: String) {
        val source = PolicySource.Profile("p1", "2", ScopeLevel.TENANT, "t", UUID.randomUUID())
        priorOf(image) { it.copy(prior = prior(source)) }.get("prior").get("source").asString() shouldBe "PROFILE"
    }

    @OnPostgres
    fun `a catalogue-default prior and no prior each give the key present and null`(image: String) {
        val withDefault = priorOf(image) { it.copy(prior = prior(PolicySource.CatalogueDefault)) }
        withDefault.has("prior") shouldBe true
        withDefault.get("prior").isNull shouldBe true
        val none = priorOf(image) { it.copy(prior = null) }
        none.has("prior") shouldBe true
        none.get("prior").isNull shouldBe true
    }
}
