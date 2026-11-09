package com.dynokore.gst.masterdata.application.rate

import com.dynokore.gst.masterdata.application.gapreport.RecordState
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Instant
import java.time.LocalDate

/** The record of one accepted rate import (INV-REQ-506): applied, failed and rejected rows. */
data class RateImportRecord(
    val importId: String,
    val entity: String,
    val actor: String,
    val source: String,
    val rangeFrom: LocalDate,
    val rangeTo: LocalDate,
    val state: RecordState,
    val startedAt: Instant,
    val completedAt: Instant?,
    val appliedCount: Int?,
    val failedCount: Int?,
    val rejectedRows: List<RejectedRateRow>?,
    val error: String?,
)

/** The rate import records of the bound tenant; every call runs inside the caller's transaction. */
interface RateImportStore {
    @Suppress("LongParameterList") // the columns of the record
    fun insertRunning(
        importId: String,
        entity: String,
        actor: String,
        source: String,
        rangeFrom: LocalDate,
        rangeTo: LocalDate,
    ): RateImportRecord

    @Suppress("LongParameterList") // the columns of the finalised record
    fun finish(
        importId: String,
        completedAt: Instant,
        appliedCount: Int,
        failedCount: Int,
        rejectedRows: List<RejectedRateRow>,
    )

    fun fail(importId: String)

    fun find(importId: String): RateImportRecord?

    /** Finalises FAILED every RUNNING record started before [before]; returns how many. */
    fun failRunningBefore(before: Instant): Int
}

/** `getImportRecord` for rate imports: the record only to the actor that started it (equality), else null. */
class ReadRateImportRecord(
    private val tx: TransactionRunner,
    private val store: RateImportStore,
) {
    fun handle(
        importId: String,
        actor: String,
    ): RateImportRecord? = tx.inTransaction { store.find(importId) }?.takeIf { it.actor == actor }
}
