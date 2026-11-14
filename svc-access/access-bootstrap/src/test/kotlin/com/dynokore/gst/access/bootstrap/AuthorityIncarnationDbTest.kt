package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AuthorityHeadOutbox
import com.dynokore.gst.access.adapters.persistence.AuthorityHeadStore
import com.dynokore.gst.access.adapters.persistence.AuthorityIncarnationStore
import com.dynokore.gst.access.adapters.persistence.JooqAuthorityIncarnationStore
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * INV-T-9261 (INV-Q-9243 B (a), INV-REQ-1201): the incarnation of the authority versions over the real database,
 * through the Spring context of the service. The clock is fixed in every case.
 */
class AuthorityIncarnationDbTest {
    private val json = JsonMapper.builder().build()
    private val heartbeatType = PlatformEventCatalogue.AUTHORITY_HEAD_OBSERVED.eventType

    private fun <T> withContext(block: (AssertableWebApplicationContext) -> T): T {
        var result: T? = null
        BoundaryFixture
            .runner(
                catalogueMatrix = true,
                tokenProperties =
                    TestSecrets.TOKEN_PROPERTIES + BoundaryFixture.INSTALLATION_ROLES + QUIET,
            ).run { context ->
                context.startupFailure?.let { throw it }
                result = block(context)
            }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun sql(statement: String) =
        BoundaryFixture.asSuperuser { it.createStatement().use { s -> s.execute(statement) } }

    /** The table as it stands before the case: incarnation [incarnation], mark [mark], the sequence at [sequence]. */
    private fun given(
        incarnation: Long,
        mark: Long,
        sequence: Long,
    ) {
        sql("delete from access.outbox where type = '$heartbeatType'")
        sql("delete from access.authority_head")
        sql("delete from access.authority_incarnation")
        sql("insert into access.authority_incarnation values (true, $incarnation, $mark)")
        sql("select setval('access.authority_version_seq', $sequence)")
    }

    private fun incarnation(): Long =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                s.executeQuery("select incarnation from access.authority_incarnation").use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun mark(): Long =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                s.executeQuery("select published_high_water from access.authority_incarnation").use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun store(
        c: AssertableWebApplicationContext,
        epochSecond: Long,
    ): AuthorityIncarnationStore =
        JooqAuthorityIncarnationStore(
            c.getBean(DSLContext::class.java),
            Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC),
        )

    private fun <T> inTx(
        c: AssertableWebApplicationContext,
        block: () -> T,
    ): T =
        c.getBean(PlatformTenantBinding::class.java).bindPlatform("test") {
            c.getBean(TransactionRunner::class.java).inTransaction { block() }
        }

