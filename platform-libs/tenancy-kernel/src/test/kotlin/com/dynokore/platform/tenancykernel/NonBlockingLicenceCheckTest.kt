package com.dynokore.platform.tenancykernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

class NonBlockingLicenceCheckTest {
    private val tenant = TenantId(UUID.randomUUID())

    @Test
    fun `passes through a well-behaved delegate's status`() {
        val check =
            NonBlockingLicenceCheck(delegate = LicenceCheck { LicenceStatus.OK }, timeout = Duration.ofSeconds(1))
        assertEquals(LicenceStatus.OK, check.status(tenant))
    }

    @Test
    fun `a delegate that throws becomes UNKNOWN, never propagated`() {
        val check =
            NonBlockingLicenceCheck(
                delegate = LicenceCheck { error("entitlement source unreachable") },
                timeout = Duration.ofSeconds(1),
            )
        assertEquals(LicenceStatus.UNKNOWN, check.status(tenant))
    }

    @Test
    fun `a delegate slower than the timeout becomes UNKNOWN, and status() itself never blocks past it (FR-G_12)`() {
        val check =
            NonBlockingLicenceCheck(
                delegate =
                LicenceCheck {
                    Thread.sleep(5_000)
                    LicenceStatus.EXPIRED
                },
                timeout = Duration.ofMillis(100),
            )
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            assertEquals(LicenceStatus.UNKNOWN, check.status(tenant))
        }
    }
}
