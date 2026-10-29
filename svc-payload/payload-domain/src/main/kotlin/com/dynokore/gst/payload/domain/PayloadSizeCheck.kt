package com.dynokore.gst.payload.domain

/** One size finding with its catalogue code and severity (PAY-010, PAY-015). */
data class SizeFinding(
    val code: String,
    val severity: Severity,
)

/** The verdict of [PayloadSizeCheck.check]; [blocked] is true exactly when a finding has severity BLOCK. */
data class SizeVerdict(
    val findings: List<SizeFinding>,
) {
    val blocked: Boolean get() = findings.any { it.severity == Severity.BLOCK }
}

/** The two size figures: the hard XML byte limit and the recommended line count. */
data class PayloadLimits(
    val maxXmlBytes: Long,
    val recommendedMaxLines: Int,
) {
    init {
        require(maxXmlBytes >= 0) { "maxXmlBytes must be at least 0" }
        require(recommendedMaxLines >= 0) { "recommendedMaxLines must be at least 0" }
    }
}

/** Decides the per-document 10 MB limit (PAY-010, INV-REQ-425) and the 999-line warning (PAY-015). */
object PayloadSizeCheck {
    const val PAY_010 = "PAY-010"
    const val PAY_015 = "PAY-015"

    /**
     * IRAS specification INV-NOTE-2 clause FR-4.12 as quoted by INV-REQ-425: the total XML size must not exceed
     * 10 MB (clause FR-4.11 of INV-REQ-424 says the same for the bulk package) and IRAS recommends no more than
     * 999 line items per invoice. "10 MB is read as 10 000 000 bytes" (INV-T-6254). The 999 is a recommendation
     * only, so it yields a warning and never a block.
     */
    val IRAS_FR_4_12 = PayloadLimits(maxXmlBytes = 10_000_000L, recommendedMaxLines = 999)

    fun check(
        xmlBytes: Long,
        lineCount: Int,
        limits: PayloadLimits = IRAS_FR_4_12,
    ): SizeVerdict {
        require(xmlBytes >= 0) { "xmlBytes must be at least 0" }
        require(lineCount >= 0) { "lineCount must be at least 0" }
        val findings = mutableListOf<SizeFinding>()
        if (xmlBytes > limits.maxXmlBytes) findings += SizeFinding(PAY_010, Severity.BLOCK)
        if (lineCount > limits.recommendedMaxLines) findings += SizeFinding(PAY_015, Severity.WARN)
        return SizeVerdict(findings)
    }
}
