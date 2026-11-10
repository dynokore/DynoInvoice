package com.dynokore.gst.consoleBff.adapters.session

import com.dynokore.gst.consoleBff.adapters.ConsoleDb
import com.dynokore.gst.consoleBff.domain.session.SessionId
import com.dynokore.gst.consoleBff.domain.session.SessionIdHash
import com.dynokore.gst.consoleBff.domain.session.SessionIdParse
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SessionSweeperTest {
    @Test
    fun `INV-AC-14457 a row past the absolute timeout is gone after one sweep and a valid row stays`() {
        val now = Instant.parse("2026-11-10T10:00:00Z")
        ConsoleDb.connect(ConsoleDb.RUNTIME, ConsoleDb.RUNTIME_PASSWORD).use { c ->
            val store = JooqSessionStore(DSL.using(c, SQLDialect.POSTGRES))
            val old = store.create("11111111-1111-1111-1111-111111111111", "old", 1, now.minus(Duration.ofHours(13)))
            store.touchRows(hash(old.cookieValue), now)
            val fresh = store.create("11111111-1111-1111-1111-111111111111", "fresh", 1, now.minusSeconds(60))
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            val sweeper = SessionSweeper(store, clock, Duration.ofMinutes(30), Duration.ofHours(12))
            sweeper.sweep() shouldBe 1
            store.find(hash(old.cookieValue)) shouldBe null
            (store.find(hash(fresh.cookieValue)) != null) shouldBe true
            store.delete(hash(fresh.cookieValue))
        }
    }

    private fun hash(value: String) = SessionIdHash.of((SessionId.parse(value) as SessionIdParse.Parsed).id)
}
