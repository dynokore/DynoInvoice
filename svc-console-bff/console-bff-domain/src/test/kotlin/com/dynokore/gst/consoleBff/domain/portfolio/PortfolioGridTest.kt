package com.dynokore.gst.consoleBff.domain.portfolio

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.time.LocalDate

class PortfolioGridTest {
    private val q3Due = LocalDate.of(2026, 11, 30)
    private val q4Due = LocalDate.of(2027, 2, 28)
    private val filedOn = LocalDate.of(2026, 11, 10)

    private fun fieldNames(c: Class<*>): Set<String> =
        c.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    private fun fact(
        period: String,
        state: PeriodState,
        due: LocalDate,
        filed: LocalDate? = null,
        ext: Boolean = false,
    ) = PeriodFact(period, state, due, filed, ext, null)

    private fun bothPeriods() =
        listOf(
            fact("2026-Q3", PeriodState.FILED, q3Due, filedOn),
            fact("2026-Q4", PeriodState.OPEN, q4Due),
        )

    private fun twoEntities() =
        mapOf(
            "E-001" to
                listOf(
                    fact("2026-Q3", PeriodState.FILED, q3Due, filedOn),
                    fact("2026-Q4", PeriodState.DUE, q4Due).copy(readiness = Readiness.Ready),
                ),
            "E-002" to
                listOf(
                    fact("2026-Q3", PeriodState.LATE, q3Due),
                    fact("2026-Q4", PeriodState.OPEN, q4Due),
                ),
        )

    @Test
    fun `INV-AC-15847 two entities two periods give columns by due date and four cells`() {
        val p = buildPortfolio(twoEntities())
        p.periodRefs shouldBe listOf("2026-Q3", "2026-Q4")
        p.rows.map { it.entityRef } shouldBe listOf("E-002", "E-001")
        val e1 = p.rows.single { it.entityRef == "E-001" }.cells
        val e2 = p.rows.single { it.entityRef == "E-002" }.cells
        e1["2026-Q3"] shouldBe Cell(PeriodState.FILED, filedOn, false, null, null)
        e1["2026-Q4"] shouldBe Cell(PeriodState.DUE, null, false, Verdict.READY, null)
        e2["2026-Q3"] shouldBe Cell(PeriodState.LATE, null, false, null, null)
        e2["2026-Q4"] shouldBe Cell(PeriodState.OPEN, null, false, null, null)
    }

    @Test
    fun `INV-AC-15847 a period filed by every entity is not a column`() {
        val p =
            buildPortfolio(
                mapOf(
                    "E-001" to bothPeriods(),
                    "E-002" to bothPeriods(),
                ),
            )
        p.periodRefs shouldBe listOf("2026-Q4")
        p.rows.forEach { it.cells.keys shouldBe setOf("2026-Q4") }
    }

    @Test
    fun `INV-AC-15847 a LATE cell with a filing date shows that date and the state LATE`() {
        val p =
            buildPortfolio(
                mapOf(
                    "E-001" to listOf(fact("2026-Q3", PeriodState.LATE, q3Due, filedOn)),
                    "E-002" to listOf(fact("2026-Q3", PeriodState.OPEN, q3Due)),
                ),
            )
        val cell = p.rows.single { it.entityRef == "E-001" }.cells["2026-Q3"]
        cell shouldBe Cell(PeriodState.LATE, filedOn, false, null, null)
    }

    @Test
    fun `INV-AC-15847 extensionGranted is copied to cells of every state`() {
        for (s in PeriodState.entries) {
            val p = buildPortfolio(mapOf("E-001" to listOf(fact("2026-Q3", s, q3Due, ext = true))))
            p.rows
                .single()
                .cells
                .getValue("2026-Q3")
                .extensionGranted shouldBe true
        }
    }

    private fun dueCell(readiness: Readiness?): Cell =
        buildPortfolio(mapOf("E-001" to listOf(fact("2026-Q3", PeriodState.DUE, q3Due).copy(readiness = readiness))))
            .rows
            .single()
            .cells
            .getValue("2026-Q3")

