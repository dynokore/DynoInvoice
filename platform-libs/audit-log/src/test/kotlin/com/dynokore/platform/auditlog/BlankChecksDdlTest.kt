package com.dynokore.platform.auditlog

import com.dynokore.platform.tenancykernel.BlankText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** The rendered DDL has no `btrim`/`trim` blank check left; each check uses the shared definition (INV-T-4616). */
class BlankChecksDdlTest {
    private val ddl =
        AuditSchema.ddl("svc", "svc_app") + AuditSchema.auditEntryDdl("svc", "svc_app") +
            AuditSchema.purgeDdl("svc", "svc_app", "svc_sys", "control-plane-svc")
    private val actor = "'${BlankText.SQL_NOT_ACTOR}'"
    private val blank = "'${BlankText.SQL_BLANK}'"

    @Test
    fun `no trim blank check is left`() {
        assertFalse(Regex("""\b(b|l|r)?trim\s*\(""", RegexOption.IGNORE_CASE).containsMatchIn(ddl))
    }

    @Test
    fun `actors use the shared actor rule and other text the shared blank rule`() {
        assertEquals(2, ddl.split("actor text not null check (actor !~ $actor)").size - 1)
        assertEquals(1, ddl.split("if v_actor is null or v_actor ~ $actor then").size - 1)
        assertEquals(1, ddl.split("v_actor text := current_setting('app.actor', true);").size - 1)
        listOf("reason", "action", "scope").forEach {
            assertFalse(ddl.split("$it text not null check ($it !~ $blank)").size < 2, it)
        }
        assertEquals(1, ddl.split("reason text check (reason is null or reason !~ $blank)").size - 1)
        listOf("p_reason", "p_subject", "p_instance_id", "p_correlation_id", "p_causation_id").forEach {
            assertEquals(1, ddl.split("$it is null or $it ~ $blank").size - 1, it)
        }
    }
}
