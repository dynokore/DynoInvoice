package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.application.questionnaire.QuestionnaireAnswerStore
import com.dynokore.gst.tenancy.application.questionnaire.QuestionnairePermissions
import com.dynokore.gst.tenancy.contract.model.AppliedOperatingProfileRecord
import com.dynokore.gst.tenancy.contract.model.QuestionnaireAnswersRequest
import com.dynokore.gst.tenancy.contract.model.QuestionnaireAnswersResponse
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * answerQuestionnaire through the real application context over PostgreSQL with forced row-level security
 * (INV-REQ-1246; owner answer INV-Q-9831 B). Each test asserts (a) the stored answers equal the submitted ones, (b) the
 * applied operating profile reads the same after the call as before it, and (c) the recommendation entries.
 */
class QuestionnaireTest {
    private fun run(block: (ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(ProfileEnv(context))
        }
    }

    private fun ProfileEnv.answer(
        tenant: TenantId,
        version: String,
        answers: Map<String, String>,
    ): QuestionnaireAnswersResponse {
        allowQuestionnaire(tenant)
        val response =
            tenantsApi.answerQuestionnaire(
                tenant.value.toString(),
                UUID.randomUUID(),
                QuestionnaireAnswersRequest(version, answers),
            )
        assertThat(response.statusCode.value()).isEqualTo(201)
        return response.body!!
    }

    private fun ProfileEnv.stored(version: String): Map<String, String>? =
        inTx { context.getBean(QuestionnaireAnswerStore::class.java).find(version) }

    private fun ProfileEnv.storedCount(): Int = inTx { context.getBean(QuestionnaireAnswerStore::class.java).count() }

    /** The applied profile of both scopes as the operation answers: the record, or 404 when none exists. */
    private fun ProfileEnv.profiles(
        tenant: TenantId,
        entityRef: String,
    ): List<Any> =
        listOf(
            tenant to null,
            tenant to entityRef,
        ).map { (_, entity) ->
            val scope = if (entity == null) "TENANT" else "ENTITY"
            val thrown = runCatching { tenantsApi.getAppliedOperatingProfile(tenant.value.toString(), scope, entity) }
            thrown.getOrNull()?.body ?: (thrown.exceptionOrNull() as ApiRefusal).code
        }

    private fun QuestionnaireAnswersResponse.refs(kind: String? = null) =
        recommendations!!.filter { kind == null || it.kind.value == kind }.map { it.ref }

    @Test
    fun `INV-AC-2232 an agent that also files the return recommends PRF-5 with its reason and applies nothing`() {
        run { env ->
            env.withEntity { tenant, entity ->
                val before = env.profiles(tenant, entity.entityRef)
                val answers = mapOf("OQ-2" to "an agent also files the return", "OQ-1" to "one")
                val response = env.answer(tenant, "2026.1", answers)

                // (a) the stored answers equal the submitted ones, question by question
                assertThat(env.stored("2026.1")).isEqualTo(answers)
                // (b) nothing is applied
                assertThat(env.profiles(tenant, entity.entityRef)).isEqualTo(before)
                // (c) the PROFILE entry PRF-5 carries the answer that led to it
                val profile = response.recommendations!!.single { it.kind.value == "PROFILE" && it.ref == "PRF-5" }
                assertThat(profile.reason).isEqualTo("OQ-2=an agent also files the return")
                assertThat(response.recommendedProfile).isEmpty()
                assertThat(response.reasons).isEmpty()
            }
        }
    }

    @Test
    fun `INV-AC-2387 a GST group operated centrally gets no PRF-3 entry and its variation points one by one`() {
        run { env ->
            env.withEntity { tenant, entity ->
                val before = env.profiles(tenant, entity.entityRef)
                val answers = mapOf("OQ-5" to "a GST group", "OQ-1" to "more")
                val response = env.answer(tenant, "2026.1", answers)

                assertThat(env.stored("2026.1")).isEqualTo(answers)
                assertThat(env.profiles(tenant, entity.entityRef)).isEqualTo(before)
                assertThat(response.recommendations!!.filter { it.ref == "PRF-3" }).isEmpty()
                assertThat(response.refs()).contains("VP-01", "VP-02", "VP-15", "VP-28", "VP-33")
                val reasons = response.recommendations!!.associate { it.ref to it.reason }
                // the first place of a variation point listed for both questions is that of OQ-1 (numeric order)
                assertThat(response.recommendations!!.filter { it.ref in setOf("VP-28", "VP-33") }.map { it.reason })
                    .contains("OQ-1=more", "OQ-5=a GST group")
                assertThat(reasons.getValue("VP-51")).isEqualTo("OQ-5=a GST group")
            }
        }
    }

    @Test
    fun `INV-AC-9593 the same version replaces its record another version keeps both and no profile is applied`() {
        run { env ->
            env.withEntity { tenant, entity ->
                assertThat(env.profiles(tenant, entity.entityRef)).allMatch { it !is AppliedOperatingProfileRecord }
                env.answer(tenant, "V1", mapOf("OQ-1" to "one"))
                env.answer(tenant, "V1", mapOf("OQ-1" to "more"))
                assertThat(env.storedCount()).isEqualTo(1)
                assertThat(env.stored("V1")).isEqualTo(mapOf("OQ-1" to "more"))
                env.answer(tenant, "V2", mapOf("OQ-1" to "one"))
                assertThat(env.storedCount()).isEqualTo(2)
                assertThat(env.stored("V1")).isEqualTo(mapOf("OQ-1" to "more"))
                val thrown =
                    runCatching {
                        env.tenantsApi.getAppliedOperatingProfile(tenant.value.toString(), "TENANT", null)
                    }
                assertThat((thrown.exceptionOrNull() as ApiRefusal).code.httpStatus).isEqualTo(404)
            }
        }
    }

    @Test
    fun `INV-AC-9593 a tenant sees only its own answers`() {
        run { env ->
            val first =
                env.withTenant { tenant ->
                    env.answer(tenant, "2026.1", mapOf("OQ-1" to "one"))
                    tenant
                }
            env.withTenant { tenant ->
                assertThat(env.stored("2026.1")).isNull()
                assertThat(env.storedCount()).isZero()
                assertThat(first).isNotEqualTo(tenant)
            }
        }
    }

    @Test
    fun `an unknown question key and another questionnaire version store the answers and recommend nothing`() {
        run { env ->
            env.withTenant { tenant ->
                val unknown = env.answer(tenant, "2026.1", mapOf("OQ-15" to "x"))
                assertThat(unknown.recommendations).isEmpty()
                assertThat(env.stored("2026.1")).isEqualTo(mapOf("OQ-15" to "x"))
                val other = env.answer(tenant, "2026.2", mapOf("OQ-1" to "one"))
                assertThat(other.recommendations).isEmpty()
                assertThat(env.stored("2026.2")).isEqualTo(mapOf("OQ-1" to "one"))
            }
        }
    }
}
