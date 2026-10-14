package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantContextImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource

/**
 * `TenantScope.workloadPrincipal` uses tenancy-kernel's `BlankText.isActor` (coordinator ruling INV-Q-4684 (A);
 * INV-Q-4534, INV-Q-4614, INV-Q-4622; INV-REQ-854 b1): the refusal names the rule and never echoes the value.
 * Construction opens no connection, so no database is needed.
 */
class TenantScopeWorkloadPrincipalTest {
    private val tenants = TenantContextImpl()
    private val runner = KillableTransactionRunner(PGSimpleDataSource(), tenants)

    private fun scope(principal: String) = TenantScope(tenants, runner, runner, principal)

    @Test
    fun `a blank or control-character workload principal is refused without echoing it`() {
        for (sample in listOf("﻿", " 　", "a\tb", "\nx", "x\u0085", "\u001B[31mx", "x\u009B")) {
            val message = assertThrows(IllegalArgumentException::class.java) { scope(sample) }.message.orEmpty()
            assertTrue(message.contains("no control character"), message)
            assertFalse(message.contains(sample))
        }
    }

    @Test
    fun `a valid workload principal is kept unchanged`() {
        for (sample in listOf("x​y", "CASE 5521", ('!'..'~').joinToString("").repeat(3).take(255))) {
            assertEquals(sample, scope(sample).workloadPrincipal)
        }
    }
}
