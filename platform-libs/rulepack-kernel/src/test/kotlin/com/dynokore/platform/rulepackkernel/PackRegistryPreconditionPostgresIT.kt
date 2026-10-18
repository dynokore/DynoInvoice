package com.dynokore.platform.rulepackkernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

private class BarePostgres(
    image: DockerImageName,
) : PostgreSQLContainer<BarePostgres>(image)

/**
 * Precondition sample of INV-T-5364 (coordinator ruling INV-Q-6663): the module's DDL does not create the binding
 * functions, so applied to a schema without `bound_tenant()` and `bound_actor()` it raises SQLSTATE 42883 at the first
 * policy that names `bound_tenant()`, and the whole script, one implicit transaction, leaves the catalogue as it was.
 */
@Tag("integration")
class PackRegistryPreconditionPostgresIT {
    @ParameterizedTest
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the DDL on a schema without the binding functions raises 42883 and changes nothing`(image: String) {
        val container = BarePostgres(DockerImageName.parse(image)).withRolesOnlyInit()
        container.start()
        try {
            psql(container, container.username, "CREATE SCHEMA rulepack_bare AUTHORIZATION $MIGRATE;")
            val before = counts(container)
            assertEquals("0|0|0", before, "the bare schema holds no table, policy or function")

            val ddl = packRegistryDdl("rulepack_bare")
            val result = psql(container, MIGRATE, ddl)
            assertNotEquals(0, result.exitCode, "the DDL was applied to a schema without the binding functions")
            val text = result.stderr
            assertTrue("42883" in text, "expected SQLSTATE 42883, saw: ${text.lines().firstOrNull { "ERROR" in it }}")
            assertTrue("bound_tenant" in text, "the first policy that names bound_tenant() raised: $text")

            assertEquals(before, counts(container), "tables|policies|functions of the schema before and after")
        } finally {
            container.stop()
        }
    }

    /** `tables|policies|functions` of schema `rulepack_bare`, read by the superuser from the catalogue. */
    private fun counts(container: BarePostgres): String =
        psql(
            container,
            container.username,
            "SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = 'rulepack_bare' AND c.relkind IN ('r', 'p')) || '|' || " +
                "(SELECT count(*) FROM pg_policies WHERE schemaname = 'rulepack_bare') || '|' || " +
                "(SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace " +
                "WHERE n.nspname = 'rulepack_bare');",
        ).stdout.trim()

    /** Runs [sql] with psql as [login] on the container's socket; `-c` sends it as one implicit transaction. */
    private fun psql(
        container: BarePostgres,
        login: String,
        sql: String,
    ) = container.execInContainer(
        "psql",
        "-U",
        login,
        "-d",
        container.databaseName,
        "-At",
        "-v",
        "ON_ERROR_STOP=1",
        "-v",
        "VERBOSITY=verbose",
        "-c",
        sql,
    )
}