    private fun heartbeatPayloads(): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                s.executeQuery("select data::text from access.outbox where type = '$heartbeatType' order by id").use {
                    buildList {
                        while (it.next()) add(it.getString(1))
                    }
                }
            }
        }

    @Test
    fun `INV-AC-22069 a normal restart with the sequence above the mark keeps the stored incarnation`() {
        withContext { c ->
            given(incarnation = 1_000, mark = 40, sequence = 41)
            assertThat(inTx(c) { store(c, 5_000).start() }).isEqualTo(1_000L)
            assertThat(incarnation()).isEqualTo(1_000L)
        }
    }

    @Test
    fun `INV-AC-22069 a sequence equal to the mark does not raise the incarnation`() {
        withContext { c ->
            given(incarnation = 1_000, mark = 40, sequence = 40)
            assertThat(inTx(c) { store(c, 5_000).start() }).isEqualTo(1_000L)
            assertThat(incarnation()).isEqualTo(1_000L)
        }
    }

    @Test
    fun `INV-AC-22069 a sequence set back below the mark raises the incarnation at start to max(stored+1, now)`() {
        withContext { c ->
            given(incarnation = 1_000, mark = 40, sequence = 7)
            assertThat(inTx(c) { store(c, 5_000).start() }).isEqualTo(5_000L)
            assertThat(incarnation()).isEqualTo(5_000L)
            // the mark is rebased on the sequence, so the next start does not raise it again
            assertThat(mark()).isEqualTo(7L)
            assertThat(inTx(c) { store(c, 9_000).start() }).isEqualTo(5_000L)
        }
    }

    @Test
    fun `INV-AC-22069 a start below the mark with a clock earlier than stored gives stored plus 1`() {
        withContext { c ->
            given(incarnation = 1_000, mark = 40, sequence = 7)
            assertThat(inTx(c) { store(c, 10).start() }).isEqualTo(1_001L)
        }
    }

    @Test
    fun `INV-AC-22069 an empty table takes the clock in epoch seconds as the first value`() {
        withContext { c ->
            given(incarnation = 1, mark = 0, sequence = 1)
            sql("delete from access.authority_incarnation")
            assertThat(inTx(c) { store(c, 7_777).start() }).isEqualTo(7_777L)
            assertThat(incarnation()).isEqualTo(7_777L)
        }
    }

    @Test
    fun `INV-AC-22069 bump with a later clock stores max(stored+1, now), with an earlier clock stored+1`() {
        withContext { c ->
            given(incarnation = 1_000, mark = 40, sequence = 41)
            assertThat(inTx(c) { store(c, 5_000).bump() }).isEqualTo(5_000L)
            assertThat(incarnation()).isEqualTo(5_000L)
            // a clock earlier than stored: the value never decreases
            assertThat(inTx(c) { store(c, 10).bump() }).isEqualTo(5_001L)
            assertThat(incarnation()).isEqualTo(5_001L)
        }
    }

    @Test
    fun `INV-AC-22069 the high-water mark is written at each publish and never lowered`() {
        withContext { c ->
            given(incarnation = 1_000, mark = 0, sequence = 1)
            val head = c.getBean(AuthorityHeadStore::class.java)
            inTx(c) { head.advance(12) }
            assertThat(mark()).isEqualTo(12L)
            inTx(c) { head.advance(5) }
            assertThat(mark()).isEqualTo(12L)
            assertThat(incarnation()).isEqualTo(1_000L)
        }
    }

    @Test
    fun `INV-AC-22069 after a publish of 40 a sequence reset to 7 raises the incarnation at the next start`() {
        withContext { c ->
            given(incarnation = 1_000, mark = 0, sequence = 41)
            inTx(c) { c.getBean(AuthorityHeadStore::class.java).advance(40) }
            assertThat(inTx(c) { store(c, 5_000).start() }).isEqualTo(1_000L)
            sql("select setval('access.authority_version_seq', 7)")
            assertThat(inTx(c) { store(c, 5_000).start() }).isEqualTo(5_000L)
        }
    }

    /** Seeds the table and the sequence, then boots the whole context: the start step is the bean under test. */
    private fun bootWith(
        incarnation: Long,
        mark: Long,
        sequence: Long,
        head: Long? = null,
        called: Boolean = true,
    ) {
        given(incarnation, mark, sequence)
        if (!called) sql("select setval('access.authority_version_seq', $sequence, false)")
        if (head != null) sql("insert into access.authority_head values (true, $head)")
        BoundaryFixture
            .runner(
                catalogueMatrix = true,
                tokenProperties = TestSecrets.TOKEN_PROPERTIES + BoundaryFixture.INSTALLATION_ROLES + QUIET,
            ).run { context -> context.startupFailure?.let { throw it } }
    }

    private fun headRows(): List<Long> =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                s.executeQuery("select authority_version from access.authority_head").use {
                    buildList {
                        while (it.next()) add(it.getLong(1))
                    }
                }
            }
        }

    @Test
    fun `INV-AC-22069 a start-time rise with the sequence at 7 and the mark at 40 rebases the mark and the head to 7`() {
        bootWith(incarnation = 1_000, mark = 40, sequence = 7, head = 40)
        assertThat(mark()).isEqualTo(7L)
        assertThat(headRows()).containsExactly(7L)
        val raised = incarnation()
        // a second start with the sequence still at 7 does not rise again
        BoundaryFixture
            .runner(
                catalogueMatrix = true,
                tokenProperties = TestSecrets.TOKEN_PROPERTIES + BoundaryFixture.INSTALLATION_ROLES + QUIET,
            ).run { context -> context.startupFailure?.let { throw it } }
        assertThat(incarnation()).isEqualTo(raised)
        assertThat(mark()).isEqualTo(7L)
    }

    @Test
    fun `INV-AC-22069 a start-time rise with the sequence never called deletes the head and the next publish inserts it`() {
        // setval(.., 1, false) leaves the sequence never called
        bootWith(incarnation = 1_000, mark = 40, sequence = 1, head = 40, called = false)
        assertThat(mark()).isEqualTo(0L)
        assertThat(headRows()).isEmpty()
        withContext { c ->
            assertThat(c.getBean(AuthorityHeadOutbox::class.java).publish()).isFalse()
            assertThat(heartbeatPayloads()).isEmpty()
            inTx(c) { c.getBean(AuthorityHeadStore::class.java).advance(1) }
            assertThat(headRows()).containsExactly(1L)
        }
    }

    @Test
    fun `INV-AC-22069 bump leaves the mark and the head row unchanged`() {
        withContext { c ->
            given(incarnation = 1_000, mark = 40, sequence = 7)
            sql("insert into access.authority_head values (true, 40)")
            inTx(c) { store(c, 5_000).bump() }
            assertThat(mark()).isEqualTo(40L)
            assertThat(headRows()).containsExactly(40L)
        }
    }

    @Test
    fun `INV-AC-22069 the service start with the sequence at or above the mark keeps the incarnation`() {
        bootWith(incarnation = 1_000, mark = 40, sequence = 40)
        assertThat(incarnation()).isEqualTo(1_000L)
    }

    @Test
    fun `INV-AC-22069 the service start with the sequence below the mark raises the incarnation`() {
        val before = Instant.now().epochSecond
        bootWith(incarnation = 1_000, mark = 40, sequence = 7)
        assertThat(incarnation()).isBetween(before, Instant.now().epochSecond)
        // a start that finds a stored value ahead of the clock gives stored + 1
        val ahead = Instant.now().epochSecond + 100_000
        bootWith(incarnation = ahead, mark = 40, sequence = 7)
        assertThat(incarnation()).isEqualTo(ahead + 1)
    }

    @Test
    fun `INV-AC-22069 the heartbeat carries the stored incarnation`() {
        withContext { c ->
            given(incarnation = 4_242, mark = 0, sequence = 1)
            inTx(c) { c.getBean(AuthorityHeadStore::class.java).advance(3) }
            assertThat(c.getBean(AuthorityHeadOutbox::class.java).publish()).isTrue()
            assertThat(json.readTree(heartbeatPayloads().single())["incarnation"].asLong()).isEqualTo(4_242L)
        }
    }

    @Test
    fun `INV-AC-22069 a stored incarnation above Int MAX_VALUE makes the publish throw and appends nothing`() {
        withContext { c ->
            given(incarnation = Int.MAX_VALUE.toLong() + 1, mark = 0, sequence = 1)
            inTx(c) { c.getBean(AuthorityHeadStore::class.java).advance(3) }
            assertThatThrownBy { c.getBean(AuthorityHeadOutbox::class.java).publish() }
                .hasStackTraceContaining("integer overflow")
            assertThat(heartbeatPayloads()).isEmpty()
        }
    }

    private companion object {
        val QUIET = arrayOf("access.authority-head.interval=PT1H")
    }
}
