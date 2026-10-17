package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.BlankText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The rendered DDL has no `btrim`/`trim` blank check left; each check uses the shared definition (INV-T-4616). */
class BlankChecksDdlTest {
    private val trim = Regex("""\b(b|l|r)?trim\s*\(""", RegexOption.IGNORE_CASE)
    private val blank = "'${BlankText.SQL_BLANK}'"

    @Test
    fun `the outbox DDL checks its arguments with the shared blank definition`() {
        val ddl = OutboxSchema.ddl("svc", "svc_app", "svc_sys")
        assertFalse(trim.containsMatchIn(ddl), "a trim check left")
        assertEquals(2, ddl.split("if p_consumer is null or p_consumer ~ $blank\n").size - 1)
        assertEquals(1, ddl.split("or p_source is null or p_source ~ $blank then").size - 1)
    }

    @Test
    fun `the generated clear functions check their value with the shared blank definition`() {
        val byColumn = OutboxSchema.readModelClearFunction("svc", "rm_view", "svc_app", "svc_sys", "consumer")
        val all = OutboxSchema.readModelClearFunction("svc", "rm_cache", "svc_app", "svc_sys")
        assertFalse(trim.containsMatchIn(byColumn + all), "a trim check left")
        assertEquals(1, byColumn.split("if p_value is null or p_value::text ~ $blank then").size - 1)
    }

    @Test
    fun `the clear function by column takes text and converts it into a typed variable first`() {
        val byColumn = OutboxSchema.readModelClearFunction("svc", "rm_view", "svc_app", "svc_sys", "consumer")
        assertTrue("(p_value text)" in byColumn)
        assertTrue("v svc.rm_view.consumer%type;" in byColumn)
        assertFalse("p_value svc.rm_view.consumer%type" in byColumn, "the column-typed argument is gone")
        assertTrue(byColumn.indexOf("v := p_value;") in 0 until byColumn.indexOf("if p_value is null"))
        assertTrue("where consumer = v;" in byColumn)
        assertEquals(3, byColumn.split("svc.rm_view_full_sync_clear(text)").size - 1)
    }
}
