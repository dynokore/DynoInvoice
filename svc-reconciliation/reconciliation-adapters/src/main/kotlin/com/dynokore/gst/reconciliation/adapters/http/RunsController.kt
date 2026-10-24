package com.dynokore.gst.reconciliation.adapters.http

import com.dynokore.gst.reconciliation.contract.api.RunsApi
import org.springframework.web.bind.annotation.RestController

/**
 * The run, work-paper and attestation operations of the reconciliation contract (TPL-08). Every operation answers 501
 * until the task named for it in `operations.tsv` overrides its method: the generated interface's default is
 * NOT_IMPLEMENTED.
 */
@RestController
open class RunsController : RunsApi
