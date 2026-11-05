package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.InstallationFactOperations
import com.dynokore.gst.access.adapters.http.InstallationJournal
import com.dynokore.gst.access.adapters.http.InstallerCaller
import com.dynokore.gst.access.adapters.http.InstallerIdentity
import com.dynokore.gst.access.adapters.http.WorkloadInstallerCaller
import com.dynokore.gst.access.application.approval.ReadInstallationFactUseCase
import com.dynokore.gst.access.application.approval.RecordInstallationFactUseCase
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID

/**
 * The installation approver fact (INV-T-4936; INV-REQ-992; INV-AC-1048, INV-AC-1063, INV-AC-9553 for the part this
 * service produces): only the installer's workload records, a later value updates, a read before any record is 404,
 * and the audit entry names the acting user ID. The activation record that carries the fact is rules-svc's.
 */
class ApprovalInstallationFactTest {
    private val installer = "system:serviceaccount:installer:installer"

    private fun clear() =
        BoundaryFixture.asSuperuser {
            it.createStatement().use { statement -> statement.execute("DELETE FROM access.installation_approver_fact") }
        }

    private fun operations(
        env: ApprovalEnv,
        who: InstallerIdentity,
    ) = env.context.let { c ->
        InstallationFactOperations(
            c.getBean(RecordInstallationFactUseCase::class.java),
            c.getBean(ReadInstallationFactUseCase::class.java),
            InstallerCaller { who },
            c.getBean(InstallationJournal::class.java),
            c.getBean(PlatformTenantBinding::class.java),
            c.getBean(TenantContext::class.java),
        )
    }

    private fun auditRows(actor: String): List<String> =
        BoundaryFixture.asSuperuser { connection ->
            connection
                .prepareStatement(
                    "select data::text from access.outbox where data::text like ? and data::text like ?",
                ).use { statement ->
                    statement.setString(1, "%recordInstallationApproverFact%")
                    statement.setString(2, "%$actor%")
                    statement.executeQuery().use { rows ->
                        generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                    }
                }
        }

    @Test
    fun `INV-AC-1048 the installer records the fact, a later value updates it and a read answers it`() =
        approvalRun { env ->
            clear()
            val ops = operations(env, InstallerIdentity.Installer(installer))
            val reader = { env.asUser("user:anyone") { ops.getInstallationApproverFact() } }
            assertThat(env.refusal { reader() }.code).isEqualTo(ClosedErrorCode.NOT_FOUND)
            val first = ops.recordInstallationApproverFact(UUID.randomUUID(), false)
            assertThat(first.statusCode.value()).isEqualTo(200)
            assertThat(first.body!!.distinctTaxApprover).isFalse()
            assertThat(reader().body!!.distinctTaxApprover).isFalse()
            val second = ops.recordInstallationApproverFact(UUID.randomUUID(), true)
            assertThat(second.body!!.distinctTaxApprover).isTrue()
            assertThat(reader().body!!.distinctTaxApprover).isTrue()
            assertThat(reader().body!!.recordedAt).isEqualTo(second.body!!.recordedAt)
        }

    @Test
    fun `INV-AC-9553 INV-AC-1063 a deployment without a distinct approver reads false and one with reads true`() =
        approvalRun { env ->
            clear()
            val ops = operations(env, InstallerIdentity.Installer(installer))
            val read = { env.asUser("user:anyone") { ops.getInstallationApproverFact().body!!.distinctTaxApprover } }
            ops.recordInstallationApproverFact(UUID.randomUUID(), false)
            val d1 = listOf(read(), read())
            ops.recordInstallationApproverFact(UUID.randomUUID(), true)
            val d2 = read()
            // the three reads, in the order of the criterion (D1, D1, D2), compared with false, false, true
            assertThat(d1 + d2).containsExactly(false, false, true)
        }

