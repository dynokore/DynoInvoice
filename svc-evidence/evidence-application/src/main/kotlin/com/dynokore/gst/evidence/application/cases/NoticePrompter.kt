package com.dynokore.gst.evidence.application.cases

import com.dynokore.platform.calendarandperiods.noticeDue
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.LocalDate

/** The notice step (INV-REQ-1403 behavior 3). It runs for one tenant per call. */
class NoticePrompter(
    private val store: PrivacyCaseStore,
    private val policy: PolicyValues,
    private val events: NoticeEvents,
    private val clock: Clock,
) {
    /** Marks and records the notice of every unprompted case that is due on [today]; returns how many. */
    fun promptNotices(
        tenant: TenantId,
        today: LocalDate,
    ): Int {
        var count = 0
        for (record in store.findUnprompted(tenant)) {
            val facts = record.privacy ?: continue
            val lead = policy.noticeLeadDays(tenant, today)
            if (noticeDue(facts.responseDeadline, today, lead)) {
                store.markNoticePrompted(tenant, record.caseId, clock.instant())
                events.record(tenant, record, lead)
                count++
            }
        }
        return count
    }
}
