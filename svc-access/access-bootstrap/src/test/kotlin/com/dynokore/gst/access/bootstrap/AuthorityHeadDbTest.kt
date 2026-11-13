package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AuthorityHeadOutbox
import com.dynokore.gst.access.adapters.persistence.AuthorityHeadStore
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * INV-T-9060 (INV-Q-17072 A, INV-Q-17070 B): the head record `access.authority_head` and the AuthorityHeadObserved
 * heartbeat, over the real database through the Spring context of the service.
 */
class AuthorityHeadDbTest {
    private val json = JsonMapper.builder().build()
    private val type = PlatformEventCatalogue.AUTHORITY_HEAD_OBSERVED.eventType
    private val changed = PlatformEventCatalogue.AUTHORITY_CHANGED.eventType

    private fun <T> withContext(block: (AssertableWebApplicationContext) -> T): T {
        var result: T? = null
        BoundaryFixture
            .runner(
                catalogueMatrix = true,
                tokenProperties =
                    TestSecrets.TOKEN_PROPERTIES + BoundaryFixture.INSTALLATION_ROLES + QUIET,
            ).run { context ->
                context.startupFailure?.let { throw it }
                reset()
                result = block(context)
            }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun reset() =
        BoundaryFixture.asSuperuser {
            it.createStatement().use { s -> s.execute("delete from access.authority_head") }
            it.createStatement().use { s -> s.execute("delete from access.outbox where type = '$type'") }
        }

    private fun store(c: AssertableWebApplicationContext) = c.getBean(AuthorityHeadStore::class.java)

    private fun commit(
        c: AssertableWebApplicationContext,
        vararg versions: Long,
    ) = c.getBean(PlatformTenantBinding::class.java).bindPlatform("test") {
        c.getBean(TransactionRunner::class.java).inTransaction { versions.forEach { store(c).advance(it) } }
    }

    private fun rolledBack(
        c: AssertableWebApplicationContext,
        version: Long,
    ) = assertThatThrownBy {
        c.getBean(PlatformTenantBinding::class.java).bindPlatform("test") {
            c.getBean(TransactionRunner::class.java).inTransaction {
                store(c).advance(version)
                error("rolled back")
            }
        }
    }.hasMessageContaining("rolled back")

    private fun stored(): Long? =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                s.executeQuery("select authority_version from access.authority_head").use {
                    if (it.next()) it.getLong(1) else null
                }
            }
        }

    private fun heartbeats(): List<Triple<Long, String, String>> =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                val sql =
                    "select tenant_id::text, entity_ref, data::text from access.outbox where type = '$type' order by id"
                s.executeQuery(sql).use {
                    buildList {
                        while (it.next()) {
                            val version = json.readTree(it.getString(3))["authorityVersion"].asLong()
                            add(Triple(version, it.getString(1), it.getString(2)))
                        }
                    }
                }
            }
        }

    @Test
    fun `INV-AC-9060 committed 10 and 11 and a rolled back 12 give 11, then 13 after a further commit`() {
        withContext { c ->
            val outbox = c.getBean(AuthorityHeadOutbox::class.java)
            commit(c, 10, 11)
            rolledBack(c, 12)
            assertThat(outbox.publish()).isTrue()
            assertThat(heartbeats().map { it.first }).containsExactly(11L)
            assertThat(stored()).isEqualTo(11L)
            commit(c, 13)
            outbox.publish()
            assertThat(heartbeats().map { it.first }).containsExactly(11L, 13L)
        }
    }

    @Test
    fun `INV-AC-9060 with no event committed nothing is appended and the table has no row`() {
        withContext { c ->
            assertThat(c.getBean(AuthorityHeadOutbox::class.java).publish()).isFalse()
            assertThat(heartbeats()).isEmpty()
            assertThat(stored()).isNull()
        }
    }

    @Test
    fun `INV-AC-9060 after every AuthorityChanged row of the outbox is deleted the next heartbeat still carries 13`() {
        withContext { c ->
            commit(c, 13)
            BoundaryFixture.asSuperuser {
                it.createStatement().use { s ->
                    s.execute(
                        "delete from access.outbox where type = '$changed'",
                    )
                }
            }
            c.getBean(AuthorityHeadOutbox::class.java).publish()
            assertThat(heartbeats().map { it.first }).containsExactly(13L)
        }
    }

    @Test
    fun `INV-AC-9060 advance of 5 after 13 leaves 13 and a second row is refused by the table`() {
        withContext { c ->
            commit(c, 13)
            commit(c, 5)
            assertThat(stored()).isEqualTo(13L)
            assertThatThrownBy {
                BoundaryFixture.asSuperuser {
                    it.createStatement().use { s -> s.execute("insert into access.authority_head values (false, 3)") }
                }
            }.hasMessageContaining("authority_head")
            assertThatThrownBy { store(c).advance(0) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `INV-AC-9060 two transactions that commit 14 and 15 in either order both commit and leave 15`() {
        withContext { c ->
            listOf(listOf(14L, 15L), listOf(15L, 14L)).forEach { order ->
                reset()
                val pool = Executors.newFixedThreadPool(2)
                val ready = CountDownLatch(2)
                val futures =
                    order.map { version ->
                        pool.submit {
                            ready.countDown()
                            ready.await()
                            commit(c, version)
                        }
                    }
                futures.forEach { it.get(30, TimeUnit.SECONDS) }
                pool.shutdownNow()
                assertThat(stored()).isEqualTo(15L)
            }
        }
    }

    @Test
    fun `INV-AC-9060 access_runtime holds no DELETE privilege on the head record`() {
        withContext {
            BoundaryFixture.asSuperuser { c ->
                fun has(privilege: String) =
                    c.createStatement().use { s ->
                        s
                            .executeQuery(
                                "select has_table_privilege('access_runtime', 'access.authority_head', '$privilege')",
                            ).use { it.next() && it.getBoolean(1) }
                    }
                assertThat(has("DELETE")).isFalse()
                assertThat(has("SELECT") && has("INSERT") && has("UPDATE")).isTrue()
            }
        }
    }

    @Test
    fun `INV-AC-9060 the heartbeat row has the platform type, tenant and entity`() {
        withContext { c ->
            commit(c, 7)
            c.getBean(AuthorityHeadOutbox::class.java).publish()
            val envelopes = c.getBean(CapturedEvents::class.java).envelopes.filter { it.type == type }
            assertThat(envelopes).hasSize(1)
            assertThat(heartbeats().single().second).isEqualTo(TenantId.PLATFORM.toString())
            assertThat(type).isEqualTo("com.dynokore.gst.platform.AuthorityHeadObserved.v1")
        }
    }

    @Test
    fun `INV-AC-9060 while the publish lock is held a publish appends nothing, after the commit one`() {
        withContext { c ->
            commit(c, 4)
            val outbox = c.getBean(AuthorityHeadOutbox::class.java)
            holdingLock { connection ->
                assertThat(outbox.publish()).isFalse()
                assertThat(heartbeats()).isEmpty()
                connection.commit()
            }
            assertThat(outbox.publish()).isTrue()
            assertThat(heartbeats()).hasSize(1)
        }
    }

    @Test
    fun `INV-AC-9060 the lock is released at rollback and a publish whose append throws leaves it free`() {
        withContext { c ->
            commit(c, 4)
            val outbox = c.getBean(AuthorityHeadOutbox::class.java)
            holdingLock { connection -> connection.rollback() }
            assertThat(outbox.publish()).isTrue()
            assertThat(lockFree()).isTrue()
        }
    }

    /** True when a fresh session can take and release the publish lock at once (nobody holds it). */
    private fun lockFree(): Boolean =
        BoundaryFixture.asSuperuser { connection ->
            connection.autoCommit = false
            connection
                .createStatement()
                .use { s ->
                    s.executeQuery("select pg_try_advisory_xact_lock($LOCK)").use {
                        it.next() && it.getBoolean(1)
                    }
                }.also { connection.rollback() }
        }

    private fun holdingLock(inside: (Connection) -> Unit) {
        val box = BoundaryFixture.container
        DriverManager.getConnection(box.jdbcUrl, box.username, box.password).use { connection ->
            connection.autoCommit = false
            connection.createStatement().use {
                it.executeQuery("select pg_try_advisory_xact_lock($LOCK)").use { r ->
                    assertThat(r.next() && r.getBoolean(1)).isTrue()
                }
            }
            inside(connection)
        }
    }

    @Test
    fun `INV-AC-9060 the publish lock is a transaction-level lock of its own key`() {
        val source =
            java.nio.file.Files.readString(
                java.nio.file.Path.of(
                    "..",
                    "access-adapters",
                    "src",
                    "main",
                    "kotlin",
                    "com",
                    "dynokore",
                    "gst",
                    "access",
                    "adapters",
                    "events",
                    "AuthorityHeadOutbox.kt",
                ),
            )
        assertThat(source).contains("pg_try_advisory_xact_lock").doesNotContain("pg_try_advisory_lock")
        assertThat(source).contains("access.authority-head-publish")
    }

    private companion object {
        val QUIET = arrayOf("access.authority-head.interval=PT1H")
        val LOCK = AuthorityHeadOutbox.AUTHORITY_HEAD_PUBLISH_LOCK
    }
}
