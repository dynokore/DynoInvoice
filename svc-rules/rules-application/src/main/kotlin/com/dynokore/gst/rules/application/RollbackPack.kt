package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.pack.BadPack
import com.dynokore.gst.rules.domain.pack.PackCorrection
import com.dynokore.gst.rules.domain.pack.PackCorrectionResult
import java.time.LocalDate

sealed interface RollbackPackResult {
    /** One new row was written. */
    data class Created(
        val row: CorrectionPackRow,
    ) : RollbackPackResult

    /** A repeat: the stored superseding row, nothing written. */
    data class Repeated(
        val row: CorrectionPackRow,
    ) : RollbackPackResult

    data object Denied : RollbackPackResult

    data object NotFound : RollbackPackResult

    data class Refused(
        val reason: PackCorrectionResult.Refused,
    ) : RollbackPackResult
}

/** Supersedes a bad armed pack by an effective-dated reversal; the bad pack is never changed (INV-REQ-962, 1002). */
class RollbackPack(
    private val permission: PackCorrectionPermissionPort,
    private val registry: PackCorrectionRegistry,
) {
    fun execute(
        packType: String,
        version: String,
        requested: LocalDate,
        actor: String,
        permissionKey: String,
    ): RollbackPackResult =
        if (!permission.mayDo(actor, permissionKey)) {
            RollbackPackResult.Denied
        } else {
            registry.inTransaction { tx -> inTransaction(tx, packType, version, requested) }
        }

    private fun inTransaction(
        tx: PackCorrectionRegistryTx,
        packType: String,
        version: String,
        requested: LocalDate,
    ): RollbackPackResult {
        val bad = tx.find(packType, version)
        val stored = tx.find(packType, version + PackCorrection.SUFFIX)
        return when {
            bad == null -> RollbackPackResult.NotFound
            stored != null && requested == bad.effectiveFrom -> RollbackPackResult.Repeated(stored)
            else -> correct(tx, bad, requested)
        }
    }

    private fun correct(
        tx: PackCorrectionRegistryTx,
        bad: CorrectionPackRow,
        requested: LocalDate,
    ): RollbackPackResult {
        val earlier = tx.latestSuperseded(bad)
        val badPack = BadPack(bad.packType, bad.version, bad.effectiveFrom, bad.status)
        return when (val result = PackCorrection.supersede(badPack, earlier, requested)) {
            is PackCorrectionResult.Refused -> {
                RollbackPackResult.Refused(result)
            }

            is PackCorrectionResult.Superseding -> {
                RollbackPackResult.Created(tx.insertSuperseding(result.spec, checkNotNull(earlier).version))
            }
        }
    }
}
