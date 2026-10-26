package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.BEGIN
import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.CUSTOMER
import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.PLATFORM
import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.SEED_MIGRATION
import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.apply
import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.clone
import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.connect
import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.count
import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.policyFindings
import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.strings
import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.withRows
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException

/**
 * INV-AC-9279 (INV-REQ-862 behavior 4; INV-Q-11223 A, INV-Q-11225 C): the seed migration applied by Flyway as
 * `access_migrate`, the role that runs it in production and that owns the tables under forced row-level security.
 * The write refusal of `grantRole` for the platform tenant is in [PlatformGrantWriteSurfaceTest].
 */
class PlatformGrantSeedMigrationTest {
    private val shipped: String = Files.readString(PlatformGrantSeedFixture.migrations.resolve(SEED_MIGRATION))
    private val list = listOf("alice" to "platform.support", "bob" to "release.manager")
    private val bound = "permission denied for function bound_tenant"

    private fun <T> onClone(
        text: String,
        block: (Connection) -> T,
    ): T {
        val db = clone()
        apply(db, text)
        return connect(db).use(block)
    }

    private fun assertNoRows(c: Connection) {
        assertThat(count(c, "access.identity")).isZero()
        assertThat(count(c, "access.role_grant")).isZero()
    }

    @Test
    fun `INV-AC-9279 the shipped empty seed writes no platform row and no row at all`() {
        onClone(shipped, ::assertNoRows)
    }

    @Test
    fun `INV-AC-9279 two identities and two roles are stored as listed and other tenants are unchanged`() {
        val db = clone()
        connect(db).use(PlatformGrantSeedFixture::insertCustomerRows)
        apply(db, withRows(shipped, *list.toTypedArray()))
        connect(db).use { c ->
            assertThat(PlatformGrantSeedFixture.pairs(c, PLATFORM)).containsExactlyElementsOf(list)
            assertThat(PlatformGrantSeedFixture.identities(c, PLATFORM)).containsExactly("alice", "bob")
            val shape =
                "select distinct scope_level || '/' || scope_ref || '/' || status || '/' || grant_source || '/' || " +
                    "created_by from access.role_grant where tenant_id = '$PLATFORM'"
            assertThat(strings(c, shape))
                .containsExactly("TENANT/$PLATFORM/ACTIVE/BY_HAND/${SEED_MIGRATION.removeSuffix(".sql")}")
            assertThat(strings(c, "select distinct grantee_kind from access.identity where tenant_id = '$PLATFORM'"))
                .containsExactly("STAFF")
            assertThat(PlatformGrantSeedFixture.pairs(c, CUSTOMER)).containsExactly("carol" to "tenant.admin")
            val others = "select count(*) from access.role_grant where tenant_id not in ('$PLATFORM', '$CUSTOMER')"
            assertThat(strings(c, others)).containsExactly("0")
        }
    }

    @Test
    fun `INV-AC-9279 the insert statements run a second time add no row`() {
        val copy = withRows(shipped, *list.toTypedArray())
        onClone(copy) { c ->
            val before = PlatformGrantSeedFixture.snapshot(c)
            assertThat(before).hasSize(4)
            PlatformGrantSeedFixture.rerun(c, copy)
            assertThat(PlatformGrantSeedFixture.snapshot(c)).isEqualTo(before)
        }
    }

    @Test
    fun `INV-AC-9279 probe - a copy with the role tenant admin fails and writes no row`() {
        val db = clone()
        assertThatThrownBy { apply(db, withRows(shipped, "alice" to "platform.support", "bob" to "tenant.admin")) }
            .hasMessageContaining("operator-role wire name")
        connect(db).use { c ->
            assertNoRows(c)
            assertThat(policyFindings(c)).describedAs("the failed migration left its policies in place").isNotEmpty()
        }
    }

    @Test
    fun `INV-AC-9279 the policies, the forced security and the privileges after the migration equal the ruled set`() {
        onClone(shipped) { c -> assertThat(policyFindings(c)).isEmpty() }
        onClone(withRows(shipped, "alice" to "platform.support")) { c -> assertThat(policyFindings(c)).isEmpty() }
    }

