package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.BindingKeyMigration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The module's DDL neither runs tenancy-kernel's binding generator nor switches role (INV-T-5363, coordinator rulings
 * INV-Q-6586 and INV-Q-6663): the schema holds `bound_tenant()` and `bound_actor()` before this DDL runs.
 */
class DdlTextGateTest {
    private val forbidden =
        listOf(
            """\bset\s+(local\s+|session\s+)?role\b""",
            """\breset\s+role\b""",
            """\bset\s+session\s+authorization\b""",
            """binding_key""",
            """binding_verified""",
            """create\s+(or\s+replace\s+)?function\s+\S*bound_(tenant|actor)\s*\(""",
        ).map { Regex(it, setOf(RegexOption.IGNORE_CASE)) }

    private fun violations(text: String): List<String> =
        forbidden.filter { it.containsMatchIn(text) }.map { it.pattern }

    private val topic = EventLogSchema.Topic("gst.a.b.v1", setOf("a_runtime"), setOf("b_runtime"), contentTopic = false)

    private val outputs: Map<String, String> =
        mapOf(
            "OutboxSchema.ddl" to OutboxSchema.ddl("svc", "svc_app", "svc_sys"),
            "OutboxSchema.ddl second pair" to OutboxSchema.ddl("other", "other_app", "other_sys"),
            "QueueSchema.ddl" to QueueSchema.ddl("svc", "svc_app", "svc_sys"),
            "EventLogSchema.schemaDdl" to EventLogSchema.schemaDdl("eventlog_sys"),
            "EventLogSchema.topicDdl" to EventLogSchema.topicDdl(topic, "eventlog_sys"),
            "EventLogSchema.functionsDdl" to EventLogSchema.functionsDdl(listOf(topic), "eventlog_sys", "purge"),
            "EventLogSchema.retentionUpgradeDdl" to EventLogSchema.retentionUpgradeDdl(),
            "OutboxSchema.testModeUpgradeDdl" to OutboxSchema.testModeUpgradeDdl("svc"),
        )

    @Test
    fun `no DDL entry point creates the binding functions or switches role`() {
        assertEquals(8, outputs.size)
        outputs.forEach { (name, text) ->
            assertTrue(text.isNotBlank(), "$name is empty")
            assertEquals(emptyList<String>(), violations(text), name)
        }
    }

    @Test
    fun `the check catches the generator and a role switch`() {
        val own = outputs.getValue("OutboxSchema.ddl")
        assertFalse(violations(BindingKeyMigration.ddl("svc", "svc_sys", "svc_app")).isEmpty(), "generator output")
        assertFalse(violations("$own\nSET ROLE x;").isEmpty(), "SET ROLE appended")
        assertFalse(violations("$own\nset   local\nrole x;").isEmpty(), "set local, line break before role")
        assertFalse(violations("$own\nRESET ROLE;").isEmpty(), "RESET ROLE appended")
        assertFalse(violations("$own\nset session authorization x;").isEmpty(), "SET SESSION AUTHORIZATION appended")
    }

    @Test
    fun `the check passes a call of the function and a search path`() {
        assertEquals(emptyList<String>(), violations("(select s.bound_tenant())"))
        assertEquals(emptyList<String>(), violations("SET search_path = pg_catalog, s, pg_temp"))
    }
}
