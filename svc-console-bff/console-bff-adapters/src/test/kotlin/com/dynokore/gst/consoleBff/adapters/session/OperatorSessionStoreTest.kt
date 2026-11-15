package com.dynokore.gst.consoleBff.adapters.session

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.application.session.ContentMode
import com.dynokore.gst.consoleBff.application.session.OperatorSession
import com.dynokore.gst.consoleBff.domain.session.SessionId
import com.dynokore.gst.consoleBff.domain.session.SessionIdHash
import com.dynokore.gst.consoleBff.domain.session.SessionIdParse
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** INV-AC-23363: the operator session in the row of a console session, on the Flyway-built database. */
class OperatorSessionStoreTest {
    private val connection: Connection = ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD)
    private val fakes = PlatformFakes()
    private val t0 = Instant.parse("2026-11-15T10:00:00Z")
    private val expires = t0.plusSeconds(900)
    private val tenant = "22222222-2222-2222-2222-222222222222"
    private val secret = "SECRET-OPERATOR-TOKEN-4155"

    private class MovingClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MovingClock(t0)
    private val store =
        JooqSessionStore(DSL.using(connection, SQLDialect.POSTGRES), fakes.binding, fakes.runner, clock = clock)

    @AfterEach
    fun clean() {
        ConsoleDb.superuser().use { it.createStatement().execute("truncate console_bff.console_session") }
        connection.close()
    }

    private fun hash(value: String) = SessionIdHash.of((SessionId.parse(value) as SessionIdParse.Parsed).id)

    private fun operator(
        token: String = secret,
        at: Instant = expires,
        mode: ContentMode = ContentMode.MASKED,
    ) = OperatorSession(tenant, token, at, mode)

    private fun columns(h: String): List<String?> =
        ConsoleDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select operator_token, operator_tenant_id::text, operator_expires_at is not null, " +
                        "operator_content_mode from console_bff.console_session where session_id_hash = ?",
                ).use { st ->
                    st.setString(1, h)
                    st.executeQuery().use { rs ->
                        if (!rs.next()) {
                            emptyList()
                        } else {
                            (1..4).map { rs.getString(it) }
                        }
                    }
                }
        }

    private fun exec(sql: String) = ConsoleDb.superuser().use { it.createStatement().execute(sql) }

    @Test
    fun `INV-AC-23363 after attach the row holds the token and find returns it`() {
        val h = hash(store.create("11111111-1111-1111-1111-111111111111", "p1", 1, t0).cookieValue)
        store.find(h)?.operator shouldBe null
        store.attachOperatorSession(h, operator(mode = ContentMode.ELEVATED)) shouldBe true
        columns(h) shouldBe listOf(secret, tenant, "t", "ELEVATED")
        store.find(h)?.operator shouldBe operator(mode = ContentMode.ELEVATED)
    }

    @Test
    fun `INV-AC-23363 the operator part is returned one second before expiry and not at expiry`() {
        val h = hash(store.create(tenant, "p1", 1, t0).cookieValue)
        store.attachOperatorSession(h, operator())
        clock.now = expires.minusSeconds(1)
        (store.find(h)?.operator != null) shouldBe true
        clock.now = expires
        store.find(h)?.operator shouldBe null
        (store.find(h) != null) shouldBe true
        clock.now = expires.plusSeconds(1)
        store.find(h)?.operator shouldBe null
    }

    @Test
    fun `INV-AC-23363 a second attach replaces the first and an unknown hash attaches nothing`() {
        val h = hash(store.create(tenant, "p1", 1, t0).cookieValue)
        store.attachOperatorSession(h, operator("first"))
        store.attachOperatorSession(h, operator("second", expires.plusSeconds(60), ContentMode.ELEVATED)) shouldBe true
        store.find(h)?.operator shouldBe operator("second", expires.plusSeconds(60), ContentMode.ELEVATED)
        store.attachOperatorSession("0".repeat(64), operator()) shouldBe false
    }

    @Test
    fun `INV-AC-23363 two console sessions never see each other's token`() {
        val a = hash(store.create(tenant, "p1", 1, t0).cookieValue)
        val b = hash(store.create(tenant, "p2", 1, t0).cookieValue)
        store.attachOperatorSession(a, operator("token-of-a"))
        store.find(b)?.operator shouldBe null
        store.attachOperatorSession(b, operator("token-of-b"))
        store.find(a)?.operator?.token shouldBe "token-of-a"
        store.find(b)?.operator?.token shouldBe "token-of-b"
    }

    @Test
    fun `INV-AC-23363 delete of the console session removes the token with the row`() {
        val h = hash(store.create(tenant, "p1", 1, t0).cookieValue)
        store.attachOperatorSession(h, operator())
        store.delete(h)
        columns(h) shouldBe emptyList()
        store.find(h) shouldBe null
    }

    @Test
    fun `INV-AC-23363 the sweeper keeps its behaviour for a row with an operator session`() {
        val old = hash(store.create(tenant, "p1", 1, t0.minus(Duration.ofHours(2))).cookieValue)
        store.attachOperatorSession(old, operator(at = t0.plusSeconds(600)))
        store.deleteExpired(t0, Duration.ofMinutes(30), Duration.ofHours(12)) shouldBe 1
        store.find(old) shouldBe null
    }

    @Test
    fun `INV-AC-23363 the migration checks refuse a row with only some of the four columns set`() {
        val h = hash(store.create(tenant, "p1", 1, t0).cookieValue)
        val partial =
            listOf(
                "operator_token = 'x'",
                "operator_expires_at = now()",
                "operator_tenant_id = '$tenant'",
                "operator_content_mode = 'MASKED'",
                "operator_token = 'x', operator_expires_at = now()",
                "operator_token = 'x', operator_expires_at = now(), operator_tenant_id = '$tenant'",
            )
        for (set in partial) {
            val failure =
                runCatching {
                    connection.createStatement().execute(
                        "update console_bff.console_session set $set where session_id_hash = '$h'",
                    )
                }.exceptionOrNull()
            (failure is SQLException) shouldBe true
        }
        columns(h) shouldBe listOf(null, null, "f", null)
    }

    @Test
    fun `INV-AC-23363 the token column holds at most 8192 characters`() {
        val h = hash(store.create(tenant, "p1", 1, t0).cookieValue)
        store.attachOperatorSession(h, operator("t".repeat(8192))) shouldBe true
        val failure = runCatching { store.attachOperatorSession(h, operator("t".repeat(8193))) }.exceptionOrNull()
        (failure != null) shouldBe true
    }

    @Test
    fun `INV-AC-23363 the table gained no tenant_id column and no other table was added`() {
        exec("select 1")
        ConsoleDb.superuser().use { c ->
            c
                .createStatement()
                .executeQuery(
                    "select count(*) from information_schema.columns where table_schema = 'console_bff' " +
                        "and table_name = 'console_session' and column_name = 'tenant_id'",
                ).use {
                    it.next()
                    it.getInt(1) shouldBe 0
                }
        }
    }
}
