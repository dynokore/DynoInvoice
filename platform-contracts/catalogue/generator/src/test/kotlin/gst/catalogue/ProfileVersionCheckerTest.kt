package gst.catalogue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** INV-Q-3283 A′ and INV-REQ-1245 b4 on small fixtures. */
class ProfileVersionCheckerTest {
    private fun profile(
        version: Int,
        vararg values: ParsedProfileValue,
    ) = ParsedOperatingProfile("PRF-1", "R", "r", "n", "t", version, ProfileStatus.AVAILABLE, null, values.toList())

    private fun v(
        vp: String,
        option: String,
        text: String = "t",
    ) = ParsedProfileValue(vp = vp, requirement = "R", text = text, option = option)

    @Test
    fun `changed values without a higher version fail`() {
        val violations = ProfileVersionChecker.check(listOf(profile(1, v("VP-1", "A"))), listOf(profile(1, v("VP-1", "B"))))
        assertTrue(violations.single().contains("without a higher version"))
    }

    @Test
    fun `a higher version passes and a lower one fails`() {
        assertTrue(ProfileVersionChecker.check(listOf(profile(1, v("VP-1", "A"))), listOf(profile(2, v("VP-1", "B")))).isEmpty())
        assertTrue(ProfileVersionChecker.check(listOf(profile(2)), listOf(profile(1))).single().contains("lower"))
    }

    @Test
    fun `text-only edits and the status are not value changes`() {
        val before = profile(1, v("VP-1", "A", "old words"))
        val after = profile(1, v("VP-1", "A", "new words")).copy(status = ProfileStatus.PLANNED)
        assertTrue(ProfileVersionChecker.check(listOf(before), listOf(after)).isEmpty())
    }

    @Test
    fun `INV-Q-3692 a newly added condition_fact is no change, but changing or removing one needs a higher version`() {
        val dir = ConditionFact("VP-1.DIR", ConstraintFact(ScopeLevel.TENANT, "directory"))
        val other = ConditionFact("VP-1.DIR", ConstraintFact(ScopeLevel.ENTITY, "directory"))
        val plain = v("VP-1", "A").copy(condition = "where one exists")
        fun check(
            before: ParsedProfileValue,
            after: ParsedProfileValue,
            version: Int = 1,
        ) = ProfileVersionChecker.check(listOf(profile(1, before)), listOf(profile(version, after)))
        assertTrue(check(plain, plain.copy(conditionFact = dir)).isEmpty())
        assertTrue(check(plain.copy(conditionFact = dir), plain.copy(conditionFact = other)).single().contains("without a higher version"))
        assertTrue(check(plain.copy(conditionFact = dir), plain).single().contains("without a higher version"))
        assertTrue(check(plain.copy(conditionFact = dir), plain.copy(conditionFact = other), version = 2).isEmpty())
    }

    @Test
    fun `a removed profile fails`() {
        assertTrue(ProfileVersionChecker.check(listOf(profile(1)), emptyList()).single().contains("absent"))
    }

    @Test
    fun `differences list added, removed and changed values in VP order`() {
        val changes =
            ProfileVersionChecker.differences(
                listOf(v("VP-10", "A"), v("VP-2", "A"), v("VP-3", "A")),
                listOf(v("VP-2", "A"), v("VP-3", "B"), v("VP-4", "A")),
            )
        assertEquals(
            listOf("VP-3" to ProfileChangeKind.CHANGED, "VP-4" to ProfileChangeKind.ADDED, "VP-10" to ProfileChangeKind.REMOVED),
            changes.map { it.vp to it.kind },
        )
    }
}
