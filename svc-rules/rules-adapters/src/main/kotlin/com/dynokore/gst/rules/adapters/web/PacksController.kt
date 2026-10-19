package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.contract.api.MappingPacksApi
import com.dynokore.gst.rules.contract.api.RegulatoryPacksApi
import com.dynokore.gst.rules.contract.model.DryRunRequest
import com.dynokore.gst.rules.contract.model.DryRunResult
import com.dynokore.gst.rules.contract.model.RulesDryRunStatus
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
open class PacksController :
    MappingPacksApi,
    RegulatoryPacksApi {
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
