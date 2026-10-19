package com.dynokore.gst.rules.bootstrap.gates

import org.junit.jupiter.api.Test

/** The Gradle check `verifyOperations` runs this class (and only this one) against the repository itself. */
class OperationsGate {
    @Test
    fun `operations-tsv lists the contract and no done task answers 501`() {
        val contract = "rules-contract/build/generated/contract-resources/contract"
        val findings =
            OperationsTable.check(
                RepositoryGates.read("operations.tsv"),
                checkNotNull(RepositoryGates.read("$contract/openapi.yaml")) {
                    "the staged contract is missing: run :rules-contract:stageContractResources"
                },
                checkNotNull(RepositoryGates.read("$contract/asyncapi.yaml")) {
                    "the staged contract is missing: run :rules-contract:stageContractResources"
                },
                RepositoryGates.adapterSources(),
            )
        check(findings.isEmpty()) { "verifyOperations failed:\n" + findings.joinToString("\n") { " - $it" } }
    }
}
