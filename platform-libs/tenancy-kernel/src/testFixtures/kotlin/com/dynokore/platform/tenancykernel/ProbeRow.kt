package com.dynokore.platform.tenancykernel

import java.util.UUID

/** The `tenancy_kernel_test.probe` row shape, for assertions in a test's own package. */
data class ProbeRow(val id: UUID, val tenantId: TenantId, val value: String)
