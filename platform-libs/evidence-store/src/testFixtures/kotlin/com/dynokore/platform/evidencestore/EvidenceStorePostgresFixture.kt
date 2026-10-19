package com.dynokore.platform.evidencestore

import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.SignedBinding
import com.dynokore.platform.tenancykernel.TenantRlsMigration
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

/** Testcontainers needs a concrete subclass for its fluent `self()` typing (Kotlin, unlike Java, has no raw types). */
private class Postgres(
    image: DockerImageName,
) : PostgreSQLContainer<Postgres>(image)

/**
 * Isolation-test fixture for this module's own tests (done-when's own
 * PostgreSQL 16/17/18 matrix, TPL-14): a fresh PostgreSQL container whose init script
 * (route R1, owner ruling INV-Q-4508 (A)) runs [evidence-store-test-roles.sql], then [bindingInit] (the binding key
 * table
 * and functions, before the first policy; INV-Q-6679), then, as the owner role, creates
 * the `evidence_record`/`legal_hold` tables this module's own tests exercise, built from
 * [JdbcEvidenceLedger.COLUMNS]/[JdbcLegalHoldRegistry.COLUMNS] plus
 * [EvidenceLedgerSchema.trailerDdl]/[TenantRlsMigration.ddl] — the same
 * generators a consuming service's own migration would use (TPL-12), not a
 * hand-copied duplicate of them — and then [extraInitSql]. A test body issues no DDL.
 *
 * A test builds its pools with tenancy-kernel's `TestPoolFixture` from [jdbcUrl] and the logins below, and builds each
 * runner with [keys] so every transaction runs under a signed binding (INV-T-4156).
 */
class EvidenceStorePostgresFixture(
    image: String,
    extraInitSql: String = "",
) : AutoCloseable {
    private val container: Postgres =
        Postgres(DockerImageName.parse(image))
            .withCopyToContainer(
                Transferable.of(initSql(extraInitSql)),
                "/docker-entrypoint-initdb.d/evidence-store.sql",
            )

    init {
        container.start()
    }

    /** The key source of the signed binding, from tenancy-kernel's [SignedBinding]. */
    val keys: BindingKeySource = SignedBinding.keys()

    fun jdbcUrl(): String = container.jdbcUrl

    override fun close() {
        container.stop()
    }

    companion object {
        const val SCHEMA = "evidence_store_test"
        const val EVIDENCE_TABLE = "evidence_record"
        const val LEGAL_HOLD_TABLE = "legal_hold"
        const val OWNER_USER = "evidence_store_owner"
        const val OWNER_PASSWORD = "evidence_store_owner_pw"
        const val APP_USER = "evidence_store_app"
        const val SYS_ROLE = "evidence_store_sys"
        const val APP_PASSWORD = "evidence_store_app_pw"

        private fun initSql(extra: String): String {
            val roles =
                checkNotNull(
                    EvidenceStorePostgresFixture::class.java.getResource("/db/evidence-store-test-roles.sql"),
                ) {
                    "evidence-store-test-roles.sql is missing"
                }.readText()
            return roles + "\n" +
                bindingInit(SCHEMA, SignedBinding.key(), SYS_ROLE, APP_USER) +
                // FORCE ROW LEVEL SECURITY applies the policy to the table owner too, which needs the function
                "GRANT EXECUTE ON FUNCTION $SCHEMA.bound_tenant() TO $OWNER_USER;\n" +
                "\nSET ROLE $OWNER_USER;\n" +
                "create table $SCHEMA.$EVIDENCE_TABLE (${JdbcEvidenceLedger.COLUMNS});\n" +
                EvidenceLedgerSchema.trailerDdl(SCHEMA, EVIDENCE_TABLE, APP_USER) + "\n" +
                "create table $SCHEMA.$LEGAL_HOLD_TABLE (${JdbcLegalHoldRegistry.COLUMNS});\n" +
                TenantRlsMigration.ddl(SCHEMA, LEGAL_HOLD_TABLE) + "\n" +
                // legal_hold is a mutable control-flag table (FR-D.4 behaviour 2), not evidence: the default
                // privileges (SELECT, INSERT only) are widened here, explicitly, for this one table.
                "grant update, delete on $SCHEMA.$LEGAL_HOLD_TABLE to $APP_USER;\n" +
                extra + "\nRESET ROLE;\n"
        }
    }
}
