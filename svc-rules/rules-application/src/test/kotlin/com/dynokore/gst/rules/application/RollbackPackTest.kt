package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.pack.EarlierPack
import com.dynokore.gst.rules.domain.pack.PackCorrectionResult.Refused
import com.dynokore.gst.rules.domain.pack.PackStatus
import com.dynokore.gst.rules.domain.pack.SupersedingSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RollbackPackTest {
    private val date = LocalDate.of(2026, 3, 1)
    private val key = "regulatory-pack.rollback"

    private data class State(
        val rows: List<CorrectionPackRow>,
        val artefactCopies: List<Pair<String, String>>,
    )

    private class FakeRegistry(
        var state: State,
        val earlierOf: Map<String, EarlierPack>,
    ) : PackCorrectionRegistry {
        var transactions = 0

        override fun <T> inTransaction(work: (PackCorrectionRegistryTx) -> T): T {
            transactions++
            val before = state
            return try {
                work(
                    object : PackCorrectionRegistryTx {
                        override fun find(
                            packType: String,
                            version: String,
                        ) = state.rows.firstOrNull { it.packType == packType && it.version == version }

                        override fun latestSuperseded(bad: CorrectionPackRow) = earlierOf[bad.version]

                        override fun insertSuperseding(
                            spec: SupersedingSpec,
                            copyFrom: String,
                        ): CorrectionPackRow {
                            val row =
                                CorrectionPackRow(
                                    spec.packType,
                                    spec.version,
                                    spec.effectiveFrom,
                                    PackStatus.STAGED,
                                    spec.contentRef,
                                )
                            state = State(state.rows + row, state.artefactCopies + (copyFrom to spec.version))
                            return row
                        }
                    },
                )
            } catch (e: Exception) {
                state = before
                throw e
            }
        }
    }

    private val badRow = CorrectionPackRow("GST", "2026.3", date, PackStatus.ARMED, "c3")
    private val earlierRow = CorrectionPackRow("GST", "2026.2", date.minusMonths(3), PackStatus.SUPERSEDED, "c2")

    private fun registry(bad: CorrectionPackRow = badRow) =
        FakeRegistry(State(listOf(earlierRow, bad), emptyList()), mapOf("2026.3" to EarlierPack("2026.2", "c2")))

    private class Permission(
        val allow: Boolean,
    ) : PackCorrectionPermissionPort {
        val calls = mutableListOf<Pair<String, String>>()

        override fun mayDo(
            userId: String,
            key: String,
        ): Boolean {
            calls += userId to key
            return allow
        }
    }

    @Test
    fun `INV-AC-24013 rollback writes exactly one row and its artefact copies and nothing else`() {
        val reg = registry()
        val result = RollbackPack(Permission(true), reg).execute("GST", "2026.3", date, "user-1", key)
        val created = (result as RollbackPackResult.Created).row
        created.version shouldBe "2026.3-rollback"
        created.effectiveFrom shouldBe date
        created.contentRef shouldBe "c2"
        reg.state.rows shouldBe listOf(earlierRow, badRow, created)
        reg.state.artefactCopies shouldBe listOf("2026.2" to "2026.3-rollback")
        reg.transactions shouldBe 1
    }

    @Test
    fun `INV-AC-24013 a repeat with the same date returns the stored row and writes nothing`() {
        val reg = registry()
        val uc = RollbackPack(Permission(true), reg)
        val first = (uc.execute("GST", "2026.3", date, "user-1", key) as RollbackPackResult.Created).row
        val after = reg.state
        uc.execute("GST", "2026.3", date, "user-1", key) shouldBe RollbackPackResult.Repeated(first)
        reg.state shouldBe after
    }

    @Test
    fun `INV-AC-24013 a different date is refused and the registry equals its prior state`() {
        val reg = registry()
        val before = reg.state
        val today = LocalDate.of(2026, 11, 17)
        RollbackPack(Permission(true), reg).execute("GST", "2026.3", today, "user-1", key) shouldBe
            RollbackPackResult.Refused(Refused.DateMismatch(today, date))
        reg.state shouldBe before
    }

    @Test
    fun `INV-AC-24013 a different date after the first rollback is refused, not answered from the store`() {
        val reg = registry()
        val uc = RollbackPack(Permission(true), reg)
        uc.execute("GST", "2026.3", date, "user-1", key)
        val after = reg.state
        uc.execute("GST", "2026.3", date.plusDays(1), "user-1", key) shouldBe
            RollbackPackResult.Refused(Refused.DateMismatch(date.plusDays(1), date))
        reg.state shouldBe after
    }

    @Test
    fun `INV-AC-24013 a long label, no earlier pack and a bad pack not ARMED are refused with nothing written`() {
        val long = badRow.copy(version = "12345678")
        val regLong =
            FakeRegistry(State(listOf(earlierRow, long), emptyList()), mapOf("12345678" to EarlierPack("2026.2", "c2")))
        val beforeLong = regLong.state
        RollbackPack(Permission(true), regLong).execute("GST", "12345678", date, "u", key) shouldBe
            RollbackPackResult.Refused(Refused.LabelTooLong("12345678-rollback"))
        regLong.state shouldBe beforeLong

        val none = FakeRegistry(State(listOf(badRow), emptyList()), emptyMap())
        val beforeNone = none.state
        RollbackPack(Permission(true), none).execute("GST", "2026.3", date, "u", key) shouldBe
            RollbackPackResult.Refused(Refused.NoEarlierPack)
        none.state shouldBe beforeNone

        val staged = registry(badRow.copy(status = PackStatus.STAGED))
        val beforeStaged = staged.state
        RollbackPack(Permission(true), staged).execute("GST", "2026.3", date, "u", key) shouldBe
            RollbackPackResult.Refused(Refused.NotArmed(PackStatus.STAGED))
        staged.state shouldBe beforeStaged
    }

    @Test
    fun `INV-AC-24013 a denied permission writes nothing and the port got the user ID and the supplied key`() {
        val reg = registry()
        val before = reg.state
        val permission = Permission(false)
        RollbackPack(permission, reg).execute("GST", "2026.3", date, "user-9", key) shouldBe RollbackPackResult.Denied
        reg.state shouldBe before
        reg.transactions shouldBe 0
        permission.calls shouldBe listOf("user-9" to key)
    }

    @Test
    fun `INV-AC-24013 an unknown bad pack is NotFound and writes nothing`() {
        val reg = registry()
        val before = reg.state
        RollbackPack(Permission(true), reg).execute("GST", "9.9", date, "u", key) shouldBe RollbackPackResult.NotFound
        reg.state shouldBe before
    }
}
