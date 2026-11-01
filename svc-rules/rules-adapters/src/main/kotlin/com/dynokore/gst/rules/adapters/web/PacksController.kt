package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.application.ActivePacksOutcome
import com.dynokore.gst.rules.application.ListActiveRegulatoryPacks
import com.dynokore.gst.rules.contract.api.MappingPacksApi
import com.dynokore.gst.rules.contract.api.RegulatoryPacksApi
import com.dynokore.gst.rules.contract.model.ActiveRegulatoryPack
import com.dynokore.gst.rules.contract.model.ActiveRegulatoryPackPage
import com.dynokore.gst.rules.contract.model.DryRunRequest
import com.dynokore.gst.rules.contract.model.DryRunResult
import com.dynokore.gst.rules.contract.model.RulesDryRunStatus
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The mapping pack and regulatory pack APIs of the rules contract (TPL-08); 501 until the task that implements an
 * operation (operations.tsv) overrides it.
 *
 * The contract tags `dryRun` and `getDryRun` with both tags, so the generator puts them in both interfaces with the
 * same
 * mapping. One controller must therefore implement both interfaces (two controllers would map the same path twice and
 * Spring MVC refuses to start), and Kotlin requires it to restate those two operations. They are stated here as the
 * explicit 501 and nowhere else; the operations gate (operations.tsv) reads such a body as a stub.
 */
@RestController
open class PacksController(
    private val listActive: ListActiveRegulatoryPacks,
) : MappingPacksApi,
    RegulatoryPacksApi {
    /** The armed regulatory packs of the platform layer for the installer; the caller check is the generated one. */
    override fun listActiveRegulatoryPacks(
        limit: Int,
        cursor: String?,
    ): ResponseEntity<ActiveRegulatoryPackPage> =
        when (val outcome = listActive.handle(limit, cursor)) {
            is ActivePacksOutcome.Page -> {
                ResponseEntity.ok(
                    ActiveRegulatoryPackPage(
                        items =
                            outcome.items.map {
                                ActiveRegulatoryPack(
                                    packType = it.packType,
                                    version = it.version,
                                    status = ActiveRegulatoryPack.Status.forValue(it.status),
                                    minEngineVersion = it.minEngineVersion,
                                    effectiveFrom = it.effectiveFrom,
                                    effectiveTo = it.effectiveTo,
                                )
                            },
                        nextCursor = outcome.nextCursor,
                    ),
                )
            }

            ActivePacksOutcome.MalformedCursor -> {
                throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "cursor is malformed")
            }

            ActivePacksOutcome.InvalidLimit -> {
                throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "limit must be 1..500")
            }
        }

    override fun dryRun(
        packRef: String,
        idempotencyKey: UUID,
        dryRunRequest: DryRunRequest?,
    ): ResponseEntity<DryRunResult> = ResponseEntity(HttpStatus.NOT_IMPLEMENTED)

    override fun getDryRun(
        packRef: String,
        dryRunId: String,
    ): ResponseEntity<RulesDryRunStatus> = ResponseEntity(HttpStatus.NOT_IMPLEMENTED)
}
