package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.masterdata.application.rate.ImportRatesOutcome
import com.dynokore.gst.masterdata.application.rate.RateRowRejection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** INV-T-18775 wire codes: each of the six pairs, the pinned catalogue, and the look-alike. */
class RateWireCodesTest {
    private fun refused(outcome: ImportRatesOutcome) = checkNotNull(RateWireCodes.refusal(outcome))

    @Test
    fun `SourceNotElected is 422 MDM-012`() {
        assertThat(refused(ImportRatesOutcome.SourceNotElected).code.code).isEqualTo("MDM-012")
        assertThat(refused(ImportRatesOutcome.SourceNotElected).status).isEqualTo(422)
    }

    @Test
    fun `SourceNotApproved is 422 MDM-013`() {
        assertThat(refused(ImportRatesOutcome.SourceNotApproved).code.code).isEqualTo("MDM-013")
        assertThat(refused(ImportRatesOutcome.SourceNotApproved).status).isEqualTo(422)
    }

    @Test
    fun `RowCountMismatch is 400 SRT-001`() {
        assertThat(refused(ImportRatesOutcome.RowCountMismatch).code.code).isEqualTo("SRT-001")
        assertThat(refused(ImportRatesOutcome.RowCountMismatch).status).isEqualTo(400)
    }

    @Test
    fun `a loaded outcome is no refusal`() {
        assertThat(RateWireCodes.refusal(ImportRatesOutcome.Loaded(0, emptyList()))).isNull()
    }

    @Test
    fun `row rejections map to MDM-014 MDM-015 and SRT-008`() {
        assertThat(RateWireCodes.rowCode(RateRowRejection.CURRENCY_INVALID).code).isEqualTo("MDM-014")
        assertThat(RateWireCodes.rowCode(RateRowRejection.RATE_INVALID).code).isEqualTo("MDM-015")
        assertThat(RateWireCodes.rowCode(RateRowRejection.REVISION_CONFLICT).code).isEqualTo("SRT-008")
    }

    @Test
    fun `every mapped code is a row of the pinned catalogue and each code of the closed types is mapped`() {
        val mapped =
            RateRowRejection.entries.map { RateWireCodes.rowCode(it) } +
                listOf(
                    ImportRatesOutcome.SourceNotElected,
                    ImportRatesOutcome.SourceNotApproved,
                    ImportRatesOutcome.RowCountMismatch,
                ).map { refused(it).code }
        mapped.forEach { assertThat(ErrorCode.fromCode(it.code)).isSameAs(it) }
        assertThat(
            mapped.map { it.code },
        ).containsExactly("MDM-014", "MDM-015", "SRT-008", "MDM-012", "MDM-013", "SRT-001")
    }

    @Test
    fun `a code absent from the catalogue is refused and the look-alike MDM-0120 is no row`() {
        assertThat(ErrorCode.fromCode("MDM-0120")).isNull()
        assertThat(ErrorCode.fromCode("mdm-012")).isNull()
        assertThat(ErrorCode.fromCode("MDM-012 ")).isNull()
        assertThat(ErrorCode.fromCode("MDM-099")).isNull()
    }
}
