package com.dynokore.gst.tenancy.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ElectionsTest {
    @Test
    fun `recordElection keys are compared by exact equality`() {
        assertEquals(ElectionKind.PURCHASE_DATE_BASIS, ElectionKeys.recordableKind("INVOICE_DATE"))
        assertEquals(ElectionKind.PURCHASE_DATE_BASIS, ElectionKeys.recordableKind("POSTING_DATE"))
        assertEquals(ElectionKind.APPORTIONMENT_MODE, ElectionKeys.recordableKind("CLAIMABLE_PORTION"))
        assertEquals(ElectionKind.APPORTIONMENT_MODE, ElectionKeys.recordableKind("ENTIRE_INVOICE"))
        listOf(
            "CASH_ACCOUNTING",
            "invoice_date",
            "INVOICE_DATE ",
            " INVOICE_DATE",
            "RELAY",
            "APPROVED_SOURCE",
            "",
        ).forEach {
            assertNull(ElectionKeys.recordableKind(it), it)
        }
    }

    @Test
    fun `an election made is ELECTED whatever the fact says`() {
        FactAnswer.entries.forEach {
            ElectionKind.entries.forEach { kind ->
                assertEquals(ElectionStatus.ELECTED, ElectionRules.status(kind, true, it))
            }
        }
    }

    @Test
    fun `only a fact recorded FALSE makes the apportionment mode or the rate source NOT_REQUIRED`() {
        listOf(ElectionKind.APPORTIONMENT_MODE, ElectionKind.EXCHANGE_RATE_SOURCE).forEach { kind ->
            assertEquals(ElectionStatus.NOT_REQUIRED, ElectionRules.status(kind, false, FactAnswer.FALSE))
            assertEquals(ElectionStatus.OUTSTANDING, ElectionRules.status(kind, false, FactAnswer.TRUE))
            assertEquals(ElectionStatus.OUTSTANDING, ElectionRules.status(kind, false, FactAnswer.NOT_RECORDED))
        }
    }

    @Test
    fun `the issuance mode and the date basis are never NOT_REQUIRED`() {
        listOf(ElectionKind.ISSUANCE_MODE, ElectionKind.PURCHASE_DATE_BASIS).forEach { kind ->
            FactAnswer.entries.forEach {
                assertEquals(
                    ElectionStatus.OUTSTANDING,
                    ElectionRules.status(kind, false, it),
                )
            }
        }
    }

    @Test
    fun `the four elections map to their variation points`() {
        assertEquals(
            mapOf(
                "ISSUANCE_MODE" to "VP-25",
                "PURCHASE_DATE_BASIS" to "VP-22",
                "APPORTIONMENT_MODE" to "VP-23",
                "EXCHANGE_RATE_SOURCE" to "VP-24",
            ),
            ElectionKind.entries.associate { it.name to it.variationPoint },
        )
    }
}
