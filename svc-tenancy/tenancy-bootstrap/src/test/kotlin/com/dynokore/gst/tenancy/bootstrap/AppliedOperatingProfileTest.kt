package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.contract.model.AppliedOperatingProfileRecord
import com.dynokore.gst.tenancy.contract.model.RecordAppliedOperatingProfileRequest
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * recordAppliedOperatingProfile and getAppliedOperatingProfile through the real application context over PostgreSQL
 * with forced row-level security (INV-REQ-1245 b2; owner answer to INV-Q-5022 A). Test names carry the criterion key.
 */
class AppliedOperatingProfileTest {
    private val tenantScope = RecordAppliedOperatingProfileRequest.Scope.TENANT
    private val entityScope = RecordAppliedOperatingProfileRequest.Scope.ENTITY

    private fun ref(tenant: TenantId) = tenant.value.toString()

    private fun run(block: (ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(ProfileEnv(context))
        }
    }

    private fun ProfileEnv.record(
        tenant: TenantId,
        scope: RecordAppliedOperatingProfileRequest.Scope,
        entityRef: String?,
        profileRef: String,
        version: Int,
    ) = tenantsApi
        .recordAppliedOperatingProfile(
            ref(tenant),
            UUID.randomUUID(),
            RecordAppliedOperatingProfileRequest(scope, profileRef, version, entityRef),
        )

    private fun ProfileEnv.read(
        tenant: TenantId,
        scope: String,
        entityRef: String?,
    ): AppliedOperatingProfileRecord = tenantsApi.getAppliedOperatingProfile(ref(tenant), scope, entityRef).body!!

    private fun refusal(block: () -> Any?): ClosedErrorCode {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(ApiRefusal::class.java)
        return (thrown as ApiRefusal).code
    }

    @Test
    fun `INV-AC-9592 each scope is read back as written and a new record replaces only its own scope`() {
        run { env ->
            env.withEntity { tenant, entity ->
                val tenantWritten = env.record(tenant, tenantScope, null, "PROF-A", 1)
                val entityWritten = env.record(tenant, entityScope, entity.entityRef, "PROF-A", 1)
                assertThat(tenantWritten.statusCode.value()).isEqualTo(200)
                assertThat(entityWritten.statusCode.value()).isEqualTo(200)
                assertThat(env.read(tenant, "TENANT", null)).isEqualTo(tenantWritten.body)
                assertThat(env.read(tenant, "ENTITY", entity.entityRef)).isEqualTo(entityWritten.body)
                assertThat(env.read(tenant, "TENANT", null))
                    .isEqualTo(
                        AppliedOperatingProfileRecord(
                            ref(tenant),
                            AppliedOperatingProfileRecord.Scope.TENANT,
                            "PROF-A",
                            1,
                        ),
                    )

                env.record(tenant, tenantScope, null, "PROF-A", 2)
                assertThat(env.read(tenant, "TENANT", null).profileVersion).isEqualTo(2)
                assertThat(env.read(tenant, "ENTITY", entity.entityRef).profileVersion).isEqualTo(1)
                env.record(tenant, entityScope, entity.entityRef, "PROF-B", 4)
                assertThat(env.read(tenant, "ENTITY", entity.entityRef).profileRef).isEqualTo("PROF-B")
                assertThat(env.read(tenant, "TENANT", null).profileRef).isEqualTo("PROF-A")
            }
        }
    }

    @Test
    fun `INV-AC-9592 a record with a wrong scope or version is refused with 400 and a scope with no record is 404`() {
        run { env ->
            env.withEntity { tenant, entity ->
                assertThat(refusal { env.record(tenant, entityScope, null, "PROF-A", 1) })
                    .isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
                assertThat(refusal { env.record(tenant, tenantScope, entity.entityRef, "PROF-A", 1) })
                    .isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
                assertThat(refusal { env.record(tenant, tenantScope, null, "PROF-A", 0) })
                    .isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
                assertThat(refusal { env.record(tenant, tenantScope, null, "", 1) })
                    .isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
                assertThat(refusal { env.read(tenant, "ENTITY", null) }).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
                assertThat(refusal { env.read(tenant, "TENANT", entity.entityRef) })
                    .isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
                assertThat(refusal { env.read(tenant, "GROUP", null) }).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)

                assertThat(refusal { env.read(tenant, "TENANT", null) }).isEqualTo(ClosedErrorCode.NOT_FOUND)
                assertThat(
                    refusal { env.read(tenant, "ENTITY", entity.entityRef) },
                ).isEqualTo(ClosedErrorCode.NOT_FOUND)
                assertThat(refusal { env.read(tenant, "ENTITY", "ENT-UNKNOWN") }).isEqualTo(ClosedErrorCode.NOT_FOUND)
                assertThat(refusal { env.record(tenant, entityScope, "ENT-UNKNOWN", "PROF-A", 1) })
                    .isEqualTo(ClosedErrorCode.NOT_FOUND)
                // a refused record leaves no record behind
                assertThat(refusal { env.read(tenant, "TENANT", null) }).isEqualTo(ClosedErrorCode.NOT_FOUND)
            }
        }
    }

    @Test
    fun `INV-AC-9592 INV-AC-2231 a catalogue with a higher profile version changes no recorded version`() {
        run { env ->
            env.withEntity { tenant, entity ->
                env.record(tenant, tenantScope, null, "PROF-A", 1)
                env.record(tenant, entityScope, entity.entityRef, "PROF-A", 1)
                val before = listOf(env.read(tenant, "TENANT", null), env.read(tenant, "ENTITY", entity.entityRef))
                // The catalogue is the profile owner's, not this service's: loading version 3 of PROF-A reaches
                // nothing here, so no operation of this service is called and the records are read again.
                val catalogue = mapOf("PROF-A" to listOf(1, 2, 3))
                assertThat(catalogue.getValue("PROF-A").max()).isGreaterThan(before.maxOf { it.profileVersion })
                val after = listOf(env.read(tenant, "TENANT", null), env.read(tenant, "ENTITY", entity.entityRef))
                assertThat(after).isEqualTo(before)
            }
        }
    }

    @Test
    fun `a record of one tenant is not visible to another tenant and an entity of another tenant is not found`() {
        run { env ->
            env.withEntity { first, entity ->
                env.record(first, tenantScope, null, "PROF-A", 1)
                env.withTenant { second ->
                    assertThat(refusal { env.read(second, "TENANT", null) }).isEqualTo(ClosedErrorCode.NOT_FOUND)
                    assertThat(refusal { env.record(second, entityScope, entity.entityRef, "PROF-A", 1) })
                        .isEqualTo(ClosedErrorCode.NOT_FOUND)
                    // another tenant's ref names a tenant that is not the bound one
                    assertThat(refusal { env.read(first, "TENANT", null) }).isEqualTo(ClosedErrorCode.NOT_FOUND)
                }
            }
        }
    }
}
