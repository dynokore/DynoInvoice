package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AuthorityHeadOutbox
import com.dynokore.gst.access.adapters.events.InstallationRoleReconciler
import com.dynokore.gst.access.adapters.http.InstallationRole
import com.dynokore.gst.access.adapters.persistence.AuthorityHeadStore
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.sql.DriverManager
import java.util.UUID

/**
 * INV-T-9060 (INV-Q-17072 A), review round 2: the behavioural probes of the publish lock key and of the fourth
 * append site (the installation-role reconciler), over the real database through the Spring context of the service.
 */
class AuthorityHeadLockDbTest {
    private fun <T> withContext(block: (AssertableWebApplicationContext) -> T): T {
        var result: T? = null
        BoundaryFixture
            .runner(
                catalogueMatrix = true,
                tokenProperties =
                    TestSecrets.TOKEN_PROPERTIES + BoundaryFixture.INSTALLATION_ROLES +
                        arrayOf("access.authority-head.interval=PT1H"),
            ).run { context ->
                context.startupFailure?.let { throw it }
                BoundaryFixture.asSuperuser {
                    it.createStatement().use { s -> s.execute("delete from access.authority_head") }
                }
                result = block(context)
            }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun commit(
        c: AssertableWebApplicationContext,
        version: Long,
    ) = c.getBean(PlatformTenantBinding::class.java).bindPlatform("test") {
        c
            .getBean(
                TransactionRunner::class.java,
            ).inTransaction { c.getBean(AuthorityHeadStore::class.java).advance(version) }
    }

    private fun stored(): Long? =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                s.executeQuery("select authority_version from access.authority_head").use {
                    if (it.next()) it.getLong(1) else null
                }
            }
        }

    @Test
    fun `INV-AC-9051 the publish lock key differs from the other advisory lock keys of the service`() {
        assertThat(LOCK).isNotEqualTo(sha256Key("access.first-administrator:${UUID.randomUUID()}"))
        val approval =
            BoundaryFixture.asSuperuser { connection ->
                connection
                    .createStatement()
                    .executeQuery("select hashtextextended('access.installation_fact_request/a/b', 0)")
                    .use {
                        it.next()
                        it.getLong(1)
                    }
            }
        assertThat(LOCK).isNotEqualTo(approval)
        assertThat(LOCK).isEqualTo(sha256Key("access.authority-head-publish"))
    }

    @Test
    fun `INV-AC-9051 a session-level lock on the key survives a publish and the publish leaves no lock of its own`() {
        withContext { c ->
            commit(c, 4)
            val outbox = c.getBean(AuthorityHeadOutbox::class.java)
            val box = BoundaryFixture.container
            DriverManager.getConnection(box.jdbcUrl, box.username, box.password).use { session ->
                session.autoCommit = true
                session.createStatement().use { it.executeQuery("select pg_advisory_lock($LOCK)").close() }
                // another session holds the key: the publish takes the try-form, finds it taken and appends nothing
                assertThat(outbox.publish()).isFalse()
                assertThat(advisoryLocks()).hasSize(1)
                session.createStatement().use { it.executeQuery("select pg_advisory_unlock($LOCK)").close() }
            }
            // free again: the publish appends, and after it returns no advisory lock on the key remains
            assertThat(outbox.publish()).isTrue()
            assertThat(advisoryLocks()).isEmpty()
        }
    }

    private fun sha256Key(text: String): Long =
        java.nio.ByteBuffer
            .wrap(
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(text.toByteArray(Charsets.UTF_8)),
            ).long

    /** The advisory locks on the publish key, as pg_locks reports them (key split into classid and objid). */
    private fun advisoryLocks(): List<Boolean> =
        BoundaryFixture.asSuperuser { connection ->
            connection
                .createStatement()
                .executeQuery(
                    "select granted from pg_locks where locktype = 'advisory' " +
                        "and ((classid::bigint << 32) | objid::bigint) = $LOCK",
                ).use { rows -> buildList { while (rows.next()) add(rows.getBoolean(1)) } }
        }

    @Test
    fun `INV-AC-9051 a reconcile that writes a grant advances the head and an unchanged entry does not`() {
        withContext { c ->
            val reconciler = c.getBean(InstallationRoleReconciler::class.java)
            val written = reconciler.reconcile(emptyList())
            assertThat(written).isNotEmpty()
            assertThat(stored()).isEqualTo(written.maxOf { it.authorityVersion })
            val before = stored()
            assertThat(reconciler.reconcile(emptyList())).isEmpty()
            assertThat(stored()).isEqualTo(before)
            val added = reconciler.reconcile(listOf(InstallationRole.of("user:op-9", "deployment.operator")))
            assertThat(added).hasSize(1)
            assertThat(stored()).isEqualTo(added.single().authorityVersion).isGreaterThan(before)
            val after = stored()
            assertThat(reconciler.reconcile(listOf(InstallationRole.of("user:op-9", "deployment.operator")))).isEmpty()
            assertThat(stored()).isEqualTo(after)
        }
    }

    private companion object {
        val LOCK = AuthorityHeadOutbox.AUTHORITY_HEAD_PUBLISH_LOCK
    }
}
