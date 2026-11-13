package com.dynokore.gst.submission.application.listing

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.catalogue.RolePermissions
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.listing.SubmissionFilter
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.listing.SubmissionRow
import com.dynokore.gst.submission.domain.listing.SubmissionRows
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.reconciliation.ReconciliationBucket
import com.dynokore.gst.submission.domain.register.ListFacts
import com.dynokore.gst.submission.domain.register.ListFactsPolicy
import com.dynokore.gst.submission.domain.register.MaskedListFacts
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** The listing use case over fakes (INV-REQ-975, INV-AC-21818). */
class ListSubmissionsTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val entityRef = "E1"
    private val periodRef = "P1"
    private val holder = Principal("holder", PrincipalKind.HUMAN)
    private val statusOnly = Principal("status-only", PrincipalKind.HUMAN)
    private val stranger = Principal("stranger", PrincipalKind.HUMAN)

    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    /** A role of the catalogue that holds both scoped keys; never written as a literal (INV-Q-10027). */
    private fun roleHoldingBoth(): String =
        (roleMap.rolesFor(ListingActions.VIEW_STATUS).keys intersect roleMap.rolesFor(ListingActions.VIEW_CONTENT).keys)
            .sorted()
            .first()

    /** A role that holds [key] and not [notFor]; never written as a literal. */
    private fun roleOnly(
        key: String,
        notFor: String,
    ): String = (roleMap.rolesFor(key).keys - roleMap.rolesFor(notFor).keys).sorted().first()

    private fun grant(
        replica: AuthorityReplica,
        principal: Principal,
        role: String,
    ) {
        val roleGrant = RoleGrant(role, GrantScope.ENTITY, entityRef, clock.instant().minusSeconds(60))
        val record = AuthorityRecord.RoleGrantRecord("$role-$entityRef", RoleGrantState.ACTIVE, roleGrant)
        replica.accept(AuthorityChanged(tenant, principal.id, 1, record))
    }

    /** A warm replica (a snapshot has completed) holding the given (principal, role) grants, none by default. */
    private fun replicaFor(vararg grants: Pair<Principal, String>): AuthorityReplica =
        AuthorityReplica(clock, roleMap).also { replica ->
            replica.onSnapshotComplete(0)
            grants.forEach { (principal, role) -> grant(replica, principal, role) }
        }

    private class RecordingSource(
        private val read: SourceRead,
    ) : SubmissionRowSource {
        val calls = mutableListOf<Triple<TenantId, String, String>>()

        override fun rows(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
        ): SourceRead {
            calls += Triple(tenant, entityRef, periodRef)
            return read
        }
    }

    @Suppress("LongParameterList")
    private fun row(
        docUuid: String,
        version: Int,
        state: LifecycleState,
        submissionType: SubmissionKind,
        direction: Direction,
        dueDate: LocalDate,
        transactionDate: LocalDate,
        counterpartyName: String,
        counterpartyUen: String,
        taxableAmount: BigDecimal,
        categoryCodes: Set<String>,
        acknowledged: Boolean,
    ) = SubmissionRow(
        docUuid = docUuid,
        version = version,
        submissionType = submissionType,
        state = state,
        dueDate = dueDate,
        documentNumber = "DOC-$docUuid-$version",
        documentTypeCode = "380",
        direction = direction,
        counterpartyName = counterpartyName,
        counterpartyUen = counterpartyUen,
        transactionDate = transactionDate,
        taxableAmount = taxableAmount,
        gstAmount = taxableAmount.multiply(BigDecimal("0.09")).setScale(2),
        categoryCodes = categoryCodes,
        submittedAt = if (state == LifecycleState.QUEUED) null else Instant.parse("2026-11-02T00:00:00Z"),
        acknowledgedAt = if (acknowledged) Instant.parse("2026-11-03T00:00:00Z") else null,
        acknowledgementId = if (acknowledged) "ACK-$docUuid-$version" else null,
    )

    private val d1v1 =
        row(
            "D1",
            1,
            LifecycleState.SUPERSEDED,
            SubmissionKind.TYPE_1A,
            Direction.SALE,
            LocalDate.of(2026, 11, 10),
            LocalDate.of(2026, 11, 1),
            "Acme",
            "UEN1",
            BigDecimal("100.00"),
            setOf("SR"),
            false,
        )
    private val d1v2 =
        row(
            "D1",
            2,
            LifecycleState.ACKNOWLEDGED,
            SubmissionKind.TYPE_1A,
            Direction.SALE,
            LocalDate.of(2026, 11, 10),
            LocalDate.of(2026, 11, 2),
            "Acme",
            "UEN1",
            BigDecimal("200.00"),
            setOf("SR"),
            true,
        )
    private val d2v1 =
        row(
            "D2",
            1,
            LifecycleState.REJECTED,
            SubmissionKind.TYPE_1B,
            Direction.PURCHASE,
            LocalDate.of(2026, 11, 5),
            LocalDate.of(2026, 11, 3),
            "Globex",
            "UEN2",
            BigDecimal("50.00"),
            setOf("ZR"),
            false,
        )
    private val d3v1 =
        row(
            "D3",
            1,
            LifecycleState.QUEUED,
            SubmissionKind.TYPE_2,
            Direction.SALE,
            LocalDate.of(2026, 11, 20),
            LocalDate.of(2026, 11, 4),
            "Acme",
            "UEN1",
            BigDecimal("300.00"),
            setOf("EX"),
            false,
        )
    private val d4v1 =
        row(
            "D4",
            1,
            LifecycleState.DEAD_LETTERED,
            SubmissionKind.TYPE_3,
            Direction.SALE,
            LocalDate.of(2026, 11, 15),
            LocalDate.of(2026, 11, 5),
            "Initech",
            "UEN3",
            BigDecimal("400.00"),
            setOf("SR"),
            false,
        )
    private val d5v1 =
        row(
            "D5",
            1,
            LifecycleState.ACKNOWLEDGED,
            SubmissionKind.TYPE_1A,
            Direction.PURCHASE,
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 6),
            "Globex",
            "UEN2",
            BigDecimal("500.00"),
            setOf("ZR"),
            true,
        )
    private val rows = listOf(d1v1, d1v2, d2v1, d3v1, d4v1, d5v1)

    @Suppress("LongParameterList")
    private fun request(
        principal: Principal = holder,
        filter: SubmissionFilter = SubmissionFilter(),
        sort: String? = null,
        cursor: String? = null,
        limit: Int = 10,
        maxLimit: Int = 500,
    ) = ListSubmissionsRequest(tenant, principal, entityRef, periodRef, filter, sort, cursor, limit, maxLimit)

    private fun maskedFacts(
        row: SubmissionRow,
        mayViewContent: Boolean,
    ) = ListFactsPolicy.mask(
        ListFacts(
            row.documentNumber,
            row.documentTypeCode,
            row.direction,
            row.counterpartyName,
            row.counterpartyUen,
            row.taxableAmount,
            row.gstAmount,
            row.categoryCodes,
        ),
        mayViewContent,
    )

    private fun expected(
        filtered: List<SubmissionRow>,
        mayViewContent: Boolean,
    ) = SubmissionRows.assemble(filtered).map { it.docUuid to it.version to maskedFacts(it, mayViewContent) }

    private fun ListSubmissionsResult.Page.keys() = rows.map { it.docUuid to it.version to it.facts }

    private fun captured(
        loggerName: String,
        block: () -> Unit,
    ): List<LogRecord> {
        val logger = Logger.getLogger(loggerName)
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        logger.addHandler(handler)
        val old = logger.level
        logger.level = Level.ALL
        try {
            block()
        } finally {
            logger.removeHandler(handler)
            logger.level = old
        }
        return records
    }

    private fun LogRecord.text(): String = java.text.MessageFormat.format(message, *parameters.orEmpty())

    @Test
    fun `INV-AC-21818 the caller without submission-view-status gets Forbidden and the source records zero calls`() {
        val source = RecordingSource(SourceRead(rows, 0))
        val uc = ListSubmissions(replicaFor(), source)
        uc.handle(request(principal = stranger), deadline) shouldBe ListSubmissionsResult.Forbidden
        source.calls.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-21818 without doc-view-content facts are masked and a masked-fact filter is refused with zero calls`() {
        val replica = replicaFor(statusOnly to roleOnly(ListingActions.VIEW_STATUS, ListingActions.VIEW_CONTENT))
        val source = RecordingSource(SourceRead(rows, 0))
        val uc = ListSubmissions(replica, source)
        val page = uc.handle(request(principal = statusOnly), deadline).shouldBeInstanceOf<ListSubmissionsResult.Page>()
        page.keys() shouldBe expected(rows, mayViewContent = false)

        val refusedSource = RecordingSource(SourceRead(rows, 0))
        val refusedUc = ListSubmissions(replica, refusedSource)
        refusedUc.handle(
            request(principal = statusOnly, filter = SubmissionFilter(counterparty = "Acme")),
            deadline,
        ) shouldBe ListSubmissionsResult.Refused(ListSubmissionsRefusal.MASKED_FACT_FILTER)
        refusedSource.calls.shouldBeEmpty()
    }

    @Test
    fun `INV-AC-21818 the superseded version appears in the page with state SUPERSEDED`() {
        val uc = ListSubmissions(replicaFor(holder to roleHoldingBoth()), RecordingSource(SourceRead(rows, 0)))
        val page = uc.handle(request(), deadline).shouldBeInstanceOf<ListSubmissionsResult.Page>()
        page.rows.any { it.docUuid == "D1" && it.version == 1 && it.state == LifecycleState.SUPERSEDED } shouldBe true
    }

    @Test
    fun `INV-AC-21818 pages concatenated for page sizes 1, 2 and 500 equal the full ordered list`() {
        val full = expected(rows, mayViewContent = true)
        listOf(1, 2, 500).forEach { size ->
            val uc = ListSubmissions(replicaFor(holder to roleHoldingBoth()), RecordingSource(SourceRead(rows, 0)))
            val collected = mutableListOf<Triple<String, Int, MaskedListFacts>>()
            var cursor: String? = null
            do {
                val result = uc.handle(request(limit = size, cursor = cursor), deadline)
                val page = result.shouldBeInstanceOf<ListSubmissionsResult.Page>()
                collected += page.keys().map { Triple(it.first.first, it.first.second, it.second) }
                cursor = page.nextCursor
            } while (cursor != null)
            collected.map { it.first to it.second to it.third } shouldBe full
        }
    }

    @Test
    fun `INV-AC-21818 a cursor continues with the same filter and sort, refused with a different one`() {
        val uc = ListSubmissions(replicaFor(holder to roleHoldingBoth()), RecordingSource(SourceRead(rows, 0)))
        val page1Result = uc.handle(request(limit = 2), deadline)
        val page1 = page1Result.shouldBeInstanceOf<ListSubmissionsResult.Page>()
        val cursor = checkNotNull(page1.nextCursor)

        val continuedResult = uc.handle(request(limit = 10, cursor = cursor), deadline)
        val continued = continuedResult.shouldBeInstanceOf<ListSubmissionsResult.Page>()
        continued.keys() shouldBe expected(rows, mayViewContent = true).drop(2)

        uc.handle(
            request(limit = 10, cursor = cursor, filter = SubmissionFilter(direction = Direction.SALE)),
            deadline,
        ) shouldBe ListSubmissionsResult.Refused(ListSubmissionsRefusal.CURSOR_MISMATCH)
        uc.handle(
            request(limit = 10, cursor = cursor, sort = "dueDate"),
            deadline,
        ) shouldBe ListSubmissionsResult.Refused(ListSubmissionsRefusal.CURSOR_MISMATCH)
    }

    @Test
    fun `INV-AC-21818 a cursor whose last row left the list resumes after its position, never from the first row`() {
        listOf<String?>(null, "-transactionDate", "dueDate", "-dueDate").forEach { sort ->
            val first = ListSubmissions(replicaFor(holder to roleHoldingBoth()), RecordingSource(SourceRead(rows, 0)))
            val page1 =
                first
                    .handle(request(limit = 2, sort = sort), deadline)
                    .shouldBeInstanceOf<ListSubmissionsResult.Page>()
            val cursor = checkNotNull(page1.nextCursor)
            val lastKey = page1.keys().last().first
            val remaining = rows.filterNot { it.docUuid == lastKey.first && it.version == lastKey.second }
            val second =
                ListSubmissions(replicaFor(holder to roleHoldingBoth()), RecordingSource(SourceRead(remaining, 0)))
            val full =
                second
                    .handle(request(limit = 500, sort = sort), deadline)
                    .shouldBeInstanceOf<ListSubmissionsResult.Page>()
                    .keys()
            val resumed =
                second
                    .handle(request(limit = 500, cursor = cursor, sort = sort), deadline)
                    .shouldBeInstanceOf<ListSubmissionsResult.Page>()
                    .keys()
            val seenOnPage1 = page1.keys().map { it.first }.toSet()
            resumed.map { it.first }.none { it in seenOnPage1 } shouldBe true
            resumed shouldBe full.dropWhile { it.first in seenOnPage1 }
        }
    }

    @Test
    fun `INV-AC-21818 a bad sort and a limit above maxLimit are refused`() {
        val uc = ListSubmissions(replicaFor(holder to roleHoldingBoth()), RecordingSource(SourceRead(rows, 0)))
        uc.handle(request(sort = "bogus"), deadline) shouldBe
            ListSubmissionsResult.Refused(ListSubmissionsRefusal.BAD_SORT)
        uc.handle(
            request(limit = 501, maxLimit = 500),
            deadline,
        ) shouldBe ListSubmissionsResult.Refused(ListSubmissionsRefusal.LIMIT_OUT_OF_RANGE)
        uc.handle(request(limit = 0), deadline) shouldBe
            ListSubmissionsResult.Refused(ListSubmissionsRefusal.LIMIT_OUT_OF_RANGE)
    }

    @Test
    fun `INV-AC-21818 withoutFacts 2 is logged at WARN once with no document, tenant or party value`() {
        val uc = ListSubmissions(replicaFor(holder to roleHoldingBoth()), RecordingSource(SourceRead(rows, 2)))
        val records = captured(ListSubmissions::class.java.name) { uc.handle(request(), deadline) }
        val warnings = records.filter { it.level == Level.WARNING }
        warnings.size shouldBe 1
        val text = warnings.single().text()
        text shouldBe "list read left out 2 row(s) without usable facts"
        rows.forEach { r ->
            text shouldNotContain r.docUuid
            text shouldNotContain r.counterpartyName
            text shouldNotContain r.counterpartyUen
        }
        text shouldNotContain tenant.value.toString()
        text shouldNotContain entityRef
    }

    @Test
    fun `INV-AC-21818 each of the nine filters narrows the page to the matching rows`() {
        val uc = ListSubmissions(replicaFor(holder to roleHoldingBoth()), RecordingSource(SourceRead(rows, 0)))

        fun page(filter: SubmissionFilter): List<Pair<String, Int>> {
            val result = uc.handle(request(filter = filter), deadline)
            return result.shouldBeInstanceOf<ListSubmissionsResult.Page>().rows.map { it.docUuid to it.version }
        }

        page(SubmissionFilter(direction = Direction.SALE)) shouldBe listOf("D1" to 1, "D1" to 2, "D3" to 1, "D4" to 1)
        page(SubmissionFilter(submissionType = SubmissionKind.TYPE_1A)) shouldBe listOf("D1" to 1, "D1" to 2, "D5" to 1)
        page(SubmissionFilter(state = LifecycleState.ACKNOWLEDGED)) shouldBe listOf("D1" to 2, "D5" to 1)
        page(SubmissionFilter(categoryCode = "SR")) shouldBe listOf("D1" to 1, "D1" to 2, "D4" to 1)
        page(SubmissionFilter(counterparty = "Acme")) shouldBe listOf("D1" to 1, "D1" to 2, "D3" to 1)
        page(SubmissionFilter(amountMin = BigDecimal("200.00"))) shouldBe
            listOf("D1" to 2, "D3" to 1, "D4" to 1, "D5" to 1)
        page(SubmissionFilter(amountMax = BigDecimal("100.00"))) shouldBe listOf("D1" to 1, "D2" to 1)
        page(SubmissionFilter(acknowledged = true)) shouldBe listOf("D1" to 2, "D5" to 1)
        page(SubmissionFilter(bucket = ReconciliationBucket.NEVER_SENT)) shouldBe listOf("D3" to 1)
    }

    @Test
    fun `INV-AC-21818 this use case's own source holds no literal string equal to a role name of the catalogue`() {
        val roles = RolePermissions.cells(DeploymentForm.SAAS).keys + RolePermissions.cells(DeploymentForm.PRIVATE).keys
        val moduleRoot = Path.of(System.getProperty("user.dir"))
        val files =
            listOf(
                "src/main/kotlin/com/dynokore/gst/submission/application/listing/ListSubmissions.kt",
                "src/test/kotlin/com/dynokore/gst/submission/application/listing/ListSubmissionsTest.kt",
            ).map { moduleRoot.resolve(it) }
        files.all { Files.isRegularFile(it) } shouldBe true
        val findings =
            files.flatMap { file ->
                val text = Files.readString(file)
                roles.filter { "\"$it\"" in text }.map { "$file: literal \"$it\"" }
            }
        findings.shouldBeEmpty()
    }
}
