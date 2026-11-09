package com.dynokore.gst.rules.bootstrap

import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** The tenant of [PartialExemptionBoundaryTest]: its packs are tenant-owned, so no other test's pack is resolved. */
val TENANT_PE: TenantId = TenantId.of("0b000000-0000-0000-0000-00000000000b")
const val USER_PE_TOKEN = "Bearer token-user-pe"

/** The fixed clock of the test: later than every date used (INV-AC-18751 needs a clock later than D). */
val PE_CLOCK: Clock = Clock.fixed(Instant.parse("2029-01-01T00:00:00Z"), ZoneOffset.UTC)

/**
 * The identity doubles and the fixed clock of the test. It deliberately holds no scope double: the
 * `ScopeVerdictPort` of the context is the production port over the persisted SCOPE pack.
 */
@TestConfiguration(proxyBeanMethods = false)
class PartialExemptionBoundaryDoubles {
    @Bean
    fun peEndUserPrincipalResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            if (authorization == USER_PE_TOKEN) EndUserPrincipal("user-pe", TENANT_PE, emptySet()) else null
        }

    @Bean
    fun peWorkloadTokenVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token: WorkloadToken? ->
            val value = token?.value
            if (value != null && value.startsWith("wl-")) {
                WorkloadVerification.Verified("subject-of-" + value.removePrefix("wl-"))
            } else {
                WorkloadVerification.Refused("no test token")
            }
        }

    @Bean
    fun clock(): Clock = PE_CLOCK
}
