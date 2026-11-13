package com.dynokore.gst.consoleBff.adapters.duedates

import com.dynokore.gst.consoleBff.application.duedates.InitialDueDates
import com.dynokore.gst.consoleBff.application.duedates.PeriodRecordsChangedReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import java.time.LocalDate

/**
 * [PeriodRecordsChangedReader] over Jackson (period-records-changed-payload.schema.json). The optional item member
 * `filingDueDate` is read when present and ignored when absent; a present value that is not a date (including JSON
 * null) is a `ContractViolation`.
 */
class JacksonPeriodRecordsChangedReader : PeriodRecordsChangedReader {
    override fun read(dataJson: String): InitialDueDates {
        val data = DueDatePayload.parse(dataJson, TYPE)
        val entityRef = DueDatePayload.text(data, "entityRef", TYPE)
        val periods = data.get("periods")
        if (periods == null ||
            !periods.isArray
        ) {
            throw ContractViolation("$TYPE member periods is missing or not an array")
        }
        val byPeriod = linkedMapOf<String, LocalDate>()
        for (item in periods.values()) {
            if (!item.isObject) throw ContractViolation("$TYPE periods holds a non-object")
            val periodRef = DueDatePayload.text(item, "periodRef", TYPE)
            if (item.has("filingDueDate")) byPeriod[periodRef] = DueDatePayload.date(item, "filingDueDate", TYPE)
        }
        return InitialDueDates(entityRef, byPeriod)
    }

    private companion object {
        const val TYPE = "PeriodRecordsChanged"
    }
}
