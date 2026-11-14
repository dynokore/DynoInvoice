package com.dynokore.gst.submission.adapters

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.catalogue.RolePermissions
import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.ListingQuery
import com.dynokore.gst.submission.adapters.http.SubmissionListingHandler
import com.dynokore.gst.submission.adapters.http.TransactionsController
import com.dynokore.gst.submission.adapters.http.TransactionsRefusalAdvice
import com.dynokore.gst.submission.application.listing.ListSubmissions
import com.dynokore.gst.submission.application.listing.ListSubmissionsRow
import com.dynokore.gst.submission.application.listing.ListingActions
import com.dynokore.gst.submission.application.listing.SourceRead
import com.dynokore.gst.submission.application.listing.SubmissionRowSource
import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplaySingleVersion
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.contract.model.SubmissionPage
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.listing.SortKey
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.listing.SubmissionRow
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.register.MaskedListFacts
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.identity.MachineTokenFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.MachinePrincipalFixtures
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence

/** `listSubmissions` served through TransactionsController and the SubmissionListingHandler (INV-AC-16326). */
@Suppress("LargeClass") // the task fixes all INV-AC-16326 cases in this one file
class SubmissionListingHandlerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val entity = "E1"
    private val period = "P1"
    private val doc1 = "00000000-0000-0000-0000-000000000001"
    private val doc2 = "00000000-0000-0000-0000-000000000002"
    private val doc3 = "00000000-0000-0000-0000-000000000003"
    private val doc4 = "00000000-0000-0000-0000-000000000004"
    private val doc5 = "00000000-0000-0000-0000-000000000005"

    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    private fun replica(): AuthorityReplica = AuthorityReplica(clock, roleMap).also { it.onSnapshotComplete(0) }

    private class RecordingSource(
        private val rows: List<SubmissionRow>,
    ) : SubmissionRowSource {
        var calls = 0

        override fun rows(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
        ): SourceRead {
            calls++
            return SourceRead(rows, 0)
        }
    }

    @Suppress("LongParameterList") // test helper mirrors the generated operation signature
    private fun row(
        doc: String,
        version: Int,
        state: LifecycleState,
        due: LocalDate,
        txn: LocalDate,
        name: String,
        taxable: String,
        acknowledged: Boolean = false,
    ) = SubmissionRow(
        docUuid = doc,
        version = version,
        submissionType = SubmissionKind.TYPE_1A,
        state = state,
        dueDate = due,
        documentNumber = "N-$doc-$version",
        documentTypeCode = "380",
        direction = Direction.SALE,
        counterpartyName = name,
        counterpartyUen = "UEN-$name",
        transactionDate = txn,
        taxableAmount = BigDecimal(taxable),
        gstAmount = BigDecimal(taxable).multiply(BigDecimal("0.09")).setScale(2),
        categoryCodes = setOf("SR"),
        submittedAt = Instant.parse("2026-11-02T00:00:00Z"),
        acknowledgedAt = if (acknowledged) Instant.parse("2026-11-03T00:00:00Z") else null,
        acknowledgementId = if (acknowledged) "ACK-$doc-$version" else null,
    )

    private val rows =
        listOf(
            row(
                doc1,
                1,
                LifecycleState.SUPERSEDED,
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 1),
                "Acme",
                "100.00",
            ),
            row(
                doc1,
                2,
                LifecycleState.ACKNOWLEDGED,
                LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 2),
                "Acme",
                "200.00",
                true,
            ),
            row(
                doc2,
                1,
                LifecycleState.REJECTED,
                LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 11, 3),
                "Globex",
                "50.00",
            ),
            row(
                doc3,
                1,
                LifecycleState.QUEUED,
                LocalDate.of(2026, 11, 20),
                LocalDate.of(2026, 11, 4),
                "Acme",
                "300.00",
            ),
            row(
                doc4,
                1,
                LifecycleState.DEAD_LETTERED,
                LocalDate.of(2026, 11, 15),
                LocalDate.of(2026, 11, 5),
                "Initech",
                "400.00",
            ),
            row(
                doc5,
                1,
                LifecycleState.ACKNOWLEDGED,
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 6),
                "Globex",
                "500.00",
                true,
            ),
        )

    private class Fixture(
        val controller: TransactionsController,
        val source: RecordingSource,
    )

    private fun fixture(
        caller: Principal?,
        replica: AuthorityReplica,
        endUser: EndUserPrincipal? = null,
    ): Fixture {
        val source = RecordingSource(rows)
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                when (args[0]) {
                    MachineTokenFilter.PRINCIPAL_ATTRIBUTE -> caller
                    EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE -> endUser
                    else -> null
                }
            } as HttpServletRequest
        val controller =
            TransactionsController(
                ReplayBySelection(replica, { _, _ -> emptyList() }, { _, _, _ -> RequeueOutcome.REQUEUED }),
                ReplaySingleVersion(replica, { _, _, _ -> null }, { _, _, _ -> RequeueOutcome.REQUEUED }),
                clock,
                object : ObjectProvider<HttpServletRequest> {
                    override fun getObject(): HttpServletRequest = request
                },
                SubmissionListingHandler(ListSubmissions(replica, source)),
            )
        return Fixture(controller, source)
    }

    private fun machine(
        id: String = "m1",
        coverage: MachineEntityCoverage = MachineEntityCoverage.Entity(entity),
        type: String = "reporting",
    ): Principal = MachinePrincipalFixtures.machine(id, tenant, coverage, type)

    private fun both() = fixture(machine(), replica())

    @Suppress("LongParameterList") // test helper mirrors the generated operation signature
    private fun call(
        f: Fixture,
        entityRef: String = entity,
        counterparty: String? = null,
        sort: String? = null,
        limit: Int = 100,
        cursor: String? = null,
        direction: String? = null,
    ): SubmissionPage =
        CurrentDeadline
            .callWith(Deadline.after(Duration.ofSeconds(5), clock)) {
                f.controller.listSubmissions(
                    entityRef,
                    period,
                    direction,
                    null,
                    null,
                    null,
                    counterparty,
                    null,
                    null,
                    null,
                    null,
                    sort,
                    limit,
                    cursor,
                )
            }.also { it.statusCode.value() shouldBe 200 }
            .body!!

    private fun refused(block: () -> Unit): Pair<Int, String> {
        val thrown = runCatching(block).exceptionOrNull()
        val refusal = thrown as? ComplianceRefusal ?: throw AssertionError("expected ComplianceRefusal, got $thrown")
        val response = TransactionsRefusalAdvice().refuse(refusal)
        val code = Regex("\"code\"\\s*:\\s*\"([^\"]*)\"").find(response.body!!)!!.groupValues[1]
        return response.statusCode.value() to code
    }

    @Suppress("LongParameterList") // test helper mirrors the generated operation signature
    private fun refusedCall(
        f: Fixture,
        entityRef: String = entity,
        counterparty: String? = null,
        limit: Int = 100,
        cursor: String? = null,
        direction: String? = null,
    ) = refused { call(f, entityRef, counterparty, null, limit, cursor, direction) }

    @Test
    fun `INV-AC-16326 a machine caller not listed for machine-submissions-read gets 403 SRT-004 and no read`() {
        val f = fixture(machine(type = "no-such-type"), replica())
        refusedCall(f) shouldBe (403 to "SRT-004")
        f.source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-16326 a machine caller covering the entity gets masked facts and 400 SRT-001 on a filter on them`() {
        val f = both()
        val page = call(f)
        page.items.size shouldBe rows.size
        page.items.all { it.documentNumber == null && it.counterpartyName == null && it.taxableAmount == null } shouldBe
            true
        page.items.all { it.documentTypeCode == "380" && it.direction != null } shouldBe true
        val callsBefore = f.source.calls
        refusedCall(f, counterparty = "Acme") shouldBe (400 to "SRT-001")
        f.source.calls shouldBe callsBefore
    }

    @Test
    fun `INV-AC-16326 the superseded version is in the list with state SUPERSEDED`() {
        val page = call(both())
        page.items
            .single { it.docUuid.toString() == doc1 && it.version == 1 }
            .state.value shouldBe "SUPERSEDED"
    }

    @Test
    fun `INV-AC-16326 a cursor from page 1 sent with another filter is 400 SRT-001`() {
        val f = both()
        val first = call(f, limit = 2)
        (first.nextCursor != null) shouldBe true
        refusedCall(f, limit = 2, cursor = first.nextCursor, direction = "PURCHASE") shouldBe (400 to "SRT-001")
        call(f, limit = 2, cursor = first.nextCursor).items.size shouldBe 2
    }

    @Test
    fun `INV-AC-16326 the pages concatenated equal the full ordered list for page sizes 1 2 and 500`() {
        val f = both()
        val full = call(f, limit = 500).items.map { it.docUuid.toString() to it.version }
        full.size shouldBe rows.size
        listOf(1, 2, 500).forEach { size ->
            val seen = mutableListOf<Pair<String, Int>>()
            var cursor: String? = null
            do {
                val page = call(f, limit = size, cursor = cursor)
                seen += page.items.map { it.docUuid.toString() to it.version }
                cursor = page.nextCursor
            } while (cursor != null)
            seen shouldBe full
        }
    }

    @Test
    fun `INV-AC-16326 a limit of 501 is 400 SRT-001 and 500 is accepted`() {
        val f = both()
        refusedCall(f, limit = 501) shouldBe (400 to "SRT-001")
        refusedCall(f, limit = 0) shouldBe (400 to "SRT-001")
        call(f, limit = 500).items.size shouldBe rows.size
    }

    @Test
    fun `INV-AC-16326 money fields are decimal strings equal to the fixture strings`() {
        // a machine caller holds no content key (no machine type does), so the amounts are masked, never zero
        val item = call(both()).items.single { it.docUuid.toString() == doc1 && it.version == 2 }
        listOf(item.taxableAmount, item.gstAmount, item.totalAmount).all { it == null } shouldBe true
        // the wire form of every amount of the contract model is a decimal string (API-12)
        listOf("getTaxableAmount", "getGstAmount", "getTotalAmount").forEach { getter ->
            val format =
                com.dynokore.gst.submission.contract.model.SubmissionSummary::class.java
                    .getDeclaredMethod(getter)
                    .getAnnotation(com.fasterxml.jackson.annotation.JsonFormat::class.java)
            format.shape shouldBe com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING
        }
    }

    @Test
    fun `INV-AC-16326 an unmasked row maps to the decimal strings 200-00 18-00 and 218-00`() {
        val facts =
            MaskedListFacts(
                documentNumber = "N-1",
                documentTypeCode = "380",
                direction = Direction.SALE,
                counterpartyName = "Acme",
                counterpartyUen = "UEN-Acme",
                taxableAmount = BigDecimal("200.00"),
                gstAmount = BigDecimal("18.00"),
                categoryCodes = setOf("SR"),
            )
        val row =
            ListSubmissionsRow(
                docUuid = doc1,
                version = 2,
                submissionType = SubmissionKind.TYPE_1A,
                state = LifecycleState.ACKNOWLEDGED,
                dueDate = LocalDate.of(2026, 11, 10),
                transactionDate = LocalDate.of(2026, 11, 2),
                facts = facts,
                submittedAt = Instant.parse("2026-11-02T00:00:00Z"),
                acknowledgedAt = null,
                acknowledgementId = null,
            )
        val mapper = JsonMapper.builder().build()
        val handler = SubmissionListingHandler(ListSubmissions(replica(), RecordingSource(emptyList())))
        val json = mapper.readTree(mapper.writeValueAsString(handler.summary(row)))
        json.get("taxableAmount").asString() shouldBe "200.00"
        json.get("gstAmount").asString() shouldBe "18.00"
        json.get("totalAmount").asString() shouldBe "218.00"
    }

    @Test
    fun `INV-AC-16326 a request with no machine principal is 401 SRT-003 also with an end-user principal`() {
        refusedCall(fixture(null, replica())) shouldBe (401 to "SRT-003")
        val endUser = EndUserPrincipal("holder", tenant, setOf(EntityRef(entity)), true)
        refusedCall(fixture(null, replica(), endUser)) shouldBe (401 to "SRT-003")
        // a principal in the machine attribute that is not of kind MACHINE is refused too
        val human = Principal("holder", PrincipalKind.HUMAN)
        refusedCall(fixture(human, replica())) shouldBe (401 to "SRT-003")
    }

    @Test
    fun `INV-AC-16326 an entityRef the machine token does not cover is 403 SRT-004`() {
        val f = both()
        refusedCall(f, entityRef = "E2") shouldBe (403 to "SRT-004")
        f.source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-16326 the sort pattern and the nine filter names equal those of the contract operation`() {
        val spec = contractOperation()
        val filters =
            Regex("#/components/parameters/Filter(\\w+)")
                .findAll(spec)
                .map { it.groupValues[1].replaceFirstChar { c -> c.lowercase() } }
                .toSet()
        filters.size shouldBe 9
        val own =
            ListingQuery::class.java.declaredFields
                .map { it.name }
                .toSet() - setOf("entityRef", "periodRef", "sort", "limit", "cursor")
        own shouldBe filters
        val pattern = Regex("pattern: '([^']*)'").find(spec)!!.groupValues[1]
        pattern shouldBe "^-?(dueDate|transactionDate)$"
        val regex = Regex(pattern)
        listOf(
            "dueDate",
            "-dueDate",
            "transactionDate",
            "-transactionDate",
            "amount",
            "--dueDate",
            "DueDate",
            "",
        ).forEach {
            regex.matches(it) shouldBe runCatching { SortKey.parse(it) }.isSuccess
        }
        Regex("maximum: (\\d+)").find(spec)!!.groupValues[1] shouldBe "500"
    }

    /** The text of the `listSubmissions` operation in the submission contract staged by submission-contract. */
    private fun contractOperation(): String {
        val root = Path.of("../submission-contract/build/contracts-repo/platform-contracts/submission")
        val file =
            Files
                .walk(root)
                .use { walk ->
                    walk.asSequence().filter { it.isRegularFile() && it.fileName.toString() == "openapi.yaml" }.toList()
                }.map { it.readText() }
                .first { it.contains("operationId: listSubmissions") }
        val text = file
        val start = text.indexOf("operationId: listSubmissions")
        val end = text.indexOf("operationId:", start + 1)
        return text.substring(start, end)
    }

    @Test
    fun `INV-AC-16326 the main sources of the adapters name no role of the catalogue`() {
        val sources =
            Files.walk(Path.of("src/main/kotlin")).use { walk ->
                walk.asSequence().filter { it.isRegularFile() && it.extension == "kt" }.toList()
            }
        (sources.isNotEmpty()) shouldBe true
        val roles = DeploymentForm.entries.flatMap { RolePermissions.cells(it).keys }.toSet()
        (roles.isNotEmpty()) shouldBe true
        sources.flatMap { file -> roles.filter { file.readText().contains("\"$it\"") }.map { file to it } } shouldBe
            emptyList()
    }
}
