package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiConflict
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.SoleOperatorController
import com.dynokore.gst.access.adapters.review.AccessReviewController
import com.dynokore.gst.access.contract.model.AccessReviewRequest
import com.dynokore.gst.access.contract.model.AccessReviewResponse
import com.dynokore.gst.access.contract.model.CloseAccessReviewRequest
import com.dynokore.gst.access.contract.model.CloseAccessReviewRequestOutcomesInner
import com.dynokore.gst.access.contract.model.SoleOperatorAckRequest
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.productruntime.catalogue.GstVariationPointCatalogue
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.http.HttpServlet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The environment of the access-review tests (INV-T-15798): a started service context over a real PostgreSQL, one
 * tenant with two `security.officer` holders, a `tenant.admin` and four auditors, and a second tenant. The campaign
 * and the outcomes are driven through the controller, the way the gateway reaches them, with the caller bound.
 */
internal class AccessReviewEnv(
    val context: AssertableWebApplicationContext,
    val tenant: TenantId,
    val other: TenantId,
) {
    val tenants = context.getBean(TenantContext::class.java)
    val review = context.getBean(AccessReviewController::class.java)
    val soleOperator = context.getBean(SoleOperatorController::class.java)

    /** grant reference by holder, as seeded (an identity may hold more than one grant). */
    val seeded = LinkedHashMap<String, MutableList<String>>()

    fun seed(
        identity: String,
        role: Role,
        into: TenantId = tenant,
    ): String =
        Seeds.grant(context, into, SeedSpec(identity, role)).also {
            if (into == tenant) seeded.getOrPut(identity) { mutableListOf() }.add(it)
        }

    fun seedStandard() {
        seed(OFFICER_1, Role.SECURITY_OFFICER)
        seed(OFFICER_1, Role.AUDITOR)
        seed(OFFICER_2, Role.SECURITY_OFFICER)
        seed(ADMIN, Role.TENANT_ADMIN)
        VIEWERS.forEach { seed(it, Role.AUDITOR) }
    }

    fun <T> asActor(
        actor: String,
        block: () -> T,
    ): T = tenants.bind(tenant, actor) { block() }

    fun open(
        period: String = "2026Q3",
        actor: String = OFFICER_1,
        tenantRef: String = tenant.toString(),
    ): AccessReviewResponse =
        asActor(actor) { review.runAccessReview(UUID.randomUUID(), AccessReviewRequest(tenantRef, period)).body!! }

    fun close(
        ref: String,
        actor: String,
        vararg outcomes: Pair<String, String>,
    ): AccessReviewResponse =
        asActor(actor) {
            review
                .closeAccessReview(
                    ref,
                    UUID.randomUUID(),
                    CloseAccessReviewRequest(
                        outcomes.map {
                            CloseAccessReviewRequestOutcomesInner(
                                it.first,
                                CloseAccessReviewRequestOutcomesInner.Outcome.forValue(it.second),
                            )
                        },
                    ),
                ).body!!
        }

    fun refusal(block: () -> Any?): ApiRefusal {
        val thrown = catchThrowable { block() }
        assertThat(thrown).isInstanceOf(ApiRefusal::class.java)
        return thrown as ApiRefusal
    }

    fun conflict(block: () -> Any?) {
        assertThat(catchThrowable { block() }).isInstanceOf(ApiConflict::class.java)
    }

    /** The HTTP status the API-20 envelope of [refusal] carries. */
    fun statusOf(refusal: ApiRefusal): Int = Problems.render(refusal.code, "corr", refusal.detail, null).status

    fun assertRefused(
        refusal: ApiRefusal,
        status: Int,
    ) {
        assertThat(statusOf(refusal)).describedAs(refusal.message).isEqualTo(status)
    }

    fun denied(refusal: ApiRefusal) {
        assertThat(refusal.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        assertThat(statusOf(refusal)).isEqualTo(403)
    }

    fun recordAck(): String =
        asActor(ADMIN) {
            soleOperator
                .recordSoleOperator(UUID.randomUUID(), SoleOperatorAckRequest(tenant.toString(), "external review"))
                .body!!
                .ackRef
        }

    fun withdrawAck(ackRef: String) =
        asActor(ADMIN) {
            soleOperator.withdrawSoleOperatorAcknowledgement(ackRef, UUID.randomUUID())
        }

    // ---- stored rows (read as the owner, bypassing row-level security) ---------------------------------------------

    fun <T> query(
        sql: String,
        vararg args: Any,
        map: (java.sql.ResultSet) -> T,
    ): List<T> =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement(sql).use { s ->
                args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
                s.executeQuery().use { r -> generateSequence { if (r.next()) map(r) else null }.toList() }
            }
        }

    fun count(
        sql: String,
        vararg args: Any,
    ): Int = query(sql, *args) { it.getInt(1) }.single()

    /** grant reference to holder, as the campaign stored them. */
    fun campaignGrants(ref: String): Map<String, String> =
        query(
            "select grant_ref, holder_ref from access.review_campaign_grant where tenant_id = ? and campaign_ref = ?",
            tenant.value,
            ref,
        ) { it.getString(1) to it.getString(2) }.toMap()

    fun grantsHeldBy(
        ref: String,
        holder: String,
    ): List<String> = campaignGrants(ref).filterValues { it == holder }.keys.sorted()

    fun outcomeRows(ref: String): Int =
        count("select count(*) from access.review_outcome where tenant_id = ? and campaign_ref = ?", tenant.value, ref)

    fun campaignRows(period: String): Int =
        count("select count(*) from access.review_campaign where tenant_id = ? and period = ?", tenant.value, period)

    fun grantStatus(grantRef: String): String =
        query("select status from access.role_grant where tenant_id = ? and grant_ref = ?", tenant.value, grantRef) {
            it.getString(1)
        }.single()

    fun authorityChangedRows(): Int =
        count(
            "select count(*) from access.outbox where tenant_id = ? and type = ?",
            tenant.value,
            PlatformEventCatalogue.AUTHORITY_CHANGED.eventType,
        )

    fun auditRows(): List<String> =
        query(
            "select data::text from access.outbox where tenant_id = ? " +
                "and type like '%AuditEntryRecorded%' order by seq",
            tenant.value,
        ) { it.getString(1) }

    // ---- VP-05 through the mounted setPolicy servlet ---------------------------------------------------------------

    private fun servlet(): HttpServlet =
        context
            .getBeansOfType(ServletRegistrationBean::class.java)
            .values
            .single { it.urlMappings.contains(PolicyCommandServlet.MAPPING) }
            .servlet as HttpServlet

    fun setCadence(
        option: String,
        token: String = TOKEN_OFFICER_1,
    ): MockHttpServletResponse {
        val today = LocalDate.ofInstant(context.getBean(Clock::class.java).instant(), SINGAPORE)
        val request = MockHttpServletRequest("POST", "/internal/v1/policies/VP-05")
        request.contentType = "application/json"
        request.addHeader("Authorization", "Bearer $token")
        request.addHeader("Idempotency-Key", UUID.randomUUID().toString())
        request.setContent(
            (
                """{"scopeLevel":"TENANT","scopeRef":"$tenant","selected":[{"option":"$option"}],""" +
                    """"effectiveDate":"$today","reason":"review cadence"}"""
            ).toByteArray(),
        )
        return MockHttpServletResponse().also { servlet().service(request, it) }
    }

    companion object {
        const val OFFICER_1 = "user:officer-1"
        const val OFFICER_2 = "user:officer-2"
        const val ADMIN = "user:tenant-admin"
        const val TOKEN_OFFICER_1 = "token-o1"
        val VIEWERS = listOf("user:viewer-1", "user:viewer-2", "user:viewer-3", "user:viewer-4")
        val SINGAPORE: ZoneId = ZoneId.of("Asia/Singapore")

        /**
         * Runs [block] in a started context. [available] answers VP-05 as `available` through [AvailableVp05] (the
         * pinned row is `planned`); [catalogueMatrix] reads the permission matrix from the gst-catalogue table instead
         * of the fixture rows, which hold no key `access-review.run`.
         */
        fun run(
            available: Boolean = false,
            catalogueMatrix: Boolean = true,
            block: (AccessReviewEnv) -> Unit,
        ) {
            val tenant = TenantId(UUID.randomUUID())
            val other = TenantId(UUID.randomUUID())
            BoundaryFixture
                .runner(catalogueMatrix = catalogueMatrix, form = "SAAS")
                .withBean(
                    EndUserPrincipalResolver::class.java,
                    {
                        TestTokenPrincipals(
                            mapOf(TOKEN_OFFICER_1 to EndUserPrincipal(OFFICER_1, tenant, emptySet())),
                        )
                    },
                ).let {
                    if (available) {
                        it.withBean(
                            VariationPointCatalogue::class.java,
                            { AvailableVp05(GstVariationPointCatalogue("access-svc")) },
                        )
                    } else {
                        it
                    }
                }.run { context ->
                    context.startupFailure?.let { throw it }
                    block(AccessReviewEnv(context, tenant, other))
                }
        }
    }
}
