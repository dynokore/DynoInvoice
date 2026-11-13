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
                    TestSecrets.TOKEN_PROPERTIES + BoundaryFixture.INSTALLATION_ROLES + arrayOf("access.authority-head.interval=PT1H"),
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
    fun `INV-AC-22069 a sequence set back below the mark raises the incarnation at start to max of stored plus 1 and now`() {
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
    fun `INV-AC-22069 bump with a later clock stores max of stored plus 1 and now, with an earlier clock stored plus 1`() {
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
}
