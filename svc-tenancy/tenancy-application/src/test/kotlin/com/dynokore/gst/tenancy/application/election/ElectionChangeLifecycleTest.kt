package com.dynokore.gst.tenancy.application.election

import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.workspace.InMemoryTenantContext
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The lifecycle of a pending election change at the level of [ElectionCommands] with in-memory ports (INV-AC-13527,
 * INV-AC-1511): withdraw, decline, list, never expires, one pending change per entity and variation point, audit.
 */
@Suppress("LargeClass", "TooManyFunctions")
class ElectionChangeLifecycleTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val january = LocalDate.of(2026, 1, 1)
    private val start = Instant.parse("2026-11-07T01:00:00Z")

    private val entities =
        listOf("E1", "E2").associateWith {
            EntityRecord(UUID.randomUUID(), it, "G1", "Acme $it", "COMPANY", null, null, "C$it", "ACTIVE", false, 1)
        }
    private val elections = FakeElections()
    private val changes = FakeChanges { now }
    private val audit = FakeAudit()
    private val published = mutableListOf<EntityProfileChange>()
    private var now = start
    private var current = "identity:USR-001"

    /** Grants as (person, entity, action) triples; the exact strings are compared, never trimmed or folded. */
    private val grants = mutableSetOf<Triple<String, String, String>>()
    private var authorityDown = false

    private object Direct : TransactionRunner {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

    private inline fun <reified T : Any> idle(): T =
        T::class.java.cast(
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                if (method.returnType == List::class.java) emptyList<Any>() else null
            },
        )

    private val structure: StructureStore =
        StructureStore::class.java.cast(
            Proxy.newProxyInstance(StructureStore::class.java.classLoader, arrayOf(StructureStore::class.java)) {
                _,
                method,
                args,
                ->
                when (method.name) {
                    "entity" -> entities[args[0]]
                    "nextStructureVersion" -> 2L
                    "touchEntity" -> null
                    else -> error("${method.name} is not used")
                }
            },
        )

    private val scopePacks =
        object : ScopePackStore by idle() {
            override fun approvedOn(on: LocalDate) =
                listOf(
                    ApprovedSource("MAS", setOf("DAILY")),
                    ApprovedSource("ECB", setOf("DAILY")),
                    ApprovedSource("REUTERS", setOf("DAILY")),
                )
        }

    private val authority =
        ActionAuthority { person, entity, action ->
            check(!authorityDown) { "replica unavailable" }
            Triple(person, entity, action) in grants
        }

    private val exempt =
        object : ExemptPurchaseElectionStore {
            override fun history(entityRef: String) = emptyList<ExemptPurchaseElectionRow>()

            override fun insert(row: ExemptPurchaseElectionRow) = error("not used")
        }

    private val commands =
        ElectionCommands(
            Direct,
            structure,
            elections,
            idle(),
            object : EntityProfileChangePublisher {
                override fun publish(change: EntityProfileChange) {
                    published += change
                }
            },
            facts(),
            idle(),
            scopePacks,
            authority,
            { current },
            changes,
            audit,
            InMemoryTenantContext(tenant),
            ExemptPurchaseElections(
                Direct,
                structure,
                exempt,
                authority,
                { current },
                audit,
                InMemoryTenantContext(tenant),
                Clock.fixed(start, ZoneOffset.UTC),
            ),
            exempt,
        )

    private fun facts() =
        object : EntityFactSource {
            override fun recordsPurchases(entityRef: String) = FactAnswer.TRUE

            override fun hasForeignCurrencyDocuments(entityRef: String) = FactAnswer.TRUE

            override fun claimsInputTaxOnPostingBasis(entityRef: String) = FactAnswer.TRUE
        }

    private val u1 = "identity:USR-001"
    private val u2 = "identity:USR-002"

    private fun grant(
        person: String,
        entity: String,
        vararg actions: String,
    ) = actions.forEach { grants += Triple(person, entity, it) }

    private fun grantAll(entity: String = "E1") {
        grant("identity:USR-001", entity, "entity-config.manage", "election-change.withdraw", "election-change.list")
        grant("identity:USR-002", entity, "entity-config.manage", "election-change.approve", "election-change.list")
    }

    private fun asUser(
        who: String,
        block: () -> ElectionOutcome,
    ): ElectionOutcome {
        current = who
        return block()
    }

    /** E1 has A (INVOICE_DATE) and the rate source MAS elected from 1 January, as a first election. */
    private fun elected() {
        grantAll()
        assertInstanceOf(
            ElectionOutcome.Done::class.java,
            asUser(u1) { commands.recordElection("E1", "INVOICE_DATE", january) },
        )
        assertInstanceOf(
            ElectionOutcome.Done::class.java,
            asUser(u1) { commands.electRateSource("E1", "MAS", "DAILY", january) },
        )
        published.clear()
        audit.entries.clear()
    }

    private fun requestPosting(day: LocalDate = LocalDate.of(2026, 12, 1)) =
        asUser(u1) { commands.recordElection("E1", "POSTING_DATE", day) }

    private fun requestSource(
        entity: String = "E1",
        source: String = "ECB",
    ) = asUser(u1) { commands.electRateSource(entity, source, "DAILY", LocalDate.of(2026, 12, 1)) }

    private fun pendingOf(outcome: ElectionOutcome) =
        assertInstanceOf(ElectionOutcome.Pending::class.java, outcome).change

    private fun snapshot() = Triple(elections.snapshot(), changes.rows.map { it.copy() }, audit.entries.toList())

    // ---- withdraw ----

    @Test
    fun `INV-AC-1511 the requester withdraws the change, the election and the events stay as they were`() {
        elected()
        val change = pendingOf(requestSource())
        audit.entries.clear()
        val before = elections.snapshot()
        val outcome = asUser(u1) { commands.withdrawElectionChange("E1", change.changeRef) }
        val closure = assertInstanceOf(ElectionOutcome.Withdrawn::class.java, outcome).closure
        assertEquals(u1, closure.closedBy)
        assertEquals(now, closure.closedAt)
        assertEquals("WITHDRAWN", changes.row(change.changeRef).state)
        assertEquals(before, elections.snapshot())
        assertTrue(published.isEmpty())
        assertEquals(listOf("withdrawn:$u1:${change.changeRef}:${change.requestedBy}"), audit.entries)
    }

    @Test
    fun `INV-AC-1511 the approver who is not the requester cannot withdraw`() {
        elected()
        val change = pendingOf(requestSource())
        grant(u2, "E1", "election-change.withdraw")
        assertForbiddenAndPending(change, u2) { commands.withdrawElectionChange("E1", change.changeRef) }
    }

    @Test
    fun `INV-AC-1511 a caller with a trailing space is not the requester`() {
        elected()
        val change = pendingOf(requestSource())
        grant("$u1 ", "E1", "election-change.withdraw")
        assertForbiddenAndPending(change, "$u1 ") { commands.withdrawElectionChange("E1", change.changeRef) }
    }

    @Test
    fun `INV-AC-1511 approve without withdraw cannot withdraw, grants only`() {
        elected()
        val change = pendingOf(requestSource())
        grants.removeIf { it.first == u1 && it.third == "election-change.withdraw" }
        grant(u1, "E1", "election-change.approve")
        assertForbiddenAndPending(change, u1) { commands.withdrawElectionChange("E1", change.changeRef) }
    }

    @Test
    fun `INV-AC-1511 a grant on another entity does not allow a withdrawal`() {
        elected()
        val change = pendingOf(requestSource())
        grants.removeIf { it.first == u1 && it.third == "election-change.withdraw" }
        grant(u1, "E2", "election-change.withdraw")
        assertForbiddenAndPending(change, u1) { commands.withdrawElectionChange("E1", change.changeRef) }
    }

    @Test
    fun `INV-AC-1511 an expired or revoked grant is a missing grant`() {
        elected()
        val change = pendingOf(requestSource())
        grants.clear()
        assertForbiddenAndPending(change, u1) { commands.withdrawElectionChange("E1", change.changeRef) }
    }

    @Test
    fun `INV-AC-1511 an unavailable replica fails closed`() {
        elected()
        val change = pendingOf(requestSource())
        authorityDown = true
        assertForbiddenAndPending(change, u1) { commands.withdrawElectionChange("E1", change.changeRef) }
        assertForbiddenAndPending(change, u2) { commands.declineElectionChange("E1", change.changeRef) }
        assertForbiddenAndPending(change, u1) { commands.listElectionChanges("E1", 10, null) }
    }

    @Test
    fun `INV-AC-1511 the action text with a trailing space is not the withdraw permission`() {
        elected()
        val change = pendingOf(requestSource())
        grants.removeIf { it.first == u1 && it.third == "election-change.withdraw" }
        grant(u1, "E1", "election-change.withdraw ")
        assertForbiddenAndPending(change, u1) { commands.withdrawElectionChange("E1", change.changeRef) }
    }

    @Test
    fun `INV-AC-1511 an unknown change, another entity's change and a decided change are not found`() {
        elected()
        grantAll("E2")
        val change = pendingOf(requestSource())
        assertEquals(ElectionOutcome.NotFound, asUser(u1) { commands.withdrawElectionChange("E1", "CHG-NONE") })
        assertEquals(ElectionOutcome.NotFound, asUser(u1) { commands.withdrawElectionChange("E2", change.changeRef) })
        assertEquals(ElectionOutcome.NotFound, asUser(u1) { commands.withdrawElectionChange("E9", change.changeRef) })
        assertInstanceOf(
            ElectionOutcome.Declined::class.java,
            asUser(u2) { commands.declineElectionChange("E1", change.changeRef) },
        )
        assertEquals(ElectionOutcome.NotFound, asUser(u1) { commands.withdrawElectionChange("E1", change.changeRef) })
    }

    @Test
    fun `INV-AC-1511 a withdrawal replayed is 404 and writes one audit entry`() {
        elected()
        val change = pendingOf(requestSource())
        audit.entries.clear()
        assertInstanceOf(
            ElectionOutcome.Withdrawn::class.java,
            asUser(u1) { commands.withdrawElectionChange("E1", change.changeRef) },
        )
        assertEquals(ElectionOutcome.NotFound, asUser(u1) { commands.withdrawElectionChange("E1", change.changeRef) })
        assertEquals(1, audit.entries.size)
    }

    private fun assertForbiddenAndPending(
        change: PendingChange,
        who: String,
        call: () -> ElectionOutcome,
    ) {
        val before = snapshot()
        assertInstanceOf(ElectionOutcome.Forbidden::class.java, asUser(who, call))
        assertEquals("PENDING", changes.row(change.changeRef).state)
        assertEquals(before, snapshot())
    }

    // ---- decline ----

    @Test
    fun `INV-AC-1511 another person holding approve declines, nothing is written to the election`() {
        elected()
        val change = pendingOf(requestSource())
        audit.entries.clear()
        val before = elections.snapshot()
        val closure =
            assertInstanceOf(
                ElectionOutcome.Declined::class.java,
                asUser(u2) { commands.declineElectionChange("E1", change.changeRef) },
            ).closure
        assertEquals(u2, closure.closedBy)
        assertEquals("DECLINED", changes.row(change.changeRef).state)
        assertEquals(before, elections.snapshot())
        assertTrue(published.isEmpty())
        assertEquals(listOf("declined:$u2:${change.changeRef}:$u1"), audit.entries)
    }

    @Test
    fun `INV-AC-1511 the requester cannot decline the own change`() {
        elected()
        val change = pendingOf(requestSource())
        grant(u1, "E1", "election-change.approve")
        assertForbiddenAndPending(change, u1) { commands.declineElectionChange("E1", change.changeRef) }
    }

    @Test
    fun `INV-AC-1511 a holder of entity-config confirm only cannot decline`() {
        elected()
        val change = pendingOf(requestSource())
        grant("identity:USR-003", "E1", "entity-config.confirm", "entity-config.manage")
        assertForbiddenAndPending(change, "identity:USR-003") { commands.declineElectionChange("E1", change.changeRef) }
    }

    @Test
    fun `INV-AC-1511 a decline replayed is 404 and writes one audit entry`() {
        elected()
        val change = pendingOf(requestSource())
        audit.entries.clear()
        asUser(u2) { commands.declineElectionChange("E1", change.changeRef) }
        assertEquals(ElectionOutcome.NotFound, asUser(u2) { commands.declineElectionChange("E1", change.changeRef) })
        assertEquals(1, audit.entries.size)
    }

    // ---- never expires ----

    @Test
    fun `INV-AC-13527 a change 400 days old is still pending, listed and approvable`() {
        elected()
        val change = pendingOf(requestPosting())
        now = now.plus(Duration.ofDays(400))
        assertEquals(listOf(change.changeRef), listed(u2).items.map { it.changeRef })
        assertEquals("PENDING", changes.row(change.changeRef).state)
        assertInstanceOf(
            ElectionOutcome.Approved::class.java,
            asUser(u2) { commands.approveElectionChange("E1", change.changeRef) },
        )
    }

    @Test
    fun `INV-AC-13527 no scheduled job and no expiry column touches the election_change rows`() {
        val root =
            generateSequence(Path.of("").toAbsolutePath()) {
                it.parent
            }.first { Files.exists(it.resolve("ac-keys.tsv")) }
        val skip = setOf("build", ".gradle", ".gradle-cache", ".git")
        val sources =
            Files.walk(root).use { walk ->
                walk
                    .filter { path -> root.relativize(path).none { it.toString() in skip } }
                    .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".sql") }
                    .filter { "/src/main/" in it.toString() }
                    .toList()
            }
        assertTrue(sources.isNotEmpty())
        val scheduled =
            sources.filter {
                Files.readString(it).let { text ->
                    "@Scheduled" in text ||
                        "ScheduledExecutor" in text
                }
            }
        scheduled.forEach {
            val text = Files.readString(it).lowercase()
            assertFalse("election_change" in text || "electionchange" in text, "$it schedules work on election changes")
        }
        val migrations =
            sources
                .filter {
                    it.toString().endsWith(".sql")
                }.joinToString("\n") { Files.readString(it).lowercase() }
        assertFalse(Regex("election_change[^;]*(expires|ttl|expir)").containsMatchIn(migrations))
        assertFalse(
            Regex("(?s)alter table tenancy\\.election_change[^;]*(expires|ttl|expir)").containsMatchIn(migrations),
        )
    }

    // ---- one pending change ----

    @Test
    fun `INV-AC-13527 a second change for the same entity and variation point is 409 and stores nothing`() {
        elected()
        pendingOf(requestSource())
        val before = snapshot()
        val outcome = requestSource(source = "REUTERS")
        assertInstanceOf(ElectionOutcome.Conflict::class.java, outcome)
        assertEquals(before, snapshot())
        assertTrue(published.isEmpty())
        assertInstanceOf(
            ElectionOutcome.Conflict::class.java,
            asUser(u1) {
                commands.electRateSource("E1", "ECB", "DAILY", january.plusYears(2))
            },
        )
        assertEquals(before, snapshot())
    }

    @Test
    fun `INV-AC-13527 another variation point and another entity are 202, a first election is 201`() {
        elected()
        grantAll("E2")
        pendingOf(requestSource())
        // another variation point of the same entity: VP-22 is a first election (201), then a change of it is 202
        assertInstanceOf(
            ElectionOutcome.Pending::class.java,
            asUser(u1) { commands.recordElection("E1", "POSTING_DATE", LocalDate.of(2026, 12, 1)) },
        )
        // another entity, same variation point
        assertInstanceOf(
            ElectionOutcome.Done::class.java,
            asUser(u1) {
                commands.electRateSource("E2", "MAS", "DAILY", january)
            },
        )
        assertInstanceOf(ElectionOutcome.Pending::class.java, requestSource("E2"))
    }

    @Test
    fun `INV-AC-13527 after approval, withdrawal or decline a new change is 202`() {
        elected()
        val first = pendingOf(requestSource())
        asUser(u1) { commands.withdrawElectionChange("E1", first.changeRef) }
        val second = pendingOf(requestSource())
        asUser(u2) { commands.declineElectionChange("E1", second.changeRef) }
        val third = pendingOf(requestSource())
        assertInstanceOf(
            ElectionOutcome.Approved::class.java,
            asUser(u2) {
                commands.approveElectionChange("E1", third.changeRef)
            },
        )
        assertInstanceOf(ElectionOutcome.Pending::class.java, requestSource(source = "REUTERS"))
    }

    // ---- list ----

    @Test
    fun `INV-AC-13527 the list holds the pending changes of the entity only, ordered by time then reference`() {
        elected()
        grantAll("E2")
        val withdrawn = pendingOf(requestSource())
        asUser(u1) { commands.withdrawElectionChange("E1", withdrawn.changeRef) }
        val declined = pendingOf(requestSource())
        asUser(u2) { commands.declineElectionChange("E1", declined.changeRef) }
        val approved = pendingOf(requestSource())
        asUser(u2) { commands.approveElectionChange("E1", approved.changeRef) }
        now = now.plusSeconds(1)
        val pendingA = pendingOf(requestSource(source = "REUTERS"))
        now = now.plusSeconds(1)
        val pendingB = pendingOf(requestPosting())
        asUser(u1) { commands.electRateSource("E2", "MAS", "DAILY", january) }
        pendingOf(requestSource("E2"))
        val page = listed(u2)
        assertEquals(listOf(pendingA, pendingB), page.items)
        assertNull(page.next)
    }

    @Test
    fun `INV-AC-13527 same-instant changes are ordered by reference and pages follow the next token`() {
        elected()
        repeat(3) { index ->
            changes.insertPending("E1", ElectionKind.entries[index % 3], "V$index", u1, now)
        }
        val first = listed(u2, limit = 2)
        assertEquals(2, first.items.size)
        val second = listed(u2, limit = 2, after = first.next)
        assertEquals(1, second.items.size)
        assertNull(second.next)
        val all = (first.items + second.items).map { it.changeRef }
        assertEquals(all.sorted(), all)
        assertEquals(3, all.toSet().size)
    }

    @Test
    fun `INV-AC-13527 listing needs election-change list on the entity and a valid limit`() {
        elected()
        grants.removeIf { it.first == u2 && it.third == "election-change.list" }
        assertInstanceOf(
            ElectionOutcome.Forbidden::class.java,
            asUser(u2) { commands.listElectionChanges("E1", 10, null) },
        )
        grant(u2, "E2", "election-change.list")
        assertInstanceOf(
            ElectionOutcome.Forbidden::class.java,
            asUser(u2) { commands.listElectionChanges("E1", 10, null) },
        )
        grant(u2, "E1", "election-change.list")
        assertInstanceOf(
            ElectionOutcome.Invalid::class.java,
            asUser(u2) { commands.listElectionChanges("E1", 0, null) },
        )
        assertInstanceOf(
            ElectionOutcome.Invalid::class.java,
            asUser(u2) {
                commands.listElectionChanges(
                    "E1",
                    MAX_LIST_LIMIT + 1,
                    null,
                )
            },
        )
        assertInstanceOf(
            ElectionOutcome.Invalid::class.java,
            asUser(u2) {
                commands.listElectionChanges("E1", 10, "not a token")
            },
        )
    }

    private fun listed(
        who: String,
        limit: Int = 50,
        after: String? = null,
    ): PendingChangePage =
        assertInstanceOf(
            ElectionOutcome.Listed::class.java,
            asUser(who) {
                commands.listElectionChanges("E1", limit, after)
            },
        ).page

    // ---- audit ----

    @Test
    fun `INV-AC-1511 a refused call writes no audit entry and no entry stores a role`() {
        elected()
        val change = pendingOf(requestSource())
        audit.entries.clear()
        asUser(u2) { commands.withdrawElectionChange("E1", change.changeRef) }
        asUser(u1) { commands.declineElectionChange("E1", change.changeRef) }
        asUser(u1) { commands.withdrawElectionChange("E1", "CHG-NONE") }
        assertTrue(audit.entries.isEmpty())
        asUser(u2) { commands.declineElectionChange("E1", change.changeRef) }
        assertEquals(1, audit.entries.size)
        assertFalse(audit.entries.single().contains("entity.admin") || audit.entries.single().contains("tax.approver"))
    }

    // ---- the acceptance scenario INV-AC-13527 ----

    @Test
    fun `INV-AC-13527 withdraw, decline, list, 400 days, one pending change per variation point`() {
        elected()
        val b = pendingOf(requestSource(source = "ECB"))
        assertInstanceOf(ElectionOutcome.Conflict::class.java, requestSource(source = "REUTERS"))
        val afterConflict = snapshot()
        asUser(u1) { commands.withdrawElectionChange("E1", b.changeRef) }
        assertEquals(1, audit.entries.count { it.startsWith("withdrawn:$u1:") })
        val c = pendingOf(requestSource(source = "REUTERS"))
        val list1 = listed(u2).items.map { it.changeRef }
        val d = pendingOf(requestPosting(LocalDate.of(2026, 11, 6)))
        val list2 = listed(u2).items.map { it.changeRef }
        now = now.plus(Duration.ofDays(400))
        val list3 = listed(u2).items.map { it.changeRef }
        asUser(u2) { commands.declineElectionChange("E1", c.changeRef) }
        assertEquals(listOf(c.changeRef), list1)
        assertEquals(setOf(c.changeRef, d.changeRef), list2.toSet())
        assertEquals(list2, list3)
        assertEquals(1, audit.entries.count { it.startsWith("declined:$u2:") })
        assertEquals("MAS", elections.history("E1", ElectionKind.EXCHANGE_RATE_SOURCE).single().value)
        assertEquals(afterConflict.first, elections.snapshot())
    }

    // ---- fakes ----

    private class FakeElections : ElectionPolicyStore {
        private val rows = mutableMapOf<Pair<String, ElectionKind>, MutableList<ElectionRecord>>()

        override fun history(
            entityRef: String,
            kind: ElectionKind,
        ): List<ElectionRecord> = rows[entityRef to kind].orEmpty().sortedBy { it.effectiveFrom }

        override fun write(
            entityRef: String,
            groupRef: String,
            kind: ElectionKind,
            value: String,
            basis: String?,
            effectiveFrom: LocalDate,
            actor: String,
            reason: String,
        ) {
            rows.getOrPut(entityRef to kind) { mutableListOf() } +=
                ElectionRecord(value, basis, effectiveFrom, actor, reason)
        }

        fun snapshot() = rows.mapValues { it.value.toList() }
    }

    data class Row(
        val change: PendingChange,
        var state: String,
        var closedBy: String? = null,
    ) {
        val changeRef get() = change.changeRef

        fun copy() = Row(change, state, closedBy)
    }

    private class FakeChanges(
        private val clock: () -> Instant,
    ) : ElectionChangeStore {
        val rows = mutableListOf<Row>()
        private var seq = 0

        fun row(ref: String) = rows.single { it.changeRef == ref }

        fun insertPending(
            entityRef: String,
            kind: ElectionKind,
            value: String,
            by: String,
            at: Instant,
        ): PendingChange {
            seq += 1
            val change =
                PendingChange("CHG-%03d".format(java.util.Locale.ROOT, seq), entityRef, kind, value, null, LocalDate.of(2026, 12, 1), by, at)
            rows += Row(change, "PENDING")
            return change
        }

        override fun request(
            entityRef: String,
            kind: ElectionKind,
            value: String,
            basis: String?,
            effectiveFrom: LocalDate,
            requestedBy: String,
        ): PendingChange? {
            if (rows.any {
                    it.state == "PENDING" && it.change.entityRef == entityRef && it.change.kind == kind
                }
            ) {
                return null
            }
            seq += 1
            val change =
                PendingChange(
                    "CHG-%03d".format(java.util.Locale.ROOT, seq),
                    entityRef,
                    kind,
                    value,
                    basis,
                    effectiveFrom,
                    requestedBy,
                    clock(),
                )
            rows += Row(change, "PENDING")
            return change
        }

        override fun pending(
            entityRef: String,
            changeRef: String,
        ): PendingChange? =
            rows
                .singleOrNull { it.state == "PENDING" && it.change.entityRef == entityRef && it.changeRef == changeRef }
                ?.change

        override fun approve(
            entityRef: String,
            changeRef: String,
            approvedBy: String,
        ): ApprovedChange? =
            pending(entityRef, changeRef)?.let {
                row(changeRef).state = "APPROVED"
                ApprovedChange(it, approvedBy, clock())
            }

        override fun withdraw(
            entityRef: String,
            changeRef: String,
            closedBy: String,
        ) = close(entityRef, changeRef, "WITHDRAWN", closedBy)

        override fun decline(
            entityRef: String,
            changeRef: String,
            closedBy: String,
        ) = close(entityRef, changeRef, "DECLINED", closedBy)

        private fun close(
            entityRef: String,
            changeRef: String,
            state: String,
            by: String,
        ): ClosedChange? =
            pending(entityRef, changeRef)?.let {
                row(changeRef).state = state
                row(changeRef).closedBy = by
                ClosedChange(it, by, clock())
            }

        override fun list(
            entityRef: String,
            limit: Int,
            after: String?,
        ): PendingChangePage {
            val ordered =
                rows
                    .filter { it.state == "PENDING" && it.change.entityRef == entityRef }
                    .map { it.change }
                    .sortedWith(compareBy({ it.requestedAt }, { it.changeRef }))
            val from =
                after?.let { token ->
                    val index = ordered.indexOfFirst { it.changeRef == token }
                    require(index >= 0) { "not a page token" }
                    index + 1
                } ?: 0
            val page = ordered.drop(from).take(limit)
            val more = ordered.size > from + limit
            return PendingChangePage(page, if (more) page.last().changeRef else null)
        }
    }

    private class FakeAudit : ElectionAudit {
        val entries = mutableListOf<String>()

        override fun requested(change: PendingChange) {
            entries += "requested:${change.requestedBy}:${change.changeRef}"
        }

        override fun approved(approval: ApprovedChange) {
            entries += "approved:${approval.approvedBy}:${approval.change.changeRef}"
        }

        override fun withdrawn(closure: ClosedChange) {
            entries += "withdrawn:${closure.closedBy}:${closure.change.changeRef}:${closure.change.requestedBy}"
        }

        override fun declined(closure: ClosedChange) {
            entries += "declined:${closure.closedBy}:${closure.change.changeRef}:${closure.change.requestedBy}"
        }

        override fun exemptPurchaseRecorded(record: ExemptPurchaseAuditRecord) = error("not used")
    }
}
