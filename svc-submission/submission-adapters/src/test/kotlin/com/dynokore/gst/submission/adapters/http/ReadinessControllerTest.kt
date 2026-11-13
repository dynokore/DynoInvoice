package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.adapters.SignedBindings
import com.dynokore.gst.submission.adapters.SubmissionDb
import com.dynokore.gst.submission.adapters.persistence.JooqReadinessCheckStore
import com.dynokore.gst.submission.application.gate.OverrideAuthority
import com.dynokore.gst.submission.application.readiness.GetLatestReadinessCheck
import com.dynokore.gst.submission.contract.model.ReadinessCheckRecord
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.HttpServletRequest
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID

/** `getLatestReadinessCheck` over HTTP with the real store (INV-AC-21137, INV-REQ-500). */
class ReadinessControllerTest {
    private val connection =
        SubmissionDb.connect(SubmissionDb.RUNTIME, SubmissionDb.RUNTIME_PASSWORD).also { it.autoCommit = false }
    private var bound: UUID = UUID.randomUUID()
    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = TenantId(bound)

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val store = JooqReadinessCheckStore(DSL.using(connection, SQLDialect.POSTGRES), tenants)
    private val tenantT = UUID.randomUUID()
    private val tenantU = UUID.randomUUID()
    private val t0 = Instant.parse("2026-11-01T08:00:00Z")
    private var newestOfT: String = ""
    private var authorityCalls = 0

    private val stored =
        listOf(
            """{"filerOfRecord":{"partyRef":"p1","optionKey":"o1"},""",
            """"filingGate":{"variationPoint":"filingGate","optionKey":"BLOCK"},""",
            """"readinessStrictness":{"variationPoint":"s","optionKey":"STRICT"},""",
            """"overrideApproval":{"variationPoint":"a","optionKey":"TWO"},""",
            """"amendmentRoute":{"variationPoint":"r","optionKey":"CN"},"relaxedControls":[]}""",
        ).joinToString("")

    init {
        // three checks of (E1, P1) for T, one of (E1, P3) with outcome values, then one of (E1, P1) for U (highest seq)
        append(tenantT, "E1", "P1", CheckVerdict.NOT_READY, "a1", t0, null)
        append(tenantT, "E1", "P1", CheckVerdict.READY, "a2", t0.plusSeconds(60), null)
        newestOfT = append(tenantT, "E1", "P1", CheckVerdict.NOT_READY, "a3", t0.plusSeconds(120), null).checkId
        append(tenantT, "E1", "P3", CheckVerdict.READY, "a4", t0.plusSeconds(180), stored)
        append(tenantU, "E1", "P1", CheckVerdict.READY, "u1", t0.plusSeconds(240), null)
    }

    @AfterEach
    fun cleanUp() {
        connection.close()
        SubmissionDb.superuser().use { c ->
            listOf(tenantT, tenantU).forEach { t ->
                c.createStatement().use { it.execute("delete from submission.readiness_check where tenant_id = '$t'") }
            }
        }
    }

    @Suppress("LongParameterList")
    private fun append(
        t: UUID,
        entity: String,
        period: String,
        verdict: CheckVerdict,
        approver: String,
        at: Instant,
        outcome: String?,
    ) = inTx(t) { store.append(entity, period, verdict, at, approver, outcome) }

    private fun <T> inTx(
        tenant: UUID,
        block: () -> T,
    ): T {
        SignedBindings.bind(connection, tenant)
        bound = tenant
        return try {
            block().also { connection.commit() }
        } catch (e: Exception) {
            connection.rollback()
            throw e
        }
    }

    private fun controller(
        caller: EndUserPrincipal?,
        allow: Boolean = true,
    ): ReadinessController {
        val authority =
            OverrideAuthority { _, permission, _ ->
                authorityCalls++
                permission shouldBe "validation.override"
                allow
            }
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                caller.takeIf { args[0] == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE }
            } as HttpServletRequest
        return ReadinessController(
            GetLatestReadinessCheck(store, authority),
            object : ObjectProvider<HttpServletRequest> {
                override fun getObject(): HttpServletRequest = request
            },
        )
    }

    private fun user(
        tenant: UUID? = tenantT,
        actor: String = "user-1",
    ) = EndUserPrincipal(
        actor,
        tenant?.let { TenantId(it) },
        if (tenant ==
            null
        ) {
            emptySet()
        } else {
            setOf(EntityRef("E1"))
        },
        true,
    )

    private fun read(
        controller: ReadinessController,
        period: String,
        tenant: UUID = tenantT,
    ): ReadinessCheckRecord =
        inTx(
            tenant,
        ) { controller.getLatestReadinessCheck("E1", period) }.also { it.statusCode.value() shouldBe 200 }.body!!

    private fun refusal(
        controller: ReadinessController,
        period: String,
    ): Triple<Int, String, String> {
        val thrown =
            runCatching {
                inTx(
                    tenantT,
                ) { controller.getLatestReadinessCheck("E1", period) }
            }.exceptionOrNull()
        val refused = thrown as? ComplianceRefusal ?: throw AssertionError("expected ComplianceRefusal, got $thrown")
        val response = ReadinessRefusalAdvice().refuse(refused)
        return Triple(response.statusCode.value(), refused.code.code, response.body!!)
    }

    @Test
    fun `INV-AC-21137 200 with the newest row of the tenant and no row of another tenant`() {
        val body = read(controller(user()), "P1")
        body.checkId shouldBe newestOfT
        body.verdict shouldBe ReadinessCheckRecord.Verdict.NOT_READY
        body.approver shouldBe "a3"
        body.checkedAt.toInstant() shouldBe t0.plusSeconds(120)
        body.outcomeValues shouldBe null
        // the other tenant's row has the highest seq of all: it is invisible to T and the only one for U
        val other = read(controller(user(tenantU)), "P1", tenantU)
        other.approver shouldBe "u1"
        other.checkId shouldBe "chk-" + (newestOfT.removePrefix("chk-").toLong() + 2)
    }

    @Test
    fun `INV-AC-21137 outcomeValues are present when stored`() {
        val body = read(controller(user()), "P3")
        body.approver shouldBe "a4"
        body.outcomeValues!!.filingGate.optionKey shouldBe "BLOCK"
    }

    @Test
    fun `INV-AC-21137 404 SRT-005 for a period with no check`() {
        val (status, code, _) = refusal(controller(user()), "P2")
        status shouldBe 404
        code shouldBe "SRT-005"
    }

    @Test
    fun `INV-AC-21137 403 SRT-004 for a denying authority and the body holds no check`() {
        val (status, code, body) = refusal(controller(user(), allow = false), "P1")
        status shouldBe 403
        code shouldBe "SRT-004"
        body shouldContain "SRT-004"
        body shouldNotContain newestOfT
        body shouldNotContain "a3"
    }

    @Test
    fun `INV-AC-21137 401 without a verified principal`() {
        val (status, code, _) = refusal(controller(null), "P1")
        status shouldBe 401
        authorityCalls shouldBe 0
    }

    @Test
    fun `INV-AC-21137 401 for a principal whose tenant is null and the use case is not called`() {
        val (status, _, _) = refusal(controller(user(tenant = null)), "P1")
        status shouldBe 401
        authorityCalls shouldBe 0
    }
}
