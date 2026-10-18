package com.dynokore.platform.calendarandperiods

import com.dynokore.platform.calendarandperiods.Fixtures.monthly
import com.dynokore.platform.calendarandperiods.Fixtures.quarterly
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class AlertThresholdsTest {
    private val base = Fixtures.pack()

    private fun pack(
        thresholds: Map<FilingFrequency, List<Int>> = emptyMap(),
        margin: Duration? = null,
        windows: Map<FilingFrequency, List<StandardWindow>> =
            base.let {
                mapOf(
                    quarterly to it.windowsOf(quarterly),
                    monthly to it.windowsOf(monthly),
                )
            },
    ) = CalendarPack("test-1", windows, "F8", base.dueRules, base.calendar, null, thresholds, margin)

    private fun refused(
        thresholds: Map<FilingFrequency, List<Int>> = emptyMap(),
        margin: Duration? = null,
    ): String = assertThrows<IllegalArgumentException> { pack(thresholds, margin) }.message.orEmpty()

    @Test
    fun `INV-AC-109 each frequency returns its own thresholds`() {
        val p = pack(mapOf(monthly to listOf(5, 2), quarterly to listOf(10, 5)))
        assertEquals(listOf(5, 2), p.defaultAlertThresholds(monthly))
        assertEquals(listOf(10, 5), p.defaultAlertThresholds(quarterly))
        assertNotEquals(p.defaultAlertThresholds(quarterly).first(), p.defaultAlertThresholds(monthly).first())
    }

    @Test
    fun `INV-AC-203 the first monthly item is the value a service compares with`() {
        assertEquals(
            5,
            pack(mapOf(monthly to listOf(5, 2), quarterly to listOf(10, 5))).defaultAlertThresholds(monthly).first(),
        )
    }

    @Test
    fun `INV-AC-2265 the margin is returned as the pack states it`() {
        assertEquals(Duration.ofDays(10), pack(margin = Duration.ofDays(10)).defaultSafetyMargin())
        assertEquals(Duration.ofHours(48), pack(margin = Duration.ofHours(48)).defaultSafetyMargin())
        assertEquals(Duration.ZERO, pack(margin = Duration.ZERO).defaultSafetyMargin())
    }

    @Test
    fun `a zero threshold is accepted`() {
        assertEquals(listOf(5, 0), pack(mapOf(monthly to listOf(5, 0))).defaultAlertThresholds(monthly))
    }

    @Test
    fun `refuses thresholds for a frequency with no windows`() {
        val message =
            assertThrows<IllegalArgumentException> {
                pack(mapOf(monthly to listOf(5)), windows = mapOf(quarterly to base.windowsOf(quarterly)))
            }.message.orEmpty()
        assertTrue("test-1" in message && "MONTHLY" in message, message)
    }

    @Test
    fun `refuses an empty list`() = assertTrue("MONTHLY" in refused(mapOf(monthly to emptyList())))

    @Test
    fun `refuses eleven items`() = refused(mapOf(monthly to (11 downTo 1).toList())).let { assertTrue("test-1" in it) }

    @Test
    fun `refuses an item above 365`() = refused(mapOf(monthly to listOf(366, 5))).let { assertTrue("MONTHLY" in it) }

    @Test
    fun `refuses an item below zero`() = refused(mapOf(monthly to listOf(5, -1))).let { assertTrue("MONTHLY" in it) }

    @Test
    fun `refuses an equal neighbour`() = refused(mapOf(monthly to listOf(5, 5))).let { assertTrue("MONTHLY" in it) }

    @Test
    fun `refuses a rising neighbour`() = refused(mapOf(monthly to listOf(2, 5))).let { assertTrue("MONTHLY" in it) }

    @Test
    fun `refuses a negative margin`() = refused(margin = Duration.ofHours(-1)).let { assertTrue("test-1" in it) }

    @Test
    fun `the six-argument call yields a pack whose accessors throw the coverage failure`() {
        val p = Fixtures.pack()
        val a = assertThrows<CalendarPackCoverageException> { p.defaultAlertThresholds(monthly) }
        assertEquals("calendar pack test-1 has no alert thresholds for frequency MONTHLY", a.message)
        val m = assertThrows<CalendarPackCoverageException> { p.defaultSafetyMargin() }
        assertEquals("calendar pack test-1 has no safety margin", m.message)
    }

    @Test
    fun `the pack is immune to later changes of the passed or returned list`() {
        val given = mutableListOf(5, 2)
        val p = pack(mapOf(monthly to given))
        given.clear()
        given.add(99)
        assertEquals(listOf(5, 2), p.defaultAlertThresholds(monthly))
        assertThrows<UnsupportedOperationException> { (p.defaultAlertThresholds(monthly) as MutableList<Int>).add(1) }
        assertEquals(listOf(5, 2), p.defaultAlertThresholds(monthly))
    }
}
