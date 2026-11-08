package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AuthorityChangedReplaySource
import com.dynokore.platform.outboxandqueue.ReplayedState
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID

/**
 * The migration `V202611091000__add_authority_version.sql` (INV-T-9061) applied to a database that holds two tenants
 * with rows of every status (INV-AC-9051): every row that had a live event gets one distinct sequence value, in the
 * order of (`updated_at`, `id`); the rows that had none keep null; the policies of the schema are the same before and
 * after; the sequence is not behind the stored values.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorityVersionMigrationDbTest {
    private val migrations = Path.of("..", "access-adapters", "src", "main", "resources", "db", "migration")
    private val provisioning = Path.of("..", "access-adapters", "src", "test", "resources", "db", "provisioning.sql")

    /** One row that carries (or should carry) a version: its table, key, status, order columns and stored value. */
    private data class Row(
        val table: String,
        val ref: String,
        val status: String,
        val updatedAt: String,
        val id: String,
        val version: Long?,
    ) {
        val shouldCarry get() =
            table == "consent" || status == "REVOKED" || (table == "role_grant" && status == "ACTIVE")
    }

    private class Observed(
        val rows: List<Row>,
        val policies: Set<String>,
        val sequenceLast: Long,
    )

    private fun nullButShouldCarry(rows: List<Row>) = rows.count { it.shouldCarry && it.version == null }

    private fun carried(rows: List<Row>) = rows.filter { it.version != null }

    private fun pairwiseDifferent(rows: List<Row>) = carried(rows).map { it.version }.let { it.size == it.toSet().size }

    private fun inOrder(rows: List<Row>) =
        carried(rows)
            .sortedWith(compareBy({ it.updatedAt }, { it.id }))
            .map { it.version!! }
            .let { versions -> versions == versions.sorted() }

    private fun fixedRows(rows: List<Row>) = rows.filter { !it.shouldCarry }

    private fun observe(connection: Connection): Observed {
        val rows = mutableListOf<Row>()
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "select 'role_grant', grant_ref, status, updated_at::text, id::text, authority_version " +
                        "from access.role_grant union all " +
                        "select 'consent', consent_ref, status, updated_at::text, id::text, authority_version " +
                        "from access.consent union all " +
                        "select 'service_principal', principal_ref, status, updated_at::text, id::text, " +
                        "authority_version from access.service_principal",
                ).use { set ->
                    while (set.next()) {
                        rows +=
                            Row(
                                set.getString(1),
                                set.getString(2),
                                set.getString(3),
                                set.getString(4),
                                set.getString(5),
                                set.getLong(6).takeUnless { set.wasNull() },
                            )
                    }
                }
        }
        return Observed(rows, policies(connection), 0)
    }

    private fun policies(connection: Connection): Set<String> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "select tablename || '.' || policyname || '.' || cmd || '.' || roles::text || '.' || " +
                        "coalesce(qual, '') || '.' || coalesce(with_check, '') " +
                        "from pg_policies where schemaname = 'access'",
                ).use { set -> generateSequence { if (set.next()) set.getString(1) else null }.toSet() }
        }

    private fun sequenceLast(connection: Connection): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("select coalesce(last_value, 0) from access.authority_version_seq").use {
                it.next()
                it.getLong(1)
            }
        }

    private fun sql(
        connection: Connection,
        text: String,
    ) = connection.createStatement().use { it.execute(text) }

    private fun seed(
        connection: Connection,
        tenant: UUID,
        tag: String,
        base: Int,
    ) {
        sql(
            connection,
            "insert into access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, created_by, " +
                "updated_at, updated_by, version) values (gen_random_uuid(), '$tenant', 'user:$tag', 'STAFF', " +
                "now(), 's', now(), 's', 0)",
        )
        listOf("ACTIVE", "REVOKED", "PENDING", "ACTIVE").forEachIndexed { index, status ->
            sql(
                connection,
                "insert into access.role_grant (id, tenant_id, grant_ref, identity_ref, role, scope_level, " +
                    "scope_ref, valid_from, status, grant_source, created_at, created_by, updated_at, updated_by, " +
                    "version) values " +
                    "(gen_random_uuid(), '$tenant', 'GRT-$tag-$index', 'user:$tag', " +
                    "'${com.dynokore.gst.access.domain.role.Role.AUDITOR.wire}', 'TENANT', 'S$index', now(), " +
                    "'$status', 'BY_HAND', now(), 's', " +
                    "timestamptz '2026-11-01 00:00:00+00' + interval '${base + index} minutes', 's', 0)",
            )
        }
        sql(
            connection,
            "insert into access.consent (id, tenant_id, consent_ref, client_entity_ref, agent_ref, valid_from, " +
                "authorising_person_email, status, handover_statement_ref, created_at, created_by, updated_at, " +
                "updated_by, version) values (gen_random_uuid(), '$tenant', 'CNS-$tag-1', 'CLI-$tag', 'AGT-$tag', " +
                "date '2026-01-01', 'a@b.example', 'LIVE', null, now(), 's', " +
                "timestamptz '2026-11-01 00:00:00+00' + interval '${base + 10} minutes', 's', 0)",
        )
        listOf("ACTIVE", "REVOKED").forEachIndexed { index, status ->
            sql(
                connection,
                "insert into access.service_principal (id, tenant_id, principal_ref, principal_type, scope, status, " +
                    "created_at, created_by, updated_at, updated_by, version) values (gen_random_uuid(), '$tenant', " +
                    "'PRN-$tag-$index', 'INGEST', 'tenant:$tag$index', '$status', now(), 's', " +
                    "timestamptz '2026-11-01 00:00:00+00' + interval '${base + 20 + index} minutes', 's', 0)",
            )
        }
    }

    /** The database every test reads: two tenants seeded, then migrated; built once and stopped at exit. */
    private class Migrated(
        val connection: Connection,
        val policiesBefore: Set<String>,
        val rows: List<Row>,
        val policiesAfter: Set<String>,
        val sequenceAfter: Long,
    )

    private val db: Migrated by lazy { migrate() }

    private fun migrate(): Migrated {
        val container =
            PostgreSQLContainer("postgres:17")
                .withStartupTimeout(Duration.ofMinutes(PG_START_TIMEOUT_MINUTES))
                .withStartupAttempts(PG_START_ATTEMPTS)
                .withCommand(*pgFastCommand)
                .withLabel("inv.agent", "c-inv-t-9061")
        container.start()
        Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
        val connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
        connection.autoCommit = true
        runFile(container, provisioning)
        runFile(container, bindingFunctions())
        flyway(container, "202611061100")
        // interleaved timestamps so that the order across tenants and tables is what the pass has to follow
        seed(connection, UUID.randomUUID(), "a", 0)
        seed(connection, UUID.randomUUID(), "b", 2)
        val before = policies(connection)
        flyway(container, "202611091000")
        val after = observe(connection)
        return Migrated(connection, before, after.rows, after.policies, sequenceLast(connection))
    }

    @Test
    fun `INV-AC-9051 after the migration every row that had a live event has one distinct version in order`() {
        val rows = db.rows
        assertThat(rows).hasSize(2 * (4 + 1 + 2))
        // (a) no row that should carry a version carries null
        assertThat(nullButShouldCarry(rows)).isZero()
        // (b) pairwise different, at least 1, rows with a smaller (updated_at, id) have a smaller value
        assertThat(pairwiseDifferent(rows)).isTrue()
        assertThat(carried(rows).minOf { it.version!! }).isGreaterThanOrEqualTo(1)
        assertThat(inOrder(rows)).isTrue()
        // (c) the PENDING grant and the ACTIVE principal compare equal to null
        assertThat(fixedRows(rows)).hasSize(2 * 2).allSatisfy { assertThat(it.version).isNull() }
        assertThat(fixedRows(rows).map { it.status }).containsOnly("PENDING", "ACTIVE")
        // (d) the policies of schema access are the same before and after
        assertThat(db.policiesAfter).isEqualTo(db.policiesBefore)
        // (e) the sequence is not behind the largest stored value
        assertThat(db.sequenceAfter).isGreaterThanOrEqualTo(carried(rows).maxOf { it.version!! })
    }

    @Test
    fun `INV-AC-9051 the look-alikes of the migration checks are reported`() {
        val rows = db.rows
        val revokedNull =
            rows.map { if (it.status == "REVOKED" && it.table == "role_grant") it.copy(version = null) else it }
        assertThat(nullButShouldCarry(revokedNull)).isPositive()
        val duplicated = rows.map { if (it.shouldCarry) it.copy(version = 7L) else it }
        assertThat(pairwiseDifferent(duplicated)).isFalse()
        val first = carried(rows).minBy { it.version!! }
        val swapped = rows.map { if (it == first) it.copy(updatedAt = "9999") else it }
        assertThat(inOrder(swapped)).isFalse()
        sql(db.connection, "create policy left_behind on access.consent for all to current_user using (true)")
        try {
            assertThat(policies(db.connection)).isNotEqualTo(db.policiesBefore)
        } finally {
            sql(db.connection, "drop policy left_behind on access.consent")
        }
        assertThat(policies(db.connection)).isEqualTo(db.policiesBefore)
    }

    @Test
    fun `INV-AC-9051 a backfilled row replays with the migration's version and no value is drawn`() {
        val last = sequenceLast(db.connection)
        val mapper = JsonMapper.builder().build()
        val source = AuthorityChangedReplaySource(DSL.using(db.connection, SQLDialect.POSTGRES), mapper) { emptyList() }
        val replayed = pages(source)
        val grant = db.rows.first { it.ref == "GRT-a-0" }
        val state = replayed.single { it.dataJson.contains("\"grantRef\":\"GRT-a-0\"") }
        assertThat(mapper.readTree(state.dataJson)["authorityVersion"].asLong()).isEqualTo(grant.version)
        assertThat(sequenceLast(db.connection)).isEqualTo(last)
        val texts = replayed.map { it.dataJson }
        assertThat(texts.none { it.contains("GRT-a-2") }).describedAs("the PENDING grant").isTrue()
        assertThat(texts.none { it.contains("PRN-a-0") }).describedAs("the ACTIVE principal").isTrue()
    }

    private fun pages(source: AuthorityChangedReplaySource): List<ReplayedState> {
        val all = mutableListOf<ReplayedState>()
        var cursor: String? = null
        do {
            val page = source.page(null, cursor, 5)
            all += page.items
            cursor = page.next
        } while (cursor != null)
        return all
    }

    private fun flyway(
        container: PostgreSQLContainer<*>,
        target: String,
    ) {
        Flyway
            .configure()
            .dataSource(container.jdbcUrl, container.username, container.password)
            .schemas("access")
            .defaultSchema("access")
            .locations("filesystem:" + migrations.toAbsolutePath().normalize())
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .target(target)
            .load()
            .migrate()
    }

    private fun bindingFunctions(): Path {
        val ddl = BindingKeyMigration.ddl("access", "access_sys", "access_runtime")
        val file = Files.createTempDirectory("access-binding-9061").resolve("binding-functions.sql")
        Files.writeString(
            file,
            "CREATE EXTENSION IF NOT EXISTS pgcrypto SCHEMA public;\n" +
                "CREATE SCHEMA IF NOT EXISTS access AUTHORIZATION access_sys;\n" +
                "SET ROLE access_sys;\n${ddl}\nRESET ROLE;\n",
        )
        file.toFile().deleteOnExit()
        file.parent.toFile().deleteOnExit()
        return file
    }

    private fun runFile(
        container: PostgreSQLContainer<*>,
        file: Path,
    ) {
        val target = "/tmp/${file.fileName}"
        container.copyFileToContainer(MountableFile.forHostPath(file), target)
        val result =
            container.execInContainer(
                "psql",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                container.username,
                "-d",
                container.databaseName,
                "-f",
                target,
            )
        check(result.exitCode == 0) { "$file failed: ${result.stderr}" }
    }
}

// the same start-up allowance and fast flags as BoundaryFixture (CI-FAST), private to each file there
private const val PG_START_TIMEOUT_MINUTES = 3L
private const val PG_START_ATTEMPTS = 3
private val pgFastCommand =
    arrayOf("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
