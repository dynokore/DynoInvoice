package com.dynokore.gst.rules.bootstrap.gates

/**
 * INV-Q-7442 (A): the column `task` of operations.tsv, compared by equality row by row with the owners the published
 * INV-T-4965 lists (platform-contracts origin/main 980af706: rules/openapi.yaml, rules/asyncapi.yaml).
 */
internal object OperationOwners {
    val expected =
        listOf(
            "operation\tevaluateDocument\tINV-T-4966",
            "operation\tevaluatePreIssuanceDraft\tINV-T-4966",
            "operation\tdraftMappingPack\tINV-T-4972",
            "operation\tgetMappingPack\tINV-T-4972",
            "operation\tlistMappingPackEntries\tINV-T-4972",
            "operation\tmapTaxCode\tINV-T-4972",
            "operation\timportMappingSpreadsheet\tINV-T-4973",
            "operation\tacceptSourceClassEntry\tINV-T-4972",
            "operation\tsubmitForApproval\tINV-T-4972",
            "operation\tapprovePack\tINV-T-4972",
            "operation\tactivateMappingPack\tINV-T-4972",
            "operation\timportRegulatoryPack\tINV-T-4976",
            "operation\tgetRegulatoryPack\tINV-T-4976",
            "operation\tacknowledgePackDelta\tINV-T-4977",
            "operation\tarmRegulatoryPack\tINV-T-4977",
            "operation\trollbackPack\tINV-T-4978",
            "operation\treprocessPopulation\tINV-T-4978",
            "operation\tdryRun\tINV-T-4974",
            "operation\tgetDryRun\tINV-T-4974",
            "channel\tgst.rules.rule-pack-staged.v1\tINV-T-4976",
            "channel\tgst.rules.rule-pack-activated.v1\tINV-T-4977",
            "channel\tgst.rules.pack-currency-changed.v1\tINV-T-4976",
            "channel\tgst.rules.example-happened.v1\tnone",
        )

    fun findings(table: String?): List<String> {
        val rows =
            table
                ?.lines()
                ?.drop(1)
                ?.filter { it.isNotBlank() }
                .orEmpty()
        return listOfNotNull("operations.tsv is missing".takeIf { table == null }) +
            (expected - rows.toSet()).map { "row missing or with another owner: $it" } +
            (rows - expected.toSet()).map { "extra row or another owner: $it" } +
            rows
                .groupBy { it }
                .filterValues { it.size > 1 }
                .keys
                .map { "row listed twice: $it" }
    }
}
