package com.dynokore.platform.serviceruntime.persistence

import com.dynokore.platform.serviceruntime.persistence.jooq.tables.records.AccountRecord
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.records.AuditedRecord
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.references.ACCOUNT
import com.dynokore.platform.serviceruntime.persistence.jooq.tables.references.AUDITED
import com.dynokore.platform.tenancykernel.TenantContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jooq.RecordContext
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AuditColumnRecordListenerTest {
    private val clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC)

    private fun context(record: AuditedRecord) = mockk<RecordContext> { every { record() } returns record }

    private fun record() =
        AuditedRecord().apply {
            set(AUDITED.ID, UUID.randomUUID())
            set(AUDITED.TENANT_ID, UUID.randomUUID())
            set(AUDITED.LABEL, "l")
            set(AUDITED.VERSION, 0L)
        }

    @Test
    fun `an insert under a binding with no actor into an audit-column table is refused`() {
        val tenants = mockk<TenantContext> { every { currentActor() } returns null }
        val record = record()
        shouldThrow<AuditColumnViolation> { AuditColumnRecordListener(clock, tenants).insertStart(context(record)) }
        record.get(AUDITED.CREATED_BY) shouldBe null
    }

    @Test
    fun `a blank actor is refused like none`() {
        val tenants = mockk<TenantContext> { every { currentActor() } returns " " }
        shouldThrow<AuditColumnViolation> { AuditColumnRecordListener(clock, tenants).insertStart(context(record())) }
    }

    @Test
    fun `an insert sets all four columns`() {
        val tenants = mockk<TenantContext> { every { currentActor() } returns "user:alice" }
        val record = record()
        AuditColumnRecordListener(clock, tenants).insertStart(context(record))
        record.get(AUDITED.CREATED_BY) shouldBe "user:alice"
        record.get(AUDITED.UPDATED_BY) shouldBe "user:alice"
        record.get(AUDITED.CREATED_AT)?.toInstant() shouldBe clock.instant()
        record.get(AUDITED.UPDATED_AT)?.toInstant() shouldBe clock.instant()
    }

    @Test
    fun `a table without the columns is untouched and needs no actor`() {
        val tenants = mockk<TenantContext> { every { currentActor() } returns null }
        val record = AccountRecord().apply { set(ACCOUNT.NAME, "n") }
        AuditColumnRecordListener(
            clock,
            tenants,
        ).insertStart(mockk<RecordContext> { every { record() } returns record })
        record.touched() shouldBe true
        record.get(ACCOUNT.NAME) shouldBe "n"
    }
}
