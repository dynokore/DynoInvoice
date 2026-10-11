package com.dynokore.platform.configresolver

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * The installation's business date (ruling INV-Q-2940): the calendar date in Asia/Singapore, never the UTC
 * date. Use it only for an as-of view that has no transaction date (e.g. the policies screen's "effective
 * today"); a business decision resolves at its document's transaction date (CS-28, INV-REQ-1351).
 */
object BusinessDate {
    val ZONE: ZoneId = ZoneId.of("Asia/Singapore")

    fun today(clock: Clock): LocalDate = clock.instant().atZone(ZONE).toLocalDate()
}
