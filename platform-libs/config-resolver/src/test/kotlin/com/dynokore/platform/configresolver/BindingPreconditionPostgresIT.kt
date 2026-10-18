package com.dynokore.platform.configresolver

import com.dynokore.platform.configresolver.ConfigSchema.EventMode
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID

/**
 * INV-T-5362, INV-Q-6663: [ConfigSchema.ddl] does not create `bound_tenant()` and `bound_actor()`; the schema must
 * already hold them. On a schema without them, in each [EventMode], the DDL applies (the harness starts) and each
 * definer function raises SQLSTATE 42883 when called with a valid platform binding, changing no row. PostgreSQL 16,
 * 17, 18.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BindingPreconditionPostgresIT {
    private val platform = TenantId.PLATFORM.value.toString()

    @ParameterizedTest(name = "{displayName}[{0} {1}]")
    @CsvSource("postgres:16,OUTBOX", "postgres:17,NO_OUTBOX", "postgres:18,OUTBOX", "postgres:16,NO_OUTBOX")
    fun `without the binding functions each definer function raises 42883 and changes nothing`(
        image: String,
        mode: EventMode,
    ) {
        WriteHarness(image, mode, withBindingFunctions = false).use { h ->
            // the DDL applied: the container's init script ran it as the migration login, and the functions exist
            assertEquals(
                3L,
                h.count(
                    "select count(*) from pg_proc where pronamespace = 'svc'::regnamespace and proname in " +
                        "('${ConfigSchema.WRITE_INSTALLATION_SETTING}', '${ConfigSchema.PURGE_SUBJECT}', " +
                        "'${ConfigSchema.PURGE_TENANT}')",
                ),
            )
            assertEquals(
                0L,
                h.count(
                    "select count(*) from pg_proc where pronamespace = 'svc'::regnamespace and proname in " +
                        "('bound_tenant', 'bound_actor')",
                ),
            )

            fun rows() =
                h.stored() +
                    listOf("config_setting", "config_resolved_value", "config_relaxation_acknowledgement")
                        .map { h.count("select count(*) from svc.$it") }
            val before = rows()
            val seen =
                listOf(
                    h.refusal { h.callInstallation(h.staged()) }.sqlState,
                    h.refusedRaw("select svc.${ConfigSchema.PURGE_SUBJECT}('submission', 'doc-1/v1')", platform, "op"),
                    h.refusedRaw(
                        "select * from svc.${ConfigSchema.PURGE_TENANT}('${UUID.randomUUID()}')",
                        platform,
                        "op",
                    ),
                )
            assertEquals(listOf("42883", "42883", "42883"), seen, "SQLSTATE seen, in the order of the functions")
            assertEquals(before, rows(), "a function changed rows without the binding functions")
        }
    }
}