    @Test
    fun `INV-AC-15847 a DUE cell shows ready blocked with count or unable to determine`() {
        dueCell(Readiness.Blocked(3)).let {
            it.verdict shouldBe Verdict.BLOCKED
            it.blockedCount shouldBe 3
        }
        dueCell(Readiness.Ready).verdict shouldBe Verdict.READY
        dueCell(Readiness.Ready).blockedCount shouldBe null
        dueCell(Readiness.Unavailable).verdict shouldBe Verdict.UNABLE_TO_DETERMINE
        dueCell(null).verdict shouldBe Verdict.UNABLE_TO_DETERMINE
        dueCell(null).blockedCount shouldBe null
    }

    @Test
    fun `INV-AC-15847 a non DUE cell has no verdict whatever readiness holds`() {
        for (s in PeriodState.entries.filter { it != PeriodState.DUE }) {
            val c =
                buildPortfolio(
                    mapOf("E-001" to listOf(fact("2026-Q3", s, q3Due).copy(readiness = Readiness.Blocked(2)))),
                ).rows
                    .single()
                    .cells
                    .getValue("2026-Q3")
            c.verdict shouldBe null
            c.blockedCount shouldBe null
        }
    }

    @Test
    fun `INV-AC-15847 rows sort by earliest unfiled due date, none last, ties by entityRef`() {
        val p =
            buildPortfolio(
                mapOf(
                    "E-001" to listOf(fact("2026-Q4", PeriodState.OPEN, q4Due)),
                    "E-002" to listOf(fact("2026-Q3", PeriodState.OPEN, q3Due)),
                    "E-000" to listOf(fact("2026-Q3", PeriodState.FILED, q3Due, filedOn)),
                    "E-004" to listOf(fact("2026-Q3", PeriodState.OPEN, q3Due)),
                    "E-003" to listOf(fact("2026-Q3", PeriodState.OPEN, q3Due)),
                ),
            )
        p.rows.map { it.entityRef } shouldBe listOf("E-002", "E-003", "E-004", "E-001", "E-000")
    }

    @Test
    fun `INV-AC-15847 an entity with no fact of a column has no cell for it`() {
        val p =
            buildPortfolio(
                mapOf(
                    "E-001" to listOf(fact("2026-Q3", PeriodState.OPEN, q3Due)),
                    "E-002" to listOf(fact("2026-Q4", PeriodState.OPEN, q4Due)),
                ),
            )
        p.rows
            .single { it.entityRef == "E-001" }
            .cells.keys shouldBe setOf("2026-Q3")
        p.rows
            .single { it.entityRef == "E-002" }
            .cells.keys shouldBe setOf("2026-Q4")
    }

    @Test
    fun `INV-AC-15847 an empty input gives no row and no column`() {
        buildPortfolio(emptyMap()) shouldBe Portfolio(emptyList(), emptyList())
    }

    @Test
    fun `INV-AC-15847 periodRef text is case sensitive so two spellings are two columns`() {
        val p =
            buildPortfolio(
                mapOf(
                    "E-001" to listOf(fact("2026-Q3", PeriodState.OPEN, q3Due)),
                    "E-002" to listOf(fact("2026-q3", PeriodState.OPEN, q3Due)),
                ),
            )
        p.periodRefs shouldBe listOf("2026-Q3", "2026-q3")
    }

    @Test
    fun `INV-AC-15847 the grid has no score total or percentage property`() {
        fieldNames(Cell::class.java) shouldBe
            setOf("state", "filingDate", "extensionGranted", "verdict", "blockedCount")
        fieldNames(PortfolioRow::class.java) shouldBe setOf("entityRef", "cells")
        fieldNames(Portfolio::class.java) shouldBe setOf("periodRefs", "rows")
    }

    @Test
    fun `INV-AC-15847 reversing the input order gives the same result`() {
        val input = twoEntities()
        val reversed = LinkedHashMap<String, List<PeriodFact>>()
        input.entries.reversed().forEach { (k, v) -> reversed[k] = v.reversed() }
        buildPortfolio(reversed) shouldBe buildPortfolio(input)
    }
}
