package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.InstallationController
import com.dynokore.gst.tenancy.adapters.http.OptionRefusal
import com.dynokore.gst.tenancy.application.installation.InstallationPermissions
import com.dynokore.gst.tenancy.contract.model.ElectPlatformRequest
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * electPlatform and getPlatformElection through the real application context over PostgreSQL (INV-REQ-1313; ruling
 * INV-Q-9825 A). The election is shared by every test of the container, so a test reads only what it wrote itself.
 */
class InstallationStatusTest {
    private fun run(
        form: String,
        block: (ProfileEnv) -> Unit,
    ) {
        BoundaryFixture.runner().withPropertyValues("gst.form=$form").run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            // electPlatform is decided by `vp-52.set` per request (INV-Q-10027), held at INSTALLATION scope
            env.tenants.bind(INSTALLATION_TENANT, "user:test") {
                env.allowInstallationRole(
                    TestOperationRoles.privateHolders(InstallationPermissions.SET_PLATFORM).single(),
                )
                block(env)
            }
        }
    }

    private fun ProfileEnv.api() = context.getBean(InstallationController::class.java)

    @Test
    fun `INV-AC-2301 a private installation elects customer Kubernetes and the election is read back`() {
        run("PRIVATE") { env ->
            val elected =
                env.api().electPlatform(
                    UUID.randomUUID(),
                    ElectPlatformRequest(ElectPlatformRequest.Platform.CUSTOMER_KUBERNETES),
                )
            assertThat(elected.statusCode.value()).isEqualTo(201)
            val read = env.api().getPlatformElection().body!!
            assertThat(read.platform.value).isEqualTo("CUSTOMER_KUBERNETES")
            assertThat(read.electedAt).isEqualTo(elected.body!!.electedAt)
            // a second election replaces the first
            val again =
                env.api().electPlatform(
                    UUID.randomUUID(),
                    ElectPlatformRequest(ElectPlatformRequest.Platform.CUSTOMER_KUBERNETES),
                )
            assertThat(
                env
                    .api()
                    .getPlatformElection()
                    .body!!
                    .electedAt,
            ).isEqualTo(again.body!!.electedAt)
            assertThat(again.body!!.electedAt).isAfterOrEqualTo(elected.body!!.electedAt)
        }
    }

    @Test
    fun `INV-AC-2666 an election of the supplied appliance is refused with POL-011 and nothing is stored`() {
        run("PRIVATE") { env ->
            env.api().electPlatform(
                UUID.randomUUID(),
                ElectPlatformRequest(ElectPlatformRequest.Platform.CUSTOMER_KUBERNETES),
            )
            val before = env.api().getPlatformElection().body
            val thrown =
                runCatching {
                    env.api().electPlatform(
                        UUID.randomUUID(),
                        ElectPlatformRequest(ElectPlatformRequest.Platform.SUPPLIED_APPLIANCE),
                    )
                }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(OptionRefusal::class.java)
            assertThat(env.api().getPlatformElection().body).isEqualTo(before)
        }
    }

    @Test
    fun `every election in the SaaS form is refused`() {
        run("SAAS") { env ->
            ElectPlatformRequest.Platform.entries.forEach { platform ->
                val thrown = runCatching { env.api().electPlatform(UUID.randomUUID(), ElectPlatformRequest(platform)) }
                assertThat(thrown.exceptionOrNull()).isInstanceOf(OptionRefusal::class.java)
            }
        }
    }

    @Test
    fun `INV-AC-2301 before any election the read is 404 and there is no default`() {
        run("PRIVATE") { env ->
            BoundaryFixture.asSuperuser { c ->
                c.prepareStatement("delete from tenancy.installation_fact where fact_key = ?").use { s ->
                    s.setString(1, "platformElection")
                    s.executeUpdate()
                }
            }
            val thrown = runCatching { env.api().getPlatformElection() }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(ApiRefusal::class.java)
            assertThat((thrown as ApiRefusal).code.httpStatus).isEqualTo(404)
        }
    }

    private companion object {
        val INSTALLATION_TENANT = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000b1"))
    }
}
