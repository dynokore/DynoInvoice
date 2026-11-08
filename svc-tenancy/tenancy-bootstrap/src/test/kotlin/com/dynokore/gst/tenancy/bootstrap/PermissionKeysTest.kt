package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.RolePermissions
import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.InstallationController
import com.dynokore.gst.tenancy.adapters.http.OptionRefusal
import com.dynokore.gst.tenancy.adapters.installation.ReplicaInstallationAuthority
import com.dynokore.gst.tenancy.application.installation.InstallationPermissions
import com.dynokore.gst.tenancy.application.questionnaire.QuestionnaireAnswerStore
import com.dynokore.gst.tenancy.application.questionnaire.QuestionnairePermissions
import com.dynokore.gst.tenancy.contract.model.ElectPlatformRequest
import com.dynokore.gst.tenancy.contract.model.EntityFacts
import com.dynokore.gst.tenancy.contract.model.QuestionnaireAnswersRequest
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * The permission keys of `answerQuestionnaire` (`questionnaire.answer`, both scopes) and `electPlatform` (`vp-52.set`),
 * each decided per request through the authority replica's `decideTenant` (INV-Q-10027; INV-AC-9769, INV-AC-9594). No
 * role name appears in the service: a person holds a key or not.
 */
class PermissionKeysTest {
    private val answer = QuestionnairePermissions.ANSWER
    private val setPlatform = InstallationPermissions.SET_PLATFORM
    private val tenantAdmin get() = TestOperationRoles.holders(answer).single()
    private val operator get() = TestOperationRoles.privateHolders(setPlatform).single()

    private fun run(
        form: String = "PRIVATE",
        block: (ProfileEnv) -> Unit,
    ) {
        BoundaryFixture.runner().withPropertyValues("gst.form=$form").run { context ->
            context.startupFailure?.let { throw it }
            block(ProfileEnv(context))
        }
    }

    private fun ProfileEnv.call(
        tenant: TenantId,
        entity: String?,
    ) = tenantsApi.answerQuestionnaire(
        tenant.value.toString(),
        UUID.randomUUID(),
        QuestionnaireAnswersRequest(
            "2026.1",
            mapOf("OQ-1" to "one"),
            entity,
            entity?.let { EntityFacts(recordsPurchases = true) },
        ),
    )

    private fun ProfileEnv.storedAnswers() =
        inTx { context.getBean(QuestionnaireAnswerStore::class.java).find("2026.1") }

    private fun entityRows(): Long =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                s.executeQuery("select count(*) from tenancy.entity_fact").use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private fun status(block: () -> Any?): Int? =
        (runCatching { block() }.exceptionOrNull() as? ApiRefusal)?.code?.httpStatus

    /** The call is refused 403 at both scopes and nothing is stored (answers or facts). */
    private fun ProfileEnv.assertDenied(label: String) {
        withEntity { tenant, entity ->
            val answersBefore = storedAnswers()
            val factsBefore = entityRows()
            assertThat(status { call(tenant, null) }).describedAs("$label tenant scope").isEqualTo(403)
            assertThat(status { call(tenant, entity.entityRef) }).describedAs("$label entity scope").isEqualTo(403)
            assertThat(storedAnswers()).isEqualTo(answersBefore)
            assertThat(entityRows()).isEqualTo(factsBefore)
        }
    }

    @Test
    fun `INV-AC-9769 a person granted questionnaire-answer answers at tenant scope and at entity scope`() {
        run { env ->
            env.withEntity { tenant, entity ->
                env.allowRole(tenant, tenantAdmin)
                assertThat(env.call(tenant, null).statusCode.value()).isEqualTo(201)
                val before = entityRows()
                assertThat(env.call(tenant, entity.entityRef).statusCode.value()).isEqualTo(201)
                assertThat(entityRows()).isEqualTo(before + 1)
            }
        }
    }

