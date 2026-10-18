package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.ConfigSchema.EventMode
import com.dynokore.platform.tenancykernel.BlankText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** The rendered DDL has no `btrim`/`trim` blank check left; each check uses the shared definition (INV-T-4616). */
class BlankChecksDdlTest {
    @ParameterizedTest
    @EnumSource(EventMode::class)
    fun `every blank check of the rendered DDL uses the shared definition`(mode: EventMode) {
        val ddl = ConfigSchema.ddl("svc", "config_app", "config_sys", mode)
        assertFalse(Regex("""\b(b|l|r)?trim\s*\(""", RegexOption.IGNORE_CASE).containsMatchIn(ddl), "a trim check left")
        val actor = "'${BlankText.SQL_NOT_ACTOR}'"
        val blank = "'${BlankText.SQL_BLANK}'"
        assertEquals(2, ddl.split("set_by text not null check (set_by !~ $actor)").size - 1)
        assertEquals(1, ddl.split("principal text not null check (principal !~ $actor)").size - 1)
        assertEquals(3, ddl.split("if v_actor is null or v_actor ~ $actor then").size - 1)
        assertEquals(3, ddl.split("v_actor text := (select svc.bound_actor());").size - 1)
        listOf("scope_id", "reason", "subject_ref", "role").forEach {
            assertFalse(ddl.split("$it text not null check ($it !~ $blank)").size < 2, it)
        }
        assertEquals(1, ddl.split("reason text check (reason is null or reason !~ $blank)").size - 1)
        assertEquals(1, ddl.split("or p_reason ~ $blank or p_audit is null").size - 1)
        listOf("instance_id", "correlation_id", "causation_id").forEach {
            assertEquals(1, ddl.split("or (v_row.staged ->> '$it') ~ $blank").size - 1, it)
        }
    }
}
