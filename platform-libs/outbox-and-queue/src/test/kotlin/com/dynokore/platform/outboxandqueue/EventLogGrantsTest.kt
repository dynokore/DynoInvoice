package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The generated topic DDL grants EXECUTE on `eventlog.bound_tenant()` to the topic's producer and subscriber logins
 * only (INV-Q-7092): the tenant predicate calls the function, so a login without it fails with SQLSTATE 42501.
 */
class EventLogGrantsTest {
    private val executeGrant =
        Regex("""grant\s+execute\s+on\s+function\s+eventlog\.bound_tenant\(\)\s+to\s+(\S+);""", RegexOption.IGNORE_CASE)

    private fun grantees(topic: EventLogSchema.Topic): List<String> =
        executeGrant.findAll(EventLogSchema.topicDdl(topic, "eventlog_sys")).map { it.groupValues[1] }.toList()

    @Test
    fun `producer and subscriber logins each get one execute grant`() {
        val topic = EventLogSchema.Topic("gst.a.b.v1", setOf("a_runtime"), setOf("b_runtime"), contentTopic = false)
        assertEquals(listOf("a_runtime", "b_runtime"), grantees(topic))
    }

    @Test
    fun `no execute grant names the sys role or bound_actor`() {
        val topic = EventLogSchema.Topic("gst.a.b.v1", setOf("a_runtime"), setOf("b_runtime"), contentTopic = false)
        val ddl = EventLogSchema.topicDdl(topic, "eventlog_sys")
        val executeStatements =
            Regex("""grant\s+execute\b[^;]*;""", RegexOption.IGNORE_CASE).findAll(ddl).map { it.value }.toList()
        assertEquals(2, executeStatements.size)
        assertFalse(executeStatements.any { it.contains("eventlog_sys") || it.contains("bound_actor") })
    }

    @Test
    fun `a topic with no producers gets one execute grant`() {
        val topic = EventLogSchema.Topic("gst.a.b.v1", emptySet(), setOf("b_runtime"), contentTopic = false)
        assertEquals(listOf("b_runtime"), grantees(topic))
    }

    @Test
    fun `a topic with no subscribers gets one execute grant`() {
        val topic = EventLogSchema.Topic("gst.a.b.v1", setOf("a_runtime"), emptySet(), contentTopic = false)
        assertEquals(listOf("a_runtime"), grantees(topic))
    }
}
