package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.INSTALLER
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.OTHER
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.CALLERS
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.operation
import com.dynokore.gst.access.bootstrap.served.ServedContext
import com.dynokore.gst.access.bootstrap.served.USER_A_TOKEN
import com.dynokore.gst.access.bootstrap.served.workloadToken
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import java.util.UUID

/**
 * The installation approver fact (INV-T-4936; INV-REQ-992; INV-AC-1048, INV-AC-1063, INV-AC-9553 for the part this
 * service produces), through the real context with the caller check that service-runtime generates from the contract
 * for the edge `installer->access-svc` (INV-Q-17002 A): only the installer records, a later value updates, a read
 * before any record is 404, a repeated key follows INV-Q-15370, and the audit entry names the acting user ID. The
 * activation record that carries the fact is rules-svc's.
 */
class ApprovalInstallationFactTest {
    private val record = "recordInstallationApproverFact"
    private val read = "getInstallationApproverFact"
    private val installerActor = WorkloadPrincipal(INSTALLER).value

    private fun clear() =
        BoundaryFixture.asSuperuser {
            it.createStatement().use { statement ->
                statement.execute("DELETE FROM access.installation_approver_fact")
                statement.execute("DELETE FROM access.installation_fact_request")
                statement.execute("DELETE FROM access.idempotency_key WHERE principal LIKE 'svc:%'")
                statement.execute("DELETE FROM access.outbox WHERE data::text LIKE '%recordInstallationApproverFact%'")
            }
        }

    private fun withInstallation(block: (ServedContext) -> Unit) =
        FirstAdministratorFixture.withService(callers = CALLERS + "installer-alt") { served ->
            clear()
            block(served)
        }

    private fun ServedContext.put(
        caller: String?,
        distinct: Boolean,
        key: UUID = UUID.randomUUID(),
        auth: String? = null,
    ): MockHttpServletResponse {
        (application.getBean(ServiceCaller::class.java) as MovableServiceCaller).value = caller
        val headers = mutableMapOf<String, String>()
        caller?.let { headers["X-Workload-Token"] = workloadToken(it) }
        auth?.let { headers["Authorization"] = it }
        return post(operation(record), headers, """{"distinctTaxApprover":$distinct}""", key)
    }

    private fun ServedContext.get(): MockHttpServletResponse {
        (application.getBean(ServiceCaller::class.java) as MovableServiceCaller).value = "console-bff"
        return call(operation(read), workloadToken("console-bff"), USER_A_TOKEN)
    }

    private fun factOf(response: MockHttpServletResponse): Boolean? =
        json(response).get("distinctTaxApprover")?.asBoolean()

    private fun auditRows(actor: String = installerActor): List<String> =
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
        withInstallation { served ->
            assertThat(served.get().status).isEqualTo(404)
            assertThat(json(served.get()).toString()).contains("SRT-005")
            val first = served.put(INSTALLER, false)
            assertThat(first.status).isEqualTo(200)
            assertThat(factOf(first)).isFalse()
            assertThat(factOf(served.get())).isFalse()
            val second = served.put(INSTALLER, true)
            assertThat(factOf(second)).isTrue()
            val reading = json(served.get())
            assertThat(reading.get("distinctTaxApprover").asBoolean()).isTrue()
            assertThat(reading.get("recordedAt").asString()).isEqualTo(json(second).get("recordedAt").asString())
        }

    @Test
    fun `INV-AC-9553 INV-AC-1063 a deployment without a distinct approver reads false and one with reads true`() =
        withInstallation { served ->
            served.put(INSTALLER, false)
            val d1 = listOf(factOf(served.get()), factOf(served.get()))
            served.put(INSTALLER, true)
            // the three reads, in the order of the criterion (D1, D1, D2), compared with false, false, true
            assertThat(d1 + factOf(served.get())).containsExactly(false, false, true)
        }

    @Test
    fun `INV-AC-1048 another service is 403, no workload token or a person alone is 401 and nothing is stored`() =
        withInstallation { served ->
            val before = served.stored()
            listOf("installer-alt", OTHER).forEach {
                val refused = served.put(it, true)
                assertThat(refused.status).isEqualTo(403)
                assertThat(refused.contentAsString).contains("SRT-004")
            }
            // an end-user token with another service's workload token
            assertThat(served.put(OTHER, true, auth = USER_A_TOKEN).status).isEqualTo(403)
            // no workload token, and a person's end-user token alone
            val nobody = served.put(null, true)
            assertThat(nobody.status).isEqualTo(401)
            assertThat(nobody.contentAsString).contains("SRT-003")
            assertThat(served.put(null, true, auth = USER_A_TOKEN).status).isEqualTo(401)
            assertThat(served.stored()).isEqualTo(before)
            assertThat(served.get().status).isEqualTo(404)
        }

    @Test
    fun `INV-AC-1048 a repeat with the same Idempotency-Key returns the original and writes one audit entry`() =
        withInstallation { served ->
            val key = UUID.randomUUID()
            val first = served.put(INSTALLER, false, key)
            val again = served.put(INSTALLER, false, key)
            assertThat(again.status).isEqualTo(200)
            assertThat(again.contentAsString).isEqualTo(first.contentAsString)
            assertThat(auditRows()).hasSize(1)
            // the same key with another body is SRT-009 and stores nothing
            val different = served.put(INSTALLER, true, key)
            assertThat(different.contentAsString).contains("SRT-009")
            assertThat(auditRows()).hasSize(1)
            assertThat(factOf(served.get())).isFalse()
        }

    @Test
    fun `INV-AC-1048 a key repeated after the fact changed is applied as an update`() =
        withInstallation { served ->
            val k1 = UUID.randomUUID()
            val k2 = UUID.randomUUID()
            val a = served.put(INSTALLER, true, k1)
            val b = served.put(INSTALLER, false, k2)
            val c = served.put(INSTALLER, true, k1)
            assertThat(listOf(a.status, b.status, c.status)).containsOnly(200)
            assertThat(factOf(c)).isTrue()
            assertThat(factOf(served.get())).isTrue()
            assertThat(auditRows()).hasSize(3)
            assertThat(json(c).get("recordedAt").asString()).isNotEqualTo(json(a).get("recordedAt").asString())
            // the response under the key is the new one: a fourth call with k1 and the fact unchanged replays it
            val d = served.put(INSTALLER, true, k1)
            assertThat(d.contentAsString).isEqualTo(c.contentAsString)
            assertThat(auditRows()).hasSize(3)
        }

    @Test
    fun `INV-AC-1048 the audit entry names the acting user ID and the prior value, never a role`() =
        withInstallation { served ->
            served.put(INSTALLER, false)
            served.put(INSTALLER, true)
            val rows = auditRows().map { json(it) }
            assertThat(rows).hasSize(2)
            rows.forEach {
                assertThat(it.toString()).contains(installerActor).doesNotContain("deployment.operator")
            }
            assertThat(rows.map { it.toString() }.count { it.contains("distinctTaxApprover") }).isEqualTo(1)
        }
}