    @Test
    fun `INV-AC-1048 an identity other than the installer is 403, no token is 401 and nothing is stored`() =
        approvalRun { env ->
            clear()
            val other = operations(env, InstallerIdentity.Other)
            val nobody = operations(env, InstallerIdentity.Absent)
            assertThat(env.refusal { other.recordInstallationApproverFact(UUID.randomUUID(), false) }.code)
                .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(env.refusal { nobody.recordInstallationApproverFact(UUID.randomUUID(), false) }.code)
                .isEqualTo(ClosedErrorCode.UNAUTHENTICATED)
            val reader = operations(env, InstallerIdentity.Installer(installer))
            assertThat(env.refusal { env.asUser("user:anyone") { reader.getInstallationApproverFact() } }.code)
                .isEqualTo(ClosedErrorCode.NOT_FOUND)
        }

    @Test
    fun `INV-AC-1048 the workload caller admits a configured installer subject and no look-alike`() {
        val verifier =
            WorkloadTokenVerifier { token ->
                when (token?.value) {
                    "installer" -> WorkloadVerification.Verified(installer)
                    "installer-2" -> WorkloadVerification.Verified("$installer-2")
                    "gateway" -> WorkloadVerification.Verified("system:serviceaccount:gateway:api-gateway")
                    else -> WorkloadVerification.Refused("invalid")
                }
            }

        fun identify(
            workload: String?,
            authorization: String? = null,
        ): InstallerIdentity {
            val request = MockHttpServletRequest()
            workload?.let { request.addHeader(WorkloadToken.HEADER_NAME, it) }
            authorization?.let { request.addHeader("Authorization", it) }
            return WorkloadInstallerCaller(request, verifier, setOf(installer)).identify()
        }
        assertThat(identify("installer")).isEqualTo(InstallerIdentity.Installer(installer))
        assertThat(identify("installer-2")).isEqualTo(InstallerIdentity.Other)
        assertThat(identify("gateway")).isEqualTo(InstallerIdentity.Other)
        assertThat(identify("forged")).isEqualTo(InstallerIdentity.Absent)
        assertThat(identify(null)).isEqualTo(InstallerIdentity.Absent)
        assertThat(identify(" ")).isEqualTo(InstallerIdentity.Absent)
        // an end-user token is never the installer's, whatever its subject
        assertThat(identify(null, "Bearer tok:$installer")).isEqualTo(InstallerIdentity.Other)
        assertThat(identify("gateway", "Bearer tok:$installer")).isEqualTo(InstallerIdentity.Other)
    }

    @Test
    fun `INV-AC-1048 a repeat with the same Idempotency-Key returns the original and writes one audit entry`() =
        approvalRun { env ->
            clear()
            val actor = "system:serviceaccount:installer:repeat-${UUID.randomUUID()}"
            val ops = operations(env, InstallerIdentity.Installer(actor))
            val key = UUID.randomUUID()
            val first = ops.recordInstallationApproverFact(key, false)
            val again = ops.recordInstallationApproverFact(key, false)
            assertThat(again.body).isEqualTo(first.body)
            assertThat(auditRows(actor)).hasSize(1)
            // the same key with another body is SRT-009 and stores nothing
            val different = env.refusal { ops.recordInstallationApproverFact(key, true) }
            assertThat(different.code).isEqualTo(ClosedErrorCode.DIFFERENT_REQUEST)
            assertThat(auditRows(actor)).hasSize(1)
            assertThat(
                env.asUser("user:anyone") { ops.getInstallationApproverFact().body!!.distinctTaxApprover },
            ).isFalse()
        }

    @Test
    fun `INV-AC-1048 the audit entry names the acting user ID and the prior value, never a role`() =
        approvalRun { env ->
            clear()
            val actor = "system:serviceaccount:installer:audit-${UUID.randomUUID()}"
            val ops = operations(env, InstallerIdentity.Installer(actor))
            ops.recordInstallationApproverFact(UUID.randomUUID(), false)
            ops.recordInstallationApproverFact(UUID.randomUUID(), true)
            val rows = auditRows(actor).map { env.json(it) }
            assertThat(rows).hasSize(2)
            rows.forEach {
                assertThat(it.toString()).contains(actor).doesNotContain("deployment.operator")
            }
            assertThat(rows.map { it.toString() }.count { it.contains("distinctTaxApprover") }).isEqualTo(1)
        }
}