    @Test
    fun `INV-AC-9769 a person granted every entity-admin key and not questionnaire-answer is 403 at both scopes`() {
        run { env ->
            DeploymentForm.entries.forEach { form ->
                assertThat(RolePermissions.cells(form).getValue("entity.admin").keys).doesNotContain(answer)
            }
            env.withEntity { tenant, entity ->
                // every key entity.admin holds is granted at once, through the role grant that carries them
                env.allowRole(tenant, "entity.admin")
                val before = entityRows()
                assertThat(status { env.call(tenant, null) }).isEqualTo(403)
                assertThat(status { env.call(tenant, entity.entityRef) }).isEqualTo(403)
                assertThat(entityRows()).isEqualTo(before)
                assertThat(env.storedAnswers()).isNull()
            }
        }
    }

    @Test
    fun `INV-AC-9769 a person with no grant is 403 at both scopes and nothing is stored`() {
        run { env -> env.assertDenied("no grant") }
    }

    @Test
    fun `INV-AC-9769 the look-alike keys questionnaire-answers and questionnaire-answer-dash do not grant`() {
        run { env ->
            listOf("questionnaire.answers", "questionnaire.answer-", "Questionnaire.answer").forEach { key ->
                // a look-alike key has no holder in the matrix, so no role grant can carry it
                assertThat(TestOperationRoles.holders(key)).describedAs(key).isEmpty()
                env.withEntity { tenant, entity ->
                    env.allowRole(tenant, "entity.admin")
                    assertThat(status { env.call(tenant, null) }).describedAs(key).isEqualTo(403)
                    assertThat(status { env.call(tenant, entity.entityRef) }).describedAs(key).isEqualTo(403)
                }
            }
        }
    }

    @Test
    fun `INV-AC-9769 the holders of questionnaire-answer are exactly tenant-admin in SAAS and in PRIVATE`() {
        DeploymentForm.entries.forEach { form ->
            val holders = RolePermissions.cells(form).filterValues { it.containsKey(answer) }.keys
            assertThat(holders).describedAs("holders in $form").isEqualTo(setOf("tenant.admin"))
        }
        assertThat(DeploymentForm.entries.map { it.name }).containsExactlyInAnyOrder("SAAS", "PRIVATE")
    }

    @Test
    fun `INV-AC-9769 an entity-scope call leaves the stored tenant answers as they were`() {
        run { env ->
            env.withEntity { tenant, entity ->
                env.allowRole(tenant, tenantAdmin)
                env.call(tenant, null)
                val before = env.storedAnswers()
                assertThat(before).isNotNull()
                val other =
                    env.tenantsApi.answerQuestionnaire(
                        tenant.value.toString(),
                        UUID.randomUUID(),
                        QuestionnaireAnswersRequest(
                            "2026.1",
                            mapOf("OQ-1" to "more"),
                            entity.entityRef,
                            EntityFacts(recordsPurchases = false),
                        ),
                    )
                assertThat(other.statusCode.value()).isEqualTo(201)
                assertThat(env.storedAnswers()).isEqualTo(before)
            }
        }
    }

    private fun ProfileEnv.elect() =
        context
            .getBean(InstallationController::class.java)
            .electPlatform(UUID.randomUUID(), ElectPlatformRequest(ElectPlatformRequest.Platform.CUSTOMER_KUBERNETES))

    private fun ProfileEnv.election() =
        runCatching { context.getBean(InstallationController::class.java).getPlatformElection().body }.getOrNull()

    private fun ProfileEnv.bound(
        tenant: TenantId?,
        block: () -> Unit,
    ) = if (tenant == null) block() else tenants.bind(tenant, "user:test") { block() }

    @Test
    fun `INV-AC-9594 vp-52-set at INSTALLATION scope elects with no tenant bound or an unrelated one`() {
        listOf<TenantId?>(null, TenantId(UUID.randomUUID())).forEach { bound ->
            run { env ->
                env.allowInstallationRole(operator)
                env.bound(bound) {
                    assertThat(env.elect().statusCode.value()).describedAs("bound=$bound").isEqualTo(201)
                    assertThat(env.election()!!.platform.value).isEqualTo("CUSTOMER_KUBERNETES")
                }
            }
        }
    }

