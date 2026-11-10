package com.dynokore.gst.consoleBff.adapters.session

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.domain.session.SessionId
import com.dynokore.gst.consoleBff.domain.session.SessionIdHash
import com.dynokore.gst.consoleBff.domain.session.SessionIdParse
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Duration
import java.time.Instant

/** The store on the Flyway-built database (INV-AC-14457): hashes only, boundaries at the timeout. */
class JooqSessionStoreTest {
    private val connection: Connection = ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD)
    private val store = JooqSessionStore(DSL.using(connection, SQLDialect.POSTGRES))
    private val tenant = "11111111-1111-1111-1111-111111111111"
    private val now = Instant.parse("2026-11-10T10:00:00Z")
    private val idle = Duration.ofMinutes(30)
    private val absolute = Duration.ofHours(12)

    @AfterEach
    fun clean() {
        ConsoleDb.superuser().use { it.createStatement().execute("truncate console.console_session") }
        connection.close()
    }

    private fun hash(value: String) =
        SessionIdHash.of((SessionId.parse(value) as SessionIdParse.Parsed).id)

    private fun count(): Int =
        ConsoleDb.superuser().use { c ->
            c.createStatement().executeQuery("select count(*) from console.console_session").use { it.next(); it.getInt(1) }
        }

    @Test
    fun `INV-AC-14457 after create the table holds neither the cookie value nor the csrf token`() {
        val created = store.create(tenant, "p1", 7, now)
        ConsoleDb.superuser().use { c ->
            c.createStatement().executeQuery("select * from console.console_session").use { rs ->
                rs.next() shouldBe true
                val texts = (1..rs.metaData.columnCount).map { rs.getString(it) }
                texts.forEach {
                    (it.contains(created.cookieValue)) shouldBe false
                    (it.contains(created.csrfToken)) shouldBe false
                }
                rs.getString("session_id_hash") shouldBe hash(created.cookieValue)
            }
        }
        store.find(hash(created.cookieValue))?.principalId shouldBe "p1"
        store.find(hash(created.cookieValue))?.authorityVersionAtOpen shouldBe 7
        created.cookieValue.length shouldBe 43
    }

    @Test
    fun `INV-AC-14457 find of a hash that is not stored is empty`() {
        store.find("0".repeat(64)) shouldBe null
    }

    @Test
    fun `INV-AC-14457 touch of an unknown hash changes no row and says so`() {
        store.touchRows("0".repeat(64), now) shouldBe 0
        count() shouldBe 0
        val created = store.create(tenant, "p1", 7, now)
        store.touchRows(hash(created.cookieValue), now.plusSeconds(5)) shouldBe 1
        store.find(hash(created.cookieValue))?.lastSeenAt shouldBe now.plusSeconds(5)
    }

    @Test
    fun `INV-AC-14457 two creates give different values`() {
        val a = store.create(tenant, "p1", 7, now)
        val b = store.create(tenant, "p1", 7, now)
        (a.cookieValue == b.cookieValue) shouldBe false
        (a.csrfToken == b.csrfToken) shouldBe false
        listOf(a.cookieValue, a.csrfToken) shouldNotContain b.csrfToken
    }

    @Test
    fun `INV-AC-14457 delete removes the row`() {
        val created = store.create(tenant, "p1", 7, now)
        store.delete(hash(created.cookieValue))
        count() shouldBe 0
    }

    @Test
    fun `INV-AC-14457 deleteExpired removes a row at exactly the idle boundary and keeps one a second before`() {
        val atBoundary = store.create(tenant, "p1", 7, now.minus(idle))
        val before = store.create(tenant, "p2", 7, now.minus(idle).plusSeconds(1))
        // keep openedAt equal to lastSeenAt; both rows are far from the absolute bound
        store.deleteExpired(now, idle, absolute) shouldBe 1
        store.find(hash(atBoundary.cookieValue)) shouldBe null
        (store.find(hash(before.cookieValue)) != null) shouldBe true
    }

    @Test
    fun `INV-AC-14457 deleteExpired removes a row at exactly the absolute boundary and keeps one a second before`() {
        val atBoundary = store.create(tenant, "p1", 7, now.minus(absolute))
        val before = store.create(tenant, "p2", 7, now.minus(absolute).plusSeconds(1))
        // a recent touch makes the idle timeout not the reason for removal
        store.touchRows(hash(atBoundary.cookieValue), now)
        store.touchRows(hash(before.cookieValue), now)
        store.deleteExpired(now, idle, absolute) shouldBe 1
        store.find(hash(atBoundary.cookieValue)) shouldBe null
        (store.find(hash(before.cookieValue)) != null) shouldBe true
    }
}
