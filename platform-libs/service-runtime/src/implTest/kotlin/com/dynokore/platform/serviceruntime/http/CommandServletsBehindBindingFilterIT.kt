package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.jobs.JobsHarness.Companion.job
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * INV-T-8198: a request the policy command servlet serves, with a tenant principal, still succeeds with the end-user
 * binding filter in front (the servlet binds again inside the filter's binding, ARC-21): the real servlet over the
 * real writer on PostgreSQL, through the real filter.
 */
class PolicyCommandBehindBindingFilterIT : PolicyHttpBase() {
    @OnPostgres
    fun `INV-AC-8196 setPolicy through the real policy servlet succeeds behind the binding filter`(image: String) {
        val s = Service(image)
        val token =
            s.user(
                "u1",
                "E-1",
                roleSet = setOf("tenant.admin", "tax.approver", "security.officer", "platform.engineer"),
            )
        val front = BindingFront.filter(s.resolver, s.h.scope.tenants)
        val body = request(PolicyOp.SET, ScopeLevel.TENANT, s.tenant.toString())
        s.post(PolicyOp.SET.path("VP-12"), body, token, front = front).ok()
        s.stored() shouldBe 1
        s.post(PolicyOp.SET.path("VP-12"), body, null, front = front).closed(ClosedErrorCode.UNAUTHENTICATED)
        s.writer.calls shouldBe 1
    }
}

/** The same for a job command path. */
class JobCommandBehindBindingFilterIT : JobCommandHttpBase() {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `INV-AC-8196 runJobNow through the real job servlet succeeds behind the binding filter`(image: String) {
        val s = Service(image)
        s.replica.runtime.register(job("front-run"), entities) { }
        val front = BindingFront.filter(principals, s.replica.scope.tenants)
        val path = runPath("front-run")
        s.post(path, """{"entityRef":"E-001","reason":"catch up"}""", front = front).status shouldBe 202
        s.post(path, """{"entityRef":"E-001"}""", token = null, front = front).closed(ClosedErrorCode.UNAUTHENTICATED)
    }
}
