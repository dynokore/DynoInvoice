package com.dynokore.gst.tenancy.domain

/** The permission keys of the period operations, compared with the catalogue key by string equality (INV-Q-10127). */
object PeriodPermissions {
    const val FILING_FREQUENCY_CHANGE = "filing-frequency.change"
    const val PERIOD_CONFIRM = "period.confirm"

    /** recordExtension: the catalogue row `period-extension.record` (gstCatalogue 2.31.0). */
    const val EXTENSION_RECORD = "period-extension.record"
}
