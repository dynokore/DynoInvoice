package com.dynokore.platform.documentlifecycle

/** The SDS 33.3 table as TEST DATA only; the library's main and impl sources hold none of these codes. */
object AmendmentFixtures {
    private val supersede = Outcome("SUPERSEDE")

    val taxonomy =
        ChangeTaxonomy(
            classes = listOf("NON_MONETARY", "IDENTITY", "PERIOD_SHIFTING", "MONETARY", "STRUCTURAL"),
            afterTransmission =
                mapOf(
                    "NON_MONETARY" to Outcome("RESUBMIT_SAME_ID"),
                    "IDENTITY" to Outcome("HOLD_FOR_REVIEW", listOf("SUB-016")),
                    "PERIOD_SHIFTING" to Outcome("RESUBMIT", listOf("PER-006")),
                    "MONETARY" to Outcome("HOLD", listOf("SUB-015")),
                    "STRUCTURAL" to Outcome("REJECT", listOf("ING-012")),
                ),
            beforeTransmission =
                mapOf(
                    "NON_MONETARY" to supersede,
                    "IDENTITY" to supersede,
                    "PERIOD_SHIFTING" to supersede,
                    "MONETARY" to supersede,
                    "STRUCTURAL" to Outcome("REJECT", listOf("ING-012")),
                ),
            classifiers =
                mapOf(
                    "deliveryAddress" to FieldClassifier.fixed("NON_MONETARY"),
                    "invoiceNote" to FieldClassifier.fixed("NON_MONETARY"),
                    "lineAmount" to FieldClassifier.fixed("MONETARY"),
                    "documentDate" to FieldClassifier.fixed("PERIOD_SHIFTING"),
                    "documentTypeCode" to FieldClassifier.fixed("STRUCTURAL"),
                    "categoryCode" to
                        FieldClassifier { _, _, _, o, n ->
                            if (o["rate"] != n["rate"]) "MONETARY" else "NON_MONETARY"
                        },
                ),
            unlistedClass = "MONETARY",
        )

    /** Builds a taxonomy from the table with one part replaced, for the refusal tests. */
    fun with(
        classes: List<String> = taxonomy.classes,
        after: Map<String, Outcome> = taxonomy.afterTransmission,
        before: Map<String, Outcome> = taxonomy.beforeTransmission,
        classifiers: Map<String, FieldClassifier> = taxonomy.classifiers,
        unlisted: String? = taxonomy.unlistedClass,
    ) = ChangeTaxonomy(classes, after, before, classifiers, unlisted)
}
