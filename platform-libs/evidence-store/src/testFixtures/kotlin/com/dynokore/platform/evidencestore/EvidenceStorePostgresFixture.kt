package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/** Testcontainers needs a concrete subclass for its fluent `self()` typing (Kotlin, unlike Java, has no raw types). */
private class Postgres(image: DockerImageName) : PostgreSQLContainer<Postgres>(image)

/**
 * Isolation-test fixture for this module's own tests (done-when's own
 * PostgreSQL 17/18 matrix, TPL-14): a fresh PostgreSQL container running
 * [evidence-store-test-roles.sql], and the `evidence_record`/`legal_hold`
 * tables this module's own tests exercise, built from
 * [JdbcEvidenceLedger.COLUMNS]/[JdbcLegalHoldRegistry.COLUMNS] plus
 * [EvidenceLedgerSchema.trailerDdl]/[TenantRlsMigration.ddl] — the same
 * generators a consuming service's own migration would use (TPL-12), not a
 * hand-copied duplicate of them.
 *
 * The app `DataSource` is wrapped in tenancy-kernel's own
 * [TenantAwareDataSource] directly, reused rather than reimplemented
 * (CODER-BRIEF): the pooled-connection tenant-leak guarantee it gives is
 * already proven once, by tenancy-kernel's own test suite, and does not need
 * a second proof here.
 */
class EvidenceStorePostgresFixture(image: String) : AutoCloseable {
    private val container: Postgres =
        Postgres(DockerImageName.parse(image)).withInitScript("db/evidence-store-test-roles.sql")

    init {
        container.start()
    }

    fun jdbcUrl(): String = container.jdbcUrl

    /** The migration/owner role — DDL only, used to create the tables and apply the migration trailers. */
    fun ownerDataSource(): DataSource = dataSourceFor(OWNER_USER, OWNER_PASSWORD)

    /**
     * The runtime application role — [TenantAwareDataSource]-wrapped, `NOSUPERUSER`,
     * `NOBYPASSRLS`, owns no table (S2-05): every query through this DataSource is
     * subject to the same RLS and grant restrictions a real service's connection would be.
     */
    fun appDataSource(): DataSource = TenantAwareDataSource(dataSourceFor(APP_USER, APP_PASSWORD))

    /**
     * Creates `evidence_store_test.evidence_record` and
     * `evidence_store_test.legal_hold`, with their migration trailers applied.
     */
    fun createTables(owner: DataSource) {
        owner.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("create table $SCHEMA.$EVIDENCE_TABLE (${JdbcEvidenceLedger.COLUMNS})")
                statement.execute(EvidenceLedgerSchema.trailerDdl(SCHEMA, EVIDENCE_TABLE, APP_USER))

                statement.execute("create table $SCHEMA.$LEGAL_HOLD_TABLE (${JdbcLegalHoldRegistry.COLUMNS})")
                statement.execute(TenantRlsMigration.ddl(SCHEMA, LEGAL_HOLD_TABLE))
                // legal_hold is a mutable control-flag table (FR-D.4 behaviour 2), not evidence:
                // the default privileges (SELECT, INSERT only) are widened here, explicitly, for
                // this one table.
                statement.execute("grant update, delete on $SCHEMA.$LEGAL_HOLD_TABLE to $APP_USER")
            }
        }
    }

    override fun close() {
        container.stop()
    }

    private fun dataSourceFor(
        user: String,
        password: String,
    ): DataSource = PGSimpleDataSource().apply {
        setUrl(container.jdbcUrl)
        this.user = user
        this.password = password
    }

    companion object {
        const val SCHEMA = "evidence_store_test"
        const val EVIDENCE_TABLE = "evidence_record"
        const val LEGAL_HOLD_TABLE = "legal_hold"
        const val OWNER_USER = "evidence_store_owner"
        const val OWNER_PASSWORD = "evidence_store_owner_pw"
        const val APP_USER = "evidence_store_app"
        const val APP_PASSWORD = "evidence_store_app_pw"
    }
}