    @Test
    fun `INV-AC-9279 probe - each broken policy step fails the policy gate`() {
        val check = "WITH CHECK (tenant_id = '$PLATFORM'::uuid)"
        val mutations =
            mapOf(
                "policy TO PUBLIC" to shipped.replace("FOR INSERT TO access_migrate", "FOR INSERT TO PUBLIC"),
                "policy without FOR INSERT" to shipped.replace("FOR INSERT TO access_migrate", "TO access_migrate"),
                "look-alike constant" to shipped.replace(check, check.replace("ffffffffffff'", "fffffffffffe'")),
                "NO FORCE" to inject("ALTER TABLE access.role_grant NO FORCE ROW LEVEL SECURITY;"),
                "GRANT EXECUTE" to inject("GRANT EXECUTE ON FUNCTION access.bound_tenant() TO access_migrate;"),
            )
        mutations.forEach { (name, text) ->
            assertThat(text).describedAs(name).isNotEqualTo(shipped)
            // access_migrate does not own bound_tenant, so Postgres may refuse the step itself; the copy is caught
            val outcome = runCatching { onClone(text) { c -> policyFindings(c) } }
            assertThat(outcome.isFailure || outcome.getOrThrow().isNotEmpty()).describedAs(name).isTrue()
        }
    }

    private fun inject(statement: String): String =
        shipped.replace("CREATE TEMPORARY TABLE", "$statement\nCREATE TEMPORARY TABLE")

    @Test
    fun `INV-AC-9279 probe - without the ALTER POLICY step the seed insert fails on bound_tenant and writes no row`() {
        val full = withRows(shipped, "alice" to "platform.support")
        val noAlter = full.replace(Regex("ALTER POLICY [^\n]*\n"), "")
        val noAlterOnIdentity =
            full.replace("ALTER POLICY tenant_isolation ON access.identity TO access_runtime;\n", "")
        listOf(noAlter, noAlterOnIdentity).forEach { copy ->
            assertThat(copy).isNotEqualTo(full)
            val db = clone()
            assertThatThrownBy { apply(db, copy) }.hasMessageContaining(bound)
            connect(db).use(::assertNoRows)
        }
    }

    @Test
    fun `INV-AC-9279 access_migrate reads nothing and writes no customer row after the seed`() {
        val db = clone()
        connect(db).use(PlatformGrantSeedFixture::insertCustomerRows)
        apply(db, withRows(shipped, *list.toTypedArray()))
        connect(db).use { c ->
            c.createStatement().use { s ->
                s.execute("SET ROLE access_migrate")
                listOf("identity", "role_grant").forEach { table ->
                    assertThat(count(c, "access.$table")).describedAs(table).isZero()
                    assertThat(s.executeUpdate("update access.$table set updated_by = 'x'")).describedAs(table).isZero()
                    assertThat(s.executeUpdate("delete from access.$table")).describedAs(table).isZero()
                }
                listOf(
                    PlatformGrantSeedFixture.customerIdentity("dave"),
                    PlatformGrantSeedFixture.customerGrant("carol", "platform.support"),
                ).forEach { insert ->
                    assertThatThrownBy { s.execute(insert) }
                        .isInstanceOf(SQLException::class.java)
                        .matches { (it as SQLException).sqlState == "42501" }
                }
                s.execute("RESET ROLE")
            }
            assertThat(count(c, "access.role_grant")).isEqualTo(3)
        }
    }

    @Test
    fun `INV-AC-9279 the runtime login sees the platform rows only with the platform tenant bound`() {
        val db = clone()
        connect(db).use(PlatformGrantSeedFixture::insertCustomerRows)
        apply(db, withRows(shipped, *list.toTypedArray()))
        connect(db).use { c ->
            PlatformGrantSeedFixture.enableRuntime(c)
            assertThat(PlatformGrantSeedFixture.runtimeSees(c, CUSTOMER)).containsExactly("carol/tenant.admin")
            assertThat(PlatformGrantSeedFixture.runtimeSees(c, PLATFORM))
                .containsExactly("alice/platform.support", "bob/release.manager")
        }
    }

    companion object {
        @JvmStatic
        @AfterAll
        fun drop() = PlatformGrantSeedFixture.drop()
    }
}
