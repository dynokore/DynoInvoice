package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.connection.ConnectionCredentialExpiryReminderRequested
import com.dynokore.gst.masterdata.application.connection.ConnectionStatusChanged
import com.dynokore.gst.masterdata.domain.connection.CallFailure
import com.dynokore.gst.masterdata.domain.connection.ConnectionState
import com.dynokore.gst.masterdata.domain.connection.LastFailure
import com.dynokore.gst.masterdata.domain.connection.TestClass
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.time.Instant

/** INV-REQ-836, INV-REQ-837: the two platform events carry the pinned catalogue's types and the schema payloads. */
class ConnectionEventsTest {
    private val mapper = JsonMapper.builder().build()

    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { _, _, _ ->
            error("not used by the payload test")
        } as T

    @Test
    fun `the event types are the platform messages of the pinned catalogue`() {
        assertThat(ConnectionStatusChangedOutbox.EVENT_TYPE)
            .isEqualTo("com.dynokore.gst.platform.ConnectionStatusChanged.v1")
        assertThat(ConnectionCredentialExpiryReminderOutbox.EVENT_TYPE)
            .isEqualTo("com.dynokore.gst.platform.ConnectionCredentialExpiryReminderRequested.v1")
    }

    @Test
    fun `the status payload holds connection, a lower case state and the last failure as time and code`() {
        val outbox = ConnectionStatusChangedOutbox(unused(), mapper, unused())
        val failure =
            LastFailure(Instant.parse("2026-11-10T01:02:03Z"), CallFailure(TestClass.UNREACHABLE, "CONNECT-TIMEOUT"))
        val node = outbox.payload(ConnectionStatusChanged("CON-1", ConnectionState.DOWN, failure))
        assertThat(node.get("connection").asString()).isEqualTo("CON-1")
        assertThat(node.get("state").asString()).isEqualTo("down")
        assertThat(node.get("lastFailure").get("at").asString()).isEqualTo("2026-11-10T01:02:03Z")
        assertThat(node.get("lastFailure").get("reason").asString()).isEqualTo("CONNECT-TIMEOUT")
        assertThat(
            outbox.payload(ConnectionStatusChanged("CON-1", ConnectionState.UP, null)).has("lastFailure"),
        ).isFalse()
        assertThat(
            outbox.payload(ConnectionStatusChanged("CON-1", ConnectionState.DEGRADED, null)).get("state").asString(),
        ).isEqualTo("degraded")
    }

    @Test
    fun `the reminder payload holds connection, expiry and offset and no credential reference`() {
        val outbox = ConnectionCredentialExpiryReminderOutbox(unused(), mapper, unused())
        val node =
            outbox.payload(
                ConnectionCredentialExpiryReminderRequested(
                    "CON-1",
                    "secret/ref",
                    3,
                    Instant.parse("2026-11-20T00:00:00Z"),
                ),
            )
        assertThat(node.get("connection").asString()).isEqualTo("CON-1")
        assertThat(node.get("expiresAt").asString()).isEqualTo("2026-11-20T00:00:00Z")
        assertThat(node.get("offsetDays").asInt()).isEqualTo(3)
        assertThat(node.toString()).doesNotContain("secret/ref")
    }
}
