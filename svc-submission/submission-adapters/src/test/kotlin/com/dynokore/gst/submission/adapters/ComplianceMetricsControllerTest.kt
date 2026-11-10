package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.catalogue.PermissionKind
import com.dynokore.gst.catalogue.RoleAllowance
import com.dynokore.gst.catalogue.RolePermissions
import com.dynokore.gst.submission.adapters.http.ComplianceMetricsController
import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.ComplianceRefusalAdvice
import com.dynokore.gst.submission.application.compliance.ComplianceMetricsConfig
import com.dynokore.gst.submission.application.compliance.ComplianceMetricsQuery
import com.dynokore.gst.submission.application.compliance.ObligationRowSource
import com.dynokore.gst.submission.contract.model.ComplianceMetricsPage
import com.dynokore.gst.submission.domain.metrics.ObligationRow
import com.dynokore.gst.submission.domain.metrics.Ratio
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.HttpServletRequest
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

class ComplianceMetricsControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 10, 30)
    private val tenantA = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val tenantB = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000b"))
    private val config = ComplianceMetricsConfig(manyVersionsFrom = 3, highAtOrAbove = Ratio(1, 2), trendPeriods = 3)

    private fun entity(n: Int) = String.format(Locale.ROOT, "e%02d", n)

    private class FakeSource(
        private val byTenant: Map<TenantId, List<ObligationRow>>,
    ) : ObligationRowSource {
        var calls = 0

        override fun rows(
            tenant: TenantId,
            entityRef: String?,
            periodRef: String?,
        ): List<ObligationRow> {
            calls++
            return byTenant[tenant]
                .orEmpty()
                .filter { entityRef == null || it.entityRef == entityRef }
                .filter { periodRef == null || it.periodRef == periodRef }
        }
    }

    private fun row(
        id: String,
        entity: String,
        period: String = "2026-Q1",
        submitted: Boolean = true,
    ) = ObligationRow(id, entity, period, today.plusDays(3), true, submitted, RowKind.INVOICE, 1)

    /** Ten entities in one period, e03 with one in-scope document that has no submission; other tenant has one more. */
    private fun source() =
        FakeSource(
            mapOf(
                tenantA to (1..10).map { row("d$it", entity(it), submitted = it != 3) },
                tenantB to listOf(row("other", "e99", submitted = false)),
            ),
        )

    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    /** A role the catalogue lists for [key], read from the map; no role name is written in this test. */
    private fun roleOf(key: String): String = roleMap.rolesFor(key).keys.sorted().first()

    /** A role the catalogue lists for [key] and does not list for [notFor]. */
    private fun roleOfOnly(
        key: String,
        notFor: String,
    ): String = (roleMap.rolesFor(key).keys - roleMap.rolesFor(notFor).keys).sorted().first()

    /** A replica fed by one role grant per entity: the role the catalogue lists for [action], for [principal]. */
    private fun replica(
        principal: String,
        action: String?,
    ) = AuthorityReplica(clock, roleMap).also { replica ->
        replica.onSnapshotComplete(0)
        if (action != null) {
            val role =
                if (action == "submission.view-status") {
                    roleOfOnly(action, "compliance-metrics.view")
                } else {
                    roleOf(action)
                }
            val grant = RoleGrant(role, GrantScope.TENANT, tenantA.toString(), clock.instant().minusSeconds(60))
            (1..10).forEach { n ->
                val record = AuthorityRecord.RoleGrantRecord("$role-$n", RoleGrantState.ACTIVE, grant)
                replica.accept(AuthorityChanged(tenantA, principal, n.toLong(), record))
            }
        }
    }

    private fun controller(
        source: ObligationRowSource,
        replica: AuthorityReplica,
        caller: EndUserPrincipal?,
    ): ComplianceMetricsController {
        val request =
            Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(HttpServletRequest::class.java),
            ) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                caller.takeIf { args[0] == EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE }
            } as HttpServletRequest
        return ComplianceMetricsController(
            ComplianceMetricsQuery(replica, source, config),
            clock,
            object : ObjectProvider<HttpServletRequest> {
                override fun getObject(): HttpServletRequest = request
            },
        )
    }

    private fun user(
        name: String,
        entities: Set<String> = (1..10).map { entity(it) }.toSet(),
        tenant: TenantId = tenantA,
        current: Boolean = true,
    ) = EndUserPrincipal(name, tenant, entities.map { EntityRef(it) }.toSet(), current)

    private fun call(
        controller: ComplianceMetricsController,
        entityRef: String? = null,
        periodRef: String? = null,
        limit: Int = 100,
        cursor: String? = null,
    ): ComplianceMetricsPage =
        CurrentDeadline
            .callWith(Deadline.after(Duration.ofSeconds(5), clock)) {
                controller.getComplianceMetrics(entityRef, periodRef, limit, cursor)
            }.also { it.statusCode.value() shouldBe 200 }
            .body!!

    private fun refusal(block: () -> Unit): Triple<Int, String, String> {
        val thrown = runCatching(block).exceptionOrNull()
        val refused = thrown as? ComplianceRefusal ?: throw AssertionError("expected ComplianceRefusal, got $thrown")
        val response = ComplianceRefusalAdvice().refuse(refused)
        return Triple(response.statusCode.value(), refused.code.code, response.body!!)
    }

    private fun refused(
        controller: ComplianceMetricsController,
        entityRef: String? = null,
        limit: Int = 100,
        cursor: String? = null,
    ) = refusal {
        CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(5), clock)) {
            controller.getComplianceMetrics(entityRef, null, limit, cursor)
        }
    }

    @Test
    fun `INV-AC-12835 a principal granted compliance-metrics view reads ten values and one with the filter`() {
        val source = source()
        val controller = controller(source, replica("holder", "compliance-metrics.view"), user("holder"))
        val all = call(controller)
        all.items.map { it.entityRef } shouldContainExactly (1..10).map { entity(it) }
        val gap = all.items.single { it.coverage.breached }
        gap.entityRef shouldBe "e03"
        gap.coverage.documentsWithoutSubmission shouldBe 1
        gap.deadline.daysToNearestDeadline shouldBe 3
        gap.resubmissionTrend.map { it.periodRef } shouldContainExactly listOf("2026-Q1")
        all.items.filter { it.entityRef != "e03" }.all { !it.coverage.breached } shouldBe true
        val one = call(controller, entityRef = "e03")
        one.items.map { it.entityRef } shouldContainExactly listOf("e03")
        one.items
            .single()
            .correction.resubmission.denominator shouldBe 1
    }

    @Test
    fun `INV-AC-12835 a principal with no grant gets 403 SRT-004 and no row is read`() {
        val source = source()
        val controller = controller(source, replica("stranger", null), user("stranger"))
        val (status, code, body) = refused(controller)
        status shouldBe 403
        code shouldBe "SRT-004"
        body shouldContain "SRT-004"
        source.calls shouldBe 0
        refused(controller, entityRef = "e03").first shouldBe 403
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-12835 a principal granted only submission view-status gets 403`() {
        val source = source()
        val controller = controller(source, replica("lookalike", "submission.view-status"), user("lookalike"))
        refused(controller).first shouldBe 403
        refused(controller, entityRef = "e03").first shouldBe 403
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-12835 the filters entityRef and periodRef return only the matching pair`() {
        val rows =
            FakeSource(
                mapOf(
                    tenantA to
                        listOf(
                            row("a1", "e01", "2026-Q1"),
                            row("a2", "e01", "2026-Q2"),
                            row("a3", "e02", "2026-Q1"),
                            row("a4", "e02", "2026-Q2"),
                        ),
                ),
            )
        val controller = controller(rows, replica("holder", "compliance-metrics.view"), user("holder"))
        call(controller).items.map { it.entityRef to it.periodRef } shouldContainExactly
            listOf("e01" to "2026-Q1", "e01" to "2026-Q2", "e02" to "2026-Q1", "e02" to "2026-Q2")
        call(controller, entityRef = "e02", periodRef = "2026-Q2").items.map {
            it.entityRef to it.periodRef
        } shouldContainExactly
            listOf("e02" to "2026-Q2")
        call(controller, entityRef = "e01").items.map { it.entityRef to it.periodRef } shouldContainExactly
            listOf("e01" to "2026-Q1", "e01" to "2026-Q2")
        call(controller, periodRef = "2026-Q1").items.map { it.entityRef to it.periodRef } shouldContainExactly
            listOf("e01" to "2026-Q1", "e02" to "2026-Q1")
        call(controller, entityRef = "e01")
            .items
            .first()
            .resubmissionTrend
            .map { it.periodRef } shouldContainExactly
            listOf("2026-Q1", "2026-Q2")
    }

    @Test
    fun `INV-AC-12835 a second tenant's rows never appear`() {
        val controller = controller(source(), replica("holder", "compliance-metrics.view"), user("holder"))
        call(controller).items.none { it.entityRef == "e99" } shouldBe true
        val asB = controller(source(), replica("holder", "compliance-metrics.view"), user("holder", tenant = tenantB))
        refused(asB).first shouldBe 403
    }

    @Test
    fun `INV-AC-12835 an entityRef outside the callers scope is 404 SRT-005 and a broken filter is 400 SRT-001`() {
        val source = source()
        val controller =
            controller(source, replica("holder", "compliance-metrics.view"), user("holder", entities = setOf("e01")))
        refused(controller, entityRef = "e02").let { (status, code) -> status to code } shouldBe (404 to "SRT-005")
        source.calls shouldBe 0
        refused(controller, limit = 0).let { (status, code) -> status to code } shouldBe (400 to "SRT-001")
        refused(controller, limit = 501).first shouldBe 400
        refused(controller, entityRef = " ").first shouldBe 400
        refused(controller, cursor = "not-a-cursor").first shouldBe 400
        refused(controller(source, replica("holder", "compliance-metrics.view"), null)).first shouldBe 401
    }

    @Test
    fun `INV-AC-12835 the cursor pages in order and is bound to its filters`() {
        val controller = controller(source(), replica("holder", "compliance-metrics.view"), user("holder"))
        val first = call(controller, limit = 4)
        first.items.map { it.entityRef } shouldContainExactly listOf("e01", "e02", "e03", "e04")
        val second = call(controller, limit = 4, cursor = first.nextCursor)
        second.items.map { it.entityRef } shouldContainExactly listOf("e05", "e06", "e07", "e08")
        val third = call(controller, limit = 4, cursor = second.nextCursor)
        third.items.map { it.entityRef } shouldContainExactly listOf("e09", "e10")
        third.nextCursor shouldBe null
        refused(controller, entityRef = "e03", limit = 4, cursor = first.nextCursor).first shouldBe 400
    }

    @Test
    fun `INV-AC-12835 the main sources of the adapters hold no role name and no permission key`() {
        val root = Path.of("src/main/kotlin")
        val sources =
            Files.walk(root).use { walk ->
                walk.asSequence().filter { it.isRegularFile() && it.extension == "kt" }.toList()
            }
        (sources.isNotEmpty()) shouldBe true
        listOf("tax.approver", "finance.viewer", "compliance-metrics.view").forEach { forbidden ->
            sources.filter { it.readText().contains(forbidden) } shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-12836 the key is held by tax approver and finance viewer only, PERMITTED in every form`() {
        val key = "compliance-metrics.view"
        val expected = setOf("tax.approver", "finance.viewer")
        var forms = 0
        DeploymentForm.entries.forEach { form ->
            forms++
            val cells = RolePermissions.cells(form)
            cells.filterValues { key in it.keys }.keys shouldBe expected
            expected.forEach { cells.getValue(it)[key] shouldBe RoleAllowance.PERMITTED }
            listOf("auditor", "tax.operator").forEach { (cells[it]?.containsKey(key) ?: false) shouldBe false }
        }
        forms shouldBe DeploymentForm.entries.size
        RolePermissions.kind(key) shouldBe PermissionKind.READ
    }
}