    @Test
    fun `INV-AC-9594 a person granted vp-52-set only at TENANT scope is 403 with the tenant bound and with none`() {
        run { env ->
            val tenant = TenantId(UUID.randomUUID())
            env.allowRole(tenant, operator)
            val before = env.election()
            listOf<TenantId?>(tenant, null).forEach { bound ->
                env.bound(bound) { assertThat(status { env.elect() }).describedAs("bound=$bound").isEqualTo(403) }
            }
            assertThat(env.election()).isEqualTo(before)
        }
    }

    @Test
    fun `INV-AC-9594 a person without vp-52-set is 403 and nothing is stored`() {
        run { env ->
            val before = env.election()
            assertThat(status { env.elect() }).isEqualTo(403)
            assertThat(env.election()).isEqualTo(before)
        }
    }

    @Test
    fun `INV-AC-9594 a person granted questionnaire-answer and not vp-52-set is 403, look-alikes hold no role`() {
        run { env ->
            val before = env.election()
            // tenant.admin holds questionnaire.answer and is granted at both scopes; it is no holder of vp-52.set
            env.allowInstallationRole(tenantAdmin)
            env.allowRole(TenantId(UUID.randomUUID()), tenantAdmin)
            assertThat(status { env.elect() }).isEqualTo(403)
            listOf("vp-52.get", "vp-52.sets", "VP-52.set").forEach {
                assertThat(TestOperationRoles.privateHolders(it)).describedAs(it).isEmpty()
            }
            assertThat(env.election()).isEqualTo(before)
        }
    }

    @Test
    fun `INV-AC-9594 a DEGRADED answer and no replica bound refuse the person`() {
        val clock = java.time.Clock.systemUTC()
        val replica = AuthorityReplica(clock, TestOperationRoles.privateMap)
        assertThat(ReplicaInstallationAuthority(null).holds("identity:USR-001", setPlatform)).isFalse()
        assertThat(ReplicaInstallationAuthority(replica).holds("identity:USR-001", setPlatform)).isFalse()
        replica.onSnapshotComplete(0)
        replica.accept(
            AuthorityChanged(
                TenantId.PLATFORM,
                "identity:USR-001",
                1,
                AuthorityRecord.RoleGrantRecord(
                    "GR-1",
                    RoleGrantState.ACTIVE,
                    RoleGrant(operator, GrantScope.INSTALLATION, null, clock.instant().minusSeconds(60)),
                ),
            ),
        )
        replica.observeHead(1)
        // the control: the same grant, in a complete replica, holds the key
        assertThat(ReplicaInstallationAuthority(replica).holds("identity:USR-001", setPlatform)).isTrue()
    }

    @Test
    fun `INV-AC-9594 the SaaS form is refused as built even for a person who holds vp-52-set at INSTALLATION scope`() {
        run("SAAS") { env ->
            env.allowInstallationRole(operator)
            // the key is held, so the refusal is the form's (POL-011), not the permission's
            val thrown = runCatching { env.elect() }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(OptionRefusal::class.java)
        }
    }

    @Test
    fun `INV-AC-9594 the holders of vp-52-set are exactly deployment-operator in PRIVATE and there is no SaaS cell`() {
        val private = RolePermissions.cells(DeploymentForm.PRIVATE).filterValues { it.containsKey(setPlatform) }.keys
        assertThat(private).isEqualTo(setOf("deployment.operator"))
        val saas = RolePermissions.cells(DeploymentForm.SAAS).filterValues { it.containsKey(setPlatform) }.keys
        assertThat(saas).isEmpty()
    }

    @Test
    fun `the gstCatalogue pin is exactly 2_45_0, the release that publishes questionnaire-answer`() {
        val toml = Files.readString(Path.of("..", "gradle", "libs.versions.toml"))
        val pin = Regex("""(?m)^gstCatalogue\s*=\s*"([^"]*)"\s*$""").find(toml)!!.groupValues[1]
        assertThat(pin).isEqualTo("2.45.0")
    }
}
