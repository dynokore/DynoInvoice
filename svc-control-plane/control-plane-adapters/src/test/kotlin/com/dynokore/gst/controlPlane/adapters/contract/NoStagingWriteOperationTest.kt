package com.dynokore.gst.controlPlane.adapters.contract

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * The API-call probe of the staging write path (coordinator ruling INV-Q-4878): no operation of the published contract
 * writes a staging record. Deny by default: every write operation (POST, PUT, PATCH, DELETE) is classified in
 * [WRITES] with the one thing it writes, and a write operation not in the list fails the test, so a new operation is
 * a finding until it is classified. None of the listed operations takes a staging result or a pack content hash.
 */
class NoStagingWriteOperationTest {
    private val spec: Map<String, Any?> =
        Yaml().load(File(System.getProperty("gst.contracts.dir"), "openapi.yaml").readText())

    private fun operations(): Map<String, Pair<String, Map<*, *>>> {
        val paths = spec["paths"] as Map<*, *>
        val out = LinkedHashMap<String, Pair<String, Map<*, *>>>()
        for ((path, item) in paths) {
            for ((method, operation) in item as Map<*, *>) {
                if (method in setOf("get", "head", "options")) continue
                val operationId = (operation as Map<*, *>)["operationId"] as String
                out[operationId] = "$method $path" to operation
            }
        }
        return out
    }

    @Test
    fun `every write operation of the contract is classified and none writes a staging record`() {
        operations().keys.filter { it !in WRITES } shouldBe emptyList()
    }

    @Test
    fun `no write operation names staging in its path or id, or takes a staging result or a content hash`() {
        val forbidden = Regex("staging|packContentHash|stagingRecord", RegexOption.IGNORE_CASE)
        val offenders =
            operations().filter { (id, entry) ->
                val body = ((entry.second["requestBody"]) ?: emptyMap<String, Any?>()).toString()
                forbidden.containsMatchIn(id) || forbidden.containsMatchIn(entry.first) ||
                    forbidden.containsMatchIn(body) || referencedSchemaNames(body).any { forbidden.containsMatchIn(it) }
            }
        offenders.keys shouldBe emptySet<String>()
    }

    private fun referencedSchemaNames(body: String): List<String> =
        Regex("""#/components/schemas/(\w+)""").findAll(body).map { it.groupValues[1] }.toList()

    private companion object {
        /** Each write operation of the contract and what it writes (one line per entry). */
        val WRITES =
            setOf(
                "authorRegulatoryPack", // a draft pack version
                "signPack", // the signature of a version
                "publishPack", // the publication of a signed version (reads the staging record, never writes it)
                "publishTrain", // a release train
                "setSupportWindow", // the supported-version window
                "managePlan", // a plan
                "issueLicence", // a licence
                "recordQualification", // a licence qualification
                "provisionTenant", // a tenant provisioning request
                "recordAccreditation", // the accreditation record
                "concludeAccreditationTask", // the conclusion of an accreditation task
                "recordGateRun", // a gate-run result of a train (INV-T-8749); never a pack staging record
                "purgeAuditEntries", // the purge of expired audit entries
                "requestFirstAdministratorInvitationReissue", // a request to reissue a tenant's first invitation
                "setTenantSchedule", // a tenant's maintenance schedule
                "approveTenantSchedule", // the approval of a tenant schedule
                "changeTenantSchedule", // a change of a tenant schedule
                "cancelTenantSchedule", // the cancellation of a tenant schedule
            )
    }
}
