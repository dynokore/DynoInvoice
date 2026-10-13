package com.dynokore.platform.tenancyjooq

/**
 * How tenancy-jooq constrains a table (INV-ADR-3968 item 4a; owner ruling INV-Q-4113; CS-19).
 *
 * - [TENANT], the default for a table with a `tenant_id` column: every read and write is limited to the bound
 *   tenant's rows, `tenant_id = <bound tenant>`.
 * - [REFERENCE]: the tenant's own rows plus the platform rows ([com.dynokore.platform.tenancykernel.TenantId.PLATFORM])
 *   can be read, `tenant_id IN (<bound tenant>, <platform>)`; only the bound tenant's rows are written, and a platform
 *   row is never written through jOOQ (platform rows are written by a definer function, INV-ADR-3968 item 6).
 * - [PLATFORM_SCOPED]: a table without a tenant column and without tenant data (its migration carries the comment
 *   `platform-scoped: no tenant data`). It is read under any binding and written only under the platform-tenant
 *   binding.
 */
enum class TableClass {
    TENANT,
    REFERENCE,
    PLATFORM_SCOPED,
}
