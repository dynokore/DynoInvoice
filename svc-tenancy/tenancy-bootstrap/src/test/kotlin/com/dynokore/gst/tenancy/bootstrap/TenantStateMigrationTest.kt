package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-19390: the state migration leaves every existing tenant (the platform row included) and every workspace
 * tenant OPEN, and a tenant created through `tenancy.create_tenant` without a paired production tenant is SCHEDULED.
 * BoundaryFixture migrates in one step, so each case migrates a scratch database of the fixture's container with
 * Flyway directly: up to the version below this migration, a row inserted, then on to the end.
 */
class TenantStateMigrationTest {
    private val below = "202611091400"
    private val platform = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")

    private class Scratch(
        val name: String,
        val url: String,
    )

    private fun <T> withScratch(block: (Scratch) -> T): T {
        val name = "t19392_" + UUID.randomUUID().toString().replace("-", "")
        val base = BoundaryFixture.container
        val url = base.jdbcUrl.replace("/" + base.databaseName, "/$name")
        BoundaryFixture.asSuperuser { c -> c.createStatement().use { it.execute("CREATE DATABASE $name") } }
        try {
            connect(url).use { c ->
                c.createStatement().use {
                    it.execute(
                        "CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;\n" +
                            "CREATE SCHEMA IF NOT EXISTS tenancy AUTHORIZATION tenancy_sys;\n" +
                            "SET ROLE tenancy_sys;\n" +
                            BindingKeyMigration.ddl("tenancy", "tenancy_sys", "tenancy_runtime") +
                            "\nRESET ROLE;",
                    )
                }
            }
            return block(Scratch(name, url))
        } finally {
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use { it.execute("DROP DATABASE IF EXISTS $name WITH (FORCE)") }
            }
        }
    }

    private fun connect(url: String): Connection {
        val b = BoundaryFixture.container
        return DriverManager.getConnection(url, b.username, b.password)
    }

    private fun migrate(
        s: Scratch,
        target: String? = null,
    ) {
        val b = BoundaryFixture.container
        val dir = Path.of("..", "tenancy-adapters", "src", "main", "resources", "db", "migration")
        Flyway
            .configure()
            .dataSource(s.url, b.username, b.password)
            .schemas("tenancy")
            .defaultSchema("tenancy")
            .locations("filesystem:" + dir.toAbsolutePath().normalize())
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .also { if (target != null) it.target(target) }
            .load()
            .migrate()
    }

    private fun insertBefore(
        s: Scratch,
        id: UUID,
    ) = connect(s.url).use { c ->
        c
            .prepareStatement(
                "insert into tenancy.tenant (tenant_id, ref, legal_name, plan_ref, contract_reference, " +
                    "structure_version, created_at) values (?, ?, 'T', 'p', ?, 1, now())",
            ).use {
                it.setObject(1, id)
                it.setString(2, "old-" + id.toString().take(8))
                it.setString(3, "c-old-" + id.toString().take(8))
                it.executeUpdate()
            }
    }

    private fun state(
        s: Scratch,
        id: UUID,
    ): String =
        connect(s.url).use { c ->
            c.prepareStatement("select state from tenancy.tenant where tenant_id = ?").use {
                it.setObject(1, id)
                it.executeQuery().use { r ->
                    r.next()
                    r.getString(1)
                }
            }
        }

    private fun enableRuntime(s: Scratch) {
        connect(s.url).use { c ->
            c.createStatement().use {
                it.execute("ALTER ROLE tenancy_runtime LOGIN PASSWORD 'runtime-password-for-tests'")
                it.execute("GRANT CONNECT ON DATABASE ${s.name} TO tenancy_runtime")
                it.execute("GRANT USAGE ON SCHEMA tenancy TO tenancy_runtime")
                it.execute(
                    "INSERT INTO tenancy.binding_key (slot, secret) " +
                        "VALUES ('current', convert_to('${TestSecrets.BINDING_KEY}', 'UTF8')) ON CONFLICT DO NOTHING",
                )
            }
        }
    }

    /** create_tenant as the runtime role, bound as TransactionRunnerImpl binds it; returns the new tenant id. */
    private fun create(
        s: Scratch,
        bound: UUID,
        paired: UUID?,
        workspace: String?,
    ): UUID {
        enableRuntime(s)
        val id = UUID.randomUUID()
        val tag = id.toString().take(8)
        DriverManager.getConnection(s.url, "tenancy_runtime", "runtime-password-for-tests").use { c ->
            c.autoCommit = false
            val actor = "svc:tenancy-svc"
            val expiry = BindingSignature.expiryFor(Instant.now(), Duration.ofSeconds(30))
            val sig =
                BindingSignature.sign(
                    TestSecrets.BINDING_KEY.toByteArray(Charsets.UTF_8),
                    TenantId(bound),
                    actor,
                    expiry,
                )
            c
                .prepareStatement(
                    "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true), " +
                        "set_config('app.binding_sig', ?, true)",
                ).use {
                    it.setString(1, bound.toString())
                    it.setString(2, actor)
                    it.setString(3, sig)
                    it.execute()
                }
            c
                .prepareStatement(
                    "select tenancy.create_tenant(?::uuid, ?, 'Legal', 'plan', ?, ?::uuid, 'SG', ?)::text",
                ).use {
                    it.setObject(1, id)
                    it.setString(2, "T-$tag")
                    it.setString(3, "c-$tag")
                    it.setObject(4, paired)
                    it.setString(5, workspace)
                    it.executeQuery().use { r -> r.next() }
                }
            c.commit()
        }
        return id
    }

    @Test
    fun `INV-AC-19390 case 1 a tenant row inserted before the migration is OPEN afterwards`() {
        withScratch { s ->
            migrate(s, below)
            val old = UUID.randomUUID()
            insertBefore(s, old)
            insertBefore(s, platform)
            migrate(s)
            assertEquals("OPEN", state(s, old))
            assertEquals("OPEN", state(s, platform))
            connect(s.url).use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("select count(*) from tenancy.tenant where state <> 'OPEN'").use { r ->
                        r.next()
                        assertEquals(0L, r.getLong(1))
                    }
                }
            }
        }
    }

    @Test
    fun `INV-AC-19390 case 2 a tenant created without a paired production tenant is SCHEDULED`() {
        withScratch { s ->
            migrate(s)
            val id = create(s, platform, null, null)
            assertEquals("SCHEDULED", state(s, id))
        }
    }

    @Test
    fun `INV-AC-19390 case 3 a workspace tenant created with a paired production tenant is OPEN`() {
        withScratch { s ->
            migrate(s)
            val production = create(s, platform, null, null)
            val workspace = create(s, production, production, "sandbox")
            assertEquals("OPEN", state(s, workspace))
            assertEquals("SCHEDULED", state(s, production))
        }
    }
}
